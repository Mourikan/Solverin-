package com.solverin.engine.preflop

import com.solverin.engine.eval.CardRemoval
import com.solverin.engine.eval.RangeEquity
import com.solverin.engine.math.ImpliedOdds
import com.solverin.engine.math.RakeModel
import com.solverin.engine.math.Realization

/**
 * Master preflop EV equation, evaluated per hand class (card-removal exact):
 *
 *  EV(raise) = P(fold)·(Pot + Dead)
 *            + P(call)·[EQ(h, callRange)·R(pos, SPR)·PotTotal − Investment − Rake(stake)] + EV_implied
 *            + P(reraise)·EV_vsReraise                       (fold / call / jam, same equation one level deeper)
 *
 *  EV(call)  = (1 − P(squeeze))·[EQ·R·PotTotal − Investment − Rake + EV_implied] − P(squeeze)·Investment
 *
 *  EV(fold)  = 0 (chips already posted are sunk).
 *  P(·) come from remaining villain combos after hero's cards (blockers). Multiway: first continuer heads-up.
 */
class PreflopMathEngine(
    private val ctx: PreflopContext,
    private val eq: RangeEquity,
    private val implied: ImpliedOdds,
) {
    private val stack = ctx.effectiveStackBb

    private fun behind(invested: Double) = (stack - invested).coerceAtLeast(0.0)
    private fun spr(pot: Double, invested: Double) = if (pot <= 0) 0.0 else behind(invested) / pot

    fun call(h: Int, n: CallNode): EvTerms {
        val to = minOf(n.callTo, stack)
        val invest = to - n.heroInvested
        val pot = n.potBefore + invest
        val players = n.villainRanges.size + 1
        val e = if (n.villainRanges.size == 1) eq.vs(h, n.villainRanges[0])
        else eq.multiway(eq.vs(h, n.villainRanges[0]), eq.vs(h, n.villainRanges[1]))
        val s = spr(pot, to)
        val r = Realization.factor(n.heroInPosition, initiative = false, spr = s, players = players)
        val eqR = minOf(e * r, MAX_EQR).toDouble()
        val rake = RakeModel.heroRake(ctx.rake, pot, behind(to), eqR)
        var impl = implied.ev(h, n.villainRanges[0], pot, behind(to), n.heroInPosition)
        if (players > 2) impl *= Realization.MULTIWAY_DECAY
        val branch = eqR * pot - invest - rake + impl
        val pSq = n.squeezeRisk
        return EvTerms(
            pFold = 0.0, pCall = 1 - pSq, pReraise = pSq, foldWin = 0.0,
            equity = e, realization = r, potTotal = pot, investment = invest, rake = rake, implied = impl,
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
        var e0 = 0f; var r0 = 0f; var pot0 = 0.0; var rake0 = 0.0; var impl0 = 0.0
        var first = true
        for (o in n.opponents) {
            val f = CardRemoval.remaining(h, o.response.fold)
            val c = CardRemoval.remaining(h, o.response.call)
            val rr = CardRemoval.remaining(h, o.response.raise)
            val all = f + c + rr
            if (all <= 0.0) continue
            if (c > 0) {
                val pot = potAfterRaise + (x - o.invested)
                val e = eq.vs(h, o.response.call)
                val s = spr(pot, x)
                val r = Realization.factor(o.heroInPosition, initiative = true, spr = s)
                val eqR = minOf(e * r, MAX_EQR).toDouble()
                val rake = RakeModel.heroRake(ctx.rake, pot, behind(x), eqR)
                val impl = implied.ev(h, o.response.call, pot, behind(x), o.heroInPosition)
                val p = reach * (c / all)
                ev += p * (eqR * pot - invest - rake + impl)
                pCall += p
                if (first) { e0 = e; r0 = r; pot0 = pot; rake0 = rake; impl0 = impl }
            }
            if (rr > 0) {
                val p = reach * (rr / all)
                val v = vsReraise(h, o, x, invest, potAfterRaise)
                ev += p * v
                pRe += p; reEv += p * v
            }
            first = false
            reach *= f / all
        }
        ev += reach * n.potBefore
        return EvTerms(
            pFold = reach, pCall = pCall, pReraise = pRe, foldWin = n.potBefore,
            equity = e0, realization = r0, potTotal = pot0, investment = invest, rake = rake0, implied = impl0,
            evVsReraise = if (pRe > 0) reEv / pRe else 0.0, ev = ev,
        )
    }

    /** Equity vs the first opponent's continuing range: >= 0.5 => raise is for value. */
    fun valueEquity(h: Int, n: RaiseNode): Float {
        val o = n.opponents.firstOrNull() ?: return 0f
        return eq.vs(h, o.response.continueRange)
    }

    private fun vsReraise(h: Int, o: Opponent, x: Double, invest: Double, potAfterRaise: Double): Double {
        val z = minOf(o.reraiseTo, stack)
        val pr = potAfterRaise + (z - o.invested)
        val range = o.response.raise
        val e = eq.vs(h, range)
        val callEv = if (z >= stack) {
            val fin = pr + (stack - x)
            e * fin - (stack - x) - RakeModel.heroRake(ctx.rake, fin, 0.0, e.toDouble())
        } else {
            val pot = pr + (z - x)
            val s = spr(pot, z)
            val r = Realization.factor(o.heroInPosition, initiative = false, spr = s)
            val eqR = minOf(e * r, MAX_EQR).toDouble()
            eqR * pot - (z - x) - RakeModel.heroRake(ctx.rake, pot, behind(z), eqR) +
                implied.ev(h, range, pot, behind(z), o.heroInPosition)
        }
        var jamEv = Double.NEGATIVE_INFINITY
        val cont = o.continuesVsJam
        if (z < stack && cont != null) {
            val remAll = CardRemoval.remaining(h, range)
            val remCont = CardRemoval.remaining(h, cont)
            val pf = if (remAll > 0) ((remAll - remCont) / remAll).coerceIn(0.0, 1.0) else 0.0
            val fin = pr + (stack - x) + (stack - z)
            val ej = eq.vs(h, cont).toDouble()
            jamEv = pf * pr + (1 - pf) * (ej * fin - (stack - x) - RakeModel.heroRake(ctx.rake, fin, 0.0, ej))
        }
        return -invest + maxOf(0.0, callEv, jamEv)
    }

    companion object {
        const val MAX_EQR = 0.98f
    }
}
