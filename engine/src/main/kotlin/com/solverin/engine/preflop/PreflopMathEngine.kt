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
 *  EV_vsReraise = −Investment + Σ_a mix_a·EV_a at hero's own solved child node (backward induction:
 *  child bluff limits and stake policy apply), or priced in place when there is no child node.
 */
class PreflopMathEngine(
    private val ctx: PreflopContext,
    private val eq: RangeEquity,
    private val implied: ImpliedOdds,
    /** Solved child node per opponent (backward induction for EV_vsReraise); null = price in place. */
    private val child: (Opponent) -> SpotAnalysis? = { null },
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
        val r = Realization.compute(h, main, n.heroInPosition, aggressor = false, spr = s, players = players, potLevel = n.potLevel)
        val penalty = if (!ctx.policy.heroDiscipline) 1.0
        else Realization.callPenalty(h, n.potLevel, n.heroInvested, if (invest > 0) behind(to) / invest else Double.POSITIVE_INFINITY)
        val rEff = r.value * penalty * n.realizationFactor * (1 - n.overcallBehind * (1 - Realization.MULTIWAY_DECAY))
        val eqR = minOf(e * rEff, MAX_EQR.toDouble())
        val rk = rake(pot, to, eqR)
        var impl = implied.ev(h, main, pot, behind(to), n.heroInPosition)
        if (players > 2) impl *= Realization.MULTIWAY_DECAY
        val branch = eqR * pot - invest - rk + impl
        val pSq = n.squeezeRisk
        return EvTerms(
            pFold = 0.0, pCall = 1 - pSq, pReraise = pSq, foldWin = 0.0,
            mdf = 0.0, blockerFactor = 1.0, tendency = 0.0,
            equity = e, basePos = r.basePos, initiative = r.initiative, playability = r.playability,
            domination = r.domination, realization = rEff.toFloat(),
            realizationNoDomination = (r.valueWithoutDomination * (1 - n.overcallBehind * (1 - Realization.MULTIWAY_DECAY))).toFloat(),
            potTotal = pot, investment = invest, rake = rk, implied = impl,
            evVsReraise = -invest, calledEv = branch, ev = (1 - pSq) * branch - pSq * invest,
        )
    }

    fun raise(h: Int, n: RaiseNode): EvTerms {
        val x = minOf(n.raiseTo, stack)
        val invest = x - n.heroInvested
        val potAfterRaise = n.potBefore + invest
        var reach = 1.0
        var ev = 0.0
        var pCall = 0.0; var pRe = 0.0; var reEv = 0.0; var callEvSum = 0.0; var rEff0 = 0f
        var fm: FoldModel.Terms? = null
        var e0 = 0f; var r0: Realization.Terms? = null; var pot0 = 0.0; var rake0 = 0.0; var impl0 = 0.0
        val mainIdx = n.mainIndex
        for ((idx, o) in n.opponents.withIndex()) {
            val isMain = idx == mainIdx
            val f = FoldModel.evaluate(h, n.potBefore, invest, o.response.fold, o.response.call, o.response.raise)
            if (isMain) fm = f
            val pc = f.pContinue * (1 - f.reraiseShare)
            val pr = f.pContinue * f.reraiseShare
            if (pc > 0) {
                val pot = potAfterRaise + (x - o.invested)
                val e = eq.vs(h, o.response.call)
                val s = spr(pot, x)
                val r = Realization.compute(h, o.response.call, o.heroInPosition, aggressor = true, spr = s)
                // Overcalls behind turn the pot multiway: expected realization loss.
                val rEff = r.value * (1 - o.overcallBehind * (1 - Realization.MULTIWAY_DECAY))
                val eqR = minOf(e * rEff, MAX_EQR.toDouble())
                val rk = rake(pot, x, eqR)
                val impl = implied.ev(h, o.response.call, pot, behind(x), o.heroInPosition)
                // Squeeze behind the flat: hero folds and loses the raise.
                val branch = (1 - o.squeezeBehind) * (eqR * pot - invest - rk + impl) - o.squeezeBehind * invest
                ev += reach * pc * branch
                callEvSum += reach * pc * branch
                pCall += reach * pc
                if (isMain) { e0 = e; r0 = r; rEff0 = rEff.toFloat(); pot0 = pot; rake0 = rk; impl0 = impl }
            }
            if (pr > 0) {
                val v = vsReraise(h, o, x, invest, potAfterRaise, n.potLevel + 1)
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
            playability = r0?.playability ?: 0.0, domination = r0?.domination ?: 0.0, realization = rEff0,
            realizationNoDomination = r0?.let { rEff0 * it.valueWithoutDomination / it.value } ?: 0f,
            potTotal = pot0, investment = invest, rake = rake0, implied = impl0,
            evVsReraise = if (pRe > 0) reEv / pRe else 0.0, calledEv = if (pCall > 0) callEvSum / pCall else 0.0, ev = ev,
        )
    }

    /** Equity vs the main opponent's continuing range: >= 0.5 => raise is for value. */
    fun valueEquity(h: Int, n: RaiseNode): Float {
        val o = n.mainOpponent ?: return 0f
        return eq.vs(h, o.response.continueRange)
    }

    /**
     * Hero's best continuation after villain re-raises to z, relative to hero's original fold:
     *   −Investment + max(0 [fold], call, re-raise)
     * The re-raise runs the master equation one level deeper: villain folds / calls / jams per
     * [Opponent.heroReRaise] with card removal; a jam is a stack-off decision for hero.
     */
    private fun vsReraise(h: Int, o: Opponent, x: Double, invest: Double, potAfterRaise: Double, potLevel: Int): Double {
        child(o)?.let { return -invest + it.continuationValue(h) }
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
            val r = Realization.compute(h, range, o.heroInPosition, aggressor = false, spr = s, potLevel = potLevel)
            val eqR = minOf(e * r.value, MAX_EQR).toDouble()
            eqR * pot - (z - x) - rake(pot, z, eqR) + implied.ev(h, range, pot, behind(z), o.heroInPosition)
        }
        var raiseEv = Double.NEGATIVE_INFINITY
        val alt = o.heroReRaise
        if (z < stack && alt != null) {
            val z2 = minOf(o.heroReRaiseTo, stack)
            val f = FoldModel.evaluate(h, pr, z2 - x, alt.fold, alt.call, alt.raise)
            if (z2 >= stack) {
                val cont = alt.continueRange
                val ej = eq.vs(h, cont).toDouble()
                val fin = pr + (stack - x) + (stack - z)
                raiseEv = f.pFold * pr + f.pContinue * (ej * fin - (stack - x) - rake(fin, stack, ej))
            } else {
                val pc = f.pContinue * (1 - f.reraiseShare)
                val pj = f.pContinue * f.reraiseShare
                val pot4 = pr + (z2 - x) + (z2 - z)
                val s4 = spr(pot4, z2)
                val ec = eq.vs(h, alt.call)
                val r4 = Realization.compute(h, alt.call, o.heroInPosition, aggressor = true, spr = s4, potLevel = potLevel + 1)
                val eqR4 = minOf(ec * r4.value, MAX_EQR).toDouble()
                val called = eqR4 * pot4 - (z2 - x) - rake(pot4, z2, eqR4) + implied.ev(h, alt.call, pot4, behind(z2), o.heroInPosition)
                val finJam = pr + (stack - x) + (stack - z)
                val ejam = eq.vs(h, alt.raise).toDouble()
                val vsJam = maxOf(-(z2 - x), ejam * finJam - (stack - x) - rake(finJam, stack, ejam))
                raiseEv = f.pFold * pr + pc * called + pj * vsJam
            }
        }
        return -invest + maxOf(0.0, callEv, raiseEv)
    }

    companion object {
        const val MAX_EQR = 0.98f
    }
}
