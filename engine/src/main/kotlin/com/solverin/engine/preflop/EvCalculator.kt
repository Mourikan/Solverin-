package com.solverin.engine.preflop

import com.solverin.engine.eval.CardRemoval
import com.solverin.engine.eval.RangeEquity
import com.solverin.engine.math.Realization

/**
 * Chip EV (bb) of each option relative to folding (EV_fold = 0; posted chips are sunk).
 *  - Equity vs ranges is card-removal exact; villain response probabilities are computed per hand
 *    from remaining combos (blockers shift fold equity directly).
 *  - Realized equity = eq * R(position, initiative, SPR, playability, players).
 *  - Rake: hero pays eqR * rake(final pot); unraked when everybody folds (no flop no drop).
 */
class EvCalculator(private val ctx: PreflopContext, private val eq: RangeEquity) {
    private val stack = ctx.effectiveStackBb

    private fun rake(pot: Double, spr: Double): Double =
        ctx.rake.rakeBb(if (spr < 0.05) pot else pot * POT_GROWTH)

    private fun spr(pot: Double, invested: Double) = if (pot <= 0) 0.0 else (stack - invested).coerceAtLeast(0.0) / pot

    fun call(h: Int, n: CallNode): Double {
        val to = minOf(n.callTo, stack)
        val cost = to - n.heroInvested
        val pot = n.potBefore + cost
        val players = n.villainRanges.size + 1
        val e = if (n.villainRanges.size == 1) eq.vs(h, n.villainRanges[0])
        else eq.multiway(eq.vs(h, n.villainRanges[0]), eq.vs(h, n.villainRanges[1]))
        val s = spr(pot, to)
        val r = Realization.factor(h, s, n.heroInPosition, initiative = false, players = players)
        val eqR = minOf(e * r, MAX_EQR).toDouble()
        val win = eqR * (pot - rake(pot, s)) - cost
        return (1 - n.squeezeRisk) * win - n.squeezeRisk * cost
    }

    /** Equity vs the first opponent's continuing range: >= 0.5 => raise is for value. */
    fun valueEquity(h: Int, n: RaiseNode): Float {
        val o = n.opponents.firstOrNull() ?: return 0f
        return eq.vs(h, o.response.continueRange)
    }

    fun raise(h: Int, n: RaiseNode): Double {
        val x = minOf(n.raiseTo, stack)
        val cost = x - n.heroInvested
        val potAfterRaise = n.potBefore + cost
        var reach = 1.0
        var ev = 0.0
        for (o in n.opponents) {
            val f = CardRemoval.remaining(h, o.response.fold)
            val c = CardRemoval.remaining(h, o.response.call)
            val r = CardRemoval.remaining(h, o.response.raise)
            val all = f + c + r
            if (all <= 0.0) continue
            if (c > 0) {
                val pot = potAfterRaise + (x - o.invested)
                val e = eq.vs(h, o.response.call)
                val s = spr(pot, x)
                val rr = Realization.factor(h, s, o.heroInPosition, initiative = true)
                val eqR = minOf(e * rr, MAX_EQR).toDouble()
                ev += reach * (c / all) * (eqR * (pot - rake(pot, s)) - cost)
            }
            if (r > 0) ev += reach * (r / all) * vsReraise(h, o, x, cost, potAfterRaise)
            reach *= f / all
        }
        ev += reach * n.potBefore
        return ev
    }

    private fun vsReraise(h: Int, o: Opponent, x: Double, cost: Double, potAfterRaise: Double): Double {
        val z = minOf(o.reraiseTo, stack)
        val pr = potAfterRaise + (z - o.invested)
        val range = o.response.raise
        val e = eq.vs(h, range)
        val callEv = if (z >= stack) {
            val fin = pr + (stack - x)
            e * (fin - rake(fin, 0.0)) - (stack - x)
        } else {
            val pot = pr + (z - x)
            val s = spr(pot, z)
            val rr = Realization.factor(h, s, o.heroInPosition, initiative = false)
            minOf(e * rr, MAX_EQR) * (pot - rake(pot, s)) - (z - x)
        }
        var jamEv = Double.NEGATIVE_INFINITY
        val cont = o.continuesVsJam
        if (z < stack && cont != null) {
            val remAll = CardRemoval.remaining(h, range)
            val remCont = CardRemoval.remaining(h, cont)
            val pf = if (remAll > 0) ((remAll - remCont) / remAll).coerceIn(0.0, 1.0) else 0.0
            val fin = pr + (stack - x) + (stack - z)
            jamEv = pf * pr + (1 - pf) * (eq.vs(h, cont) * (fin - rake(fin, 0.0)) - (stack - x))
        }
        return -cost + maxOf(0.0, callEv, jamEv)
    }

    companion object {
        /** Average final pot / flop pot for rake purposes. */
        const val POT_GROWTH = 1.8
        const val MAX_EQR = 0.98f
    }
}
