package com.solverin.engine

import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopAction
import com.solverin.engine.flop.FlopAnalysis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Invariants of every flop solve. */
object FlopInvariants {
    private const val BAND = 0.05

    fun all(a: FlopAnalysis) {
        for (k in 0 until Combos.COUNT) {
            if (a.heroRange[k] <= 0f) continue
            val name = Combos.name(k)
            var sum = 0f
            for (act in a.actions) {
                val f = a.frequency(act, k)
                assertTrue(f >= 0f && f <= 1.0001f, "$name $act frequency $f")
                sum += f
            }
            assertEquals(1f, sum, 1e-4f, "$name frequencies")
            for (act in a.actions) {
                val t = a.terms(act, k) ?: continue
                assertEquals(1.0, t.pFold + t.pCall + t.pRaise, 1e-9, "$name $act probabilities")
                assertTrue(t.pFold >= -1e-12 && t.pCall >= -1e-12 && t.pRaise >= -1e-12)
                assertEquals(t.pFold * t.foldWin + t.pCall * t.calledEv + t.pRaise * t.evVsRaise, t.ev, 1e-9, "$name $act EV identity")
                assertTrue(t.ev.isFinite() && t.equity in 0f..1f && t.realization in 0f..1.25f, "$name $act terms")
                // A re-raised hand can always fold: its continuation never loses more than its own bet.
                if (t.pRaise > 0 && (act == FlopAction.BET || act == FlopAction.RAISE || act == FlopAction.DONK))
                    assertTrue(t.evVsRaise >= -t.investment - 1e-9, "$name $act vs re-raise ${t.evVsRaise} < -${t.investment}")
                // No action invests more than the effective stack.
                assertTrue(t.investment <= a.spot.effectiveStackBb + 1e-9, "$name $act invests ${t.investment} > stack")
                // Commitment: an all-in branch realizes raw equity.
                if (t.allIn) assertEquals(1f, t.realization, 1e-6f, "$name $act all-in realization")
            }
            maxEv(a, k, name)
            a.betSizeBb?.let { assertTrue(it <= a.spot.effectiveStackBb + 1e-9 && it > 0.0, "bet size $it") }
            if (a.minBet) {
                val call = a.terms(FlopAction.CALL, k)!!
                if (call.equity >= a.potOdds!!) assertEquals(0f, a.frequency(FlopAction.FOLD, k), "$name folds a min-bet with pot odds")
            }
        }
    }

    /** Actions played are within the mixing band of the best one, except bluffs cut by the range-consistency cap. */
    private fun maxEv(a: FlopAnalysis, k: Int, name: String) {
        val evs = a.actions.associateWith { a.ev(it, k) }.filterValues { !it.isNaN() }
        val bestAct = evs.maxByOrNull { it.value }?.key ?: return
        val best = evs.getValue(bestAct)
        val aggressiveBest = bestAct == FlopAction.BET || bestAct == FlopAction.RAISE || bestAct == FlopAction.DONK
        val capped = aggressiveBest && a.capped(k)
        for ((act, ev) in evs) if (a.frequency(act, k) > 0f)
            assertTrue(ev >= best - BAND - 1e-9 || capped || (a.minBet && act != FlopAction.FOLD), "$name plays $act below max EV")
    }

    /** Hero-range share of combos whose fold frequency is 1. */
    fun foldShare(a: FlopAnalysis): Double = a.frequency(FlopAction.FOLD)
}
