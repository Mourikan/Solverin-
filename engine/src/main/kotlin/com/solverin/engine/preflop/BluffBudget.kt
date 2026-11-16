package com.solverin.engine.preflop

import com.solverin.engine.core.HandClass
import com.solverin.engine.model.VillainProfile
import com.solverin.engine.range.Scenario
import kotlin.math.pow

/**
 * Caps bluff raises (equity < 50% vs villain's continue range) at valueCombos x ratio.
 * Profile fold rates are measured vs normal-width ranges; this keeps the raising range
 * polarized at a defensible value:bluff ratio, scaled up vs over-folders and down vs stations.
 * Bluffs compete for the budget by EV surplus over their best alternative (blockers included via FE).
 */
object BluffBudget {

    private fun base(s: Scenario): Double = when (s) {
        Scenario.RFI -> Double.POSITIVE_INFINITY
        Scenario.VS_RFI -> 1.0
        Scenario.SQUEEZE -> 0.8
        Scenario.VS_3BET -> 0.7
        Scenario.VS_4BET -> 0.25
    }

    private fun foldPair(s: Scenario, p: VillainProfile): Pair<Double, Double> = when (s) {
        Scenario.RFI -> 1.0 to 1.0
        Scenario.VS_RFI -> p.foldTo3Bet to 0.55
        Scenario.SQUEEZE -> (p.foldTo3Bet + 0.05) to 0.62
        Scenario.VS_3BET -> p.foldTo4Bet to 0.50
        Scenario.VS_4BET -> p.foldTo5Bet to 0.35
    }

    fun ratio(s: Scenario, p: VillainProfile): Double {
        val b = base(s)
        if (b.isInfinite()) return b
        val (f, ref) = foldPair(s, p)
        return b * (f / ref).pow(2).coerceIn(0.3, 3.0)
    }

    /**
     * Mutates raise/call in place. [surplus] = adjusted raise EV minus best alternative;
     * [fallbackCall] = call share the hand takes if its raise is removed.
     */
    fun apply(
        ratio: Double, raise: FloatArray, call: FloatArray, valueEq: FloatArray,
        surplus: DoubleArray, fallbackCall: FloatArray,
    ) {
        if (ratio.isInfinite()) return
        var value = 0.0
        val bluffs = ArrayList<Int>()
        for (h in 0 until HandClass.COUNT) {
            if (raise[h] <= 0f) continue
            if (valueEq[h] >= 0.5f) value += raise[h] * HandClass.combos(h) else bluffs += h
        }
        var budget = value * ratio
        bluffs.sortByDescending { surplus[it] }
        for (h in bluffs) {
            val combos = raise[h] * HandClass.combos(h)
            val keep = when {
                budget >= combos -> raise[h]
                budget > 0 -> (budget / HandClass.combos(h)).toFloat()
                else -> 0f
            }
            budget -= keep * HandClass.combos(h)
            val removed = raise[h] - keep
            if (removed > 0f) {
                raise[h] = keep
                call[h] = (call[h] + removed * fallbackCall[h]).coerceAtMost(1f - keep)
            }
        }
    }
}
