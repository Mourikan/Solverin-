package com.solverin.engine.preflop

import com.solverin.engine.eval.RangeEquity
import com.solverin.engine.math.FoldModel
import com.solverin.engine.math.ImpliedOdds
import com.solverin.engine.math.RakeModel
import com.solverin.engine.math.Realization

/**
 * Master preflop EV equation, per hand class, card-removal exact:
 *
 *  EV(raise) = P(fold)·(Pot + Dead)
 *            + P(call)·[EQ(h, callRange)·R_real·PotTotal − Investment − Rake_model] + EV_implied
 *            + P(reraise)·EV_vsReraise          (hero then folds / calls / jams: same equation one level deeper)
 *
 *  EV(call)  = (1 − P(squeeze))·[EQ·R_real·PotTotal − Investment − Rake_model + EV_implied] − P(squeeze)·Investment
 *
 *  P(fold)   = 1 − MDF·BlockerFactor·TendencyMultiplier      ([FoldModel])
 *  R_real    = BasePos·Initiative·(1 + Playability − DominationPenalty)   ([Realization])
 *  Rake      = min(Pot_postflop_avg·%rake, cap)              ([RakeModel], attribution per context)
 *  EV(fold)  = 0 (posted chips are sunk).
 */
class PreflopMathEngine(
    private val ctx: PreflopContext,
    private val eq: RangeEquity,
    private val implied: ImpliedOdds,
) {
    private val stack = ctx.effectiveStackBb

    private fun behind(invested: Double) = (stack - invested).coerceAtLeast(0.0)
    private fun spr(pot: Double, invested: Double) = if (pot <= 0) 0.0 else behind(invested) / pot
    private fun rake(pot: Double, invested: Double, eqR: Double) =
        RakeModel.charge(ctx.rake, ctx.rakeAttribution, pot, behind(invested), eqR)

    fun call(h: Int, n: CallNode): EvTerms {
        val to = minOf(n.callTo, stack)
        val invest = to - n.heroInvested
        val pot = n.potBefore + invest
        val players = n.villainRanges.size + 1
        val main = n.villainRanges[0]
        val e = if (n.villainRanges.size == 1) eq.vs(h, main)
        else eq.multiway(eq.vs(h, main), eq.vs(h, n.villainRanges[1]))
        val s = spr(pot, to)
        val r = Realization.compute(h, main, n.heroInPosition, aggressor = false, spr = s, players = players)
        val eqR = minOf(e * r.value, MAX_EQR).toDouble()
        val rk = rake(pot, to, eqR)
        var impl = implied.ev(h, main, pot, behind(to), n.heroInPosition)
        if (players > 2) impl *= Realization.MULTIWAY_DECAY
        val branch = eqR * pot - invest - rk + impl
        val pSq = n.squeezeRisk
        return EvTerms(
            pFold = 0.0, pCall = 1 - pSq, pReraise = pSq, foldWin = 0.0,
            mdf = 0.0, blockerFactor = 1.0, tendency = 0.0,
            equity = e, basePos = r.basePos, initiative = r.initiative, playability = r.playability,
            domination = r.domination, realization = r.value, realizationNoDomination = r.valueWithoutDomination,
            potTotal = pot, investment = invest, rake = rk, implied = impl,
            evVsReraise = -invest, ev = (1 - pSq) * branch - pSq * invest,
        )
    }

    fun raise(h: Int, n: RaiseNode): EvTerms {
        val x = minOf(n.raiseTo, stack)
        val invest = x - n.heroInvested
        val potAfterRaise = n.potBefore + invest
        var reach = 1.0
        var ev = 0.0
        var pCall = 0.0; var pRe = 0.0; var reEv = 0.0
        var fm: FoldModel.Terms? = null
        var e0 = 0f; var r0: Realization.Terms? = null; var pot0 = 0.0; var rake0 = 0.0; var impl0 = 0.0
        for (o in n.opponents) {
            val f = FoldModel.evaluate(h, n.potBefore, invest, o.response.fold, o.response.call, o.response.raise)
            if (fm == null) fm = f
            val pc = f.pContinue * (1 - f.reraiseShare)
            val pr = f.pContinue * f.reraiseShare
            if (pc > 0) {
                val pot = potAfterRaise + (x - o.invested)
                val e = eq.vs(h, o.response.call)
                val s = spr(pot, x)
                val r = Realization.compute(h, o.response.call, o.heroInPosition, aggressor = true, spr = s)
                val eqR = minOf(e * r.value, MAX_EQR).toDouble()
                val rk = rake(pot, x, eqR)
                val impl = implied.ev(h, o.response.call, pot, behind(x), o.heroInPosition)
                ev += reach * pc * (eqR * pot - invest - rk + impl)
                pCall += reach * pc
                if (r0 == null) { e0 = e; r0 = r; pot0 = pot; rake0 = rk; impl0 = impl }
            }
            if (pr > 0) {
                val v = vsReraise(h, o, x, invest, potAfterRaise)
                ev += reach * pr * v
                pRe += reach * pr; reEv += reach * pr * v
            }
            reach *= f.pFold
        }
        ev += reach * n.potBefore
        return EvTerms(
            pFold = reach, pCall = pCall, pReraise = pRe, foldWin = n.potBefore,
            mdf = fm?.mdf ?: 0.0, blockerFactor = fm?.blockerFactor ?: 1.0, tendency = fm?.tendency ?: 0.0,
            equity = e0, basePos = r0?.basePos ?: 0.0, initiative = r0?.initiative ?: 0.0,
            playability = r0?.playability ?: 0.0, domination = r0?.domination ?: 0.0, realization = r0?.value ?: 0f,
            realizationNoDomination = r0?.valueWithoutDomination ?: 0f,
            potTotal = pot0, investment = invest, rake = rake0, implied = impl0,
            evVsReraise = if (pRe > 0) reEv / pRe else 0.0, ev = ev,
        )
    }

    /** Equity vs the first opponent's continuing range: >= 0.5 => raise is for value. */
    fun valueEquity(h: Int, n: RaiseNode): Float {
        val o = n.opponents.firstOrNull() ?: return 0f
        return eq.vs(h, o.response.continueRange)
    }

    /** Hero's best continuation after villain re-raises, relative to hero's original fold. */
    private fun vsReraise(h: Int, o: Opponent, x: Double, invest: Double, potAfterRaise: Double): Double {
        val z = minOf(o.reraiseTo, stack)
        val pr = potAfterRaise + (z - o.invested)
        val range = o.response.raise
        val e = eq.vs(h, range)
        val callEv = if (z >= stack) {
            val fin = pr + (stack - x)
            e * fin - (stack - x) - rake(fin, stack, e.toDouble())
        } else {
            val pot = pr + (z - x)
            val s = spr(pot, z)
            val r = Realization.compute(h, range, o.heroInPosition, aggressor = false, spr = s)
            val eqR = minOf(e * r.value, MAX_EQR).toDouble()
            eqR * pot - (z - x) - rake(pot, z, eqR) + implied.ev(h, range, pot, behind(z), o.heroInPosition)
        }
        var jamEv = Double.NEGATIVE_INFINITY
        val cont = o.continuesVsJam
        if (z < stack && cont != null) {
            val fold = FloatArray(range.size) { (range[it] - cont[it]).coerceAtLeast(0f) }
            val f = FoldModel.evaluate(h, pr, stack - x, fold, cont, FloatArray(range.size))
            val fin = pr + (stack - x) + (stack - z)
            val ej = eq.vs(h, cont).toDouble()
            jamEv = f.pFold * pr + f.pContinue * (ej * fin - (stack - x) - rake(fin, stack, ej))
        }
        return -invest + maxOf(0.0, callEv, jamEv)
    }

    companion object {
        const val MAX_EQR = 0.98f
    }
}
