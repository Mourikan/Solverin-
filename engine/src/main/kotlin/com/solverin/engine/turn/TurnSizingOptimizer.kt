package com.solverin.engine.turn

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Turn sizes: 33% (block / thin value), standard ~70%, the exact 2-street geometric size and overbets
 * (125% / 150%) only when ΔNuts_turn > overbetNutEdge and villain's range is capped.
 * Geometric (turn + river, equal pot fractions ending all-in): f = (√(1 + 2·SPR) − 1) / 2,
 * i.e. Sizing = Pot · (√(1 + 2·Stack/Pot) − 1) / 2.
 */
object TurnSizingOptimizer {
    fun geometricFraction(spr: Double): Double = if (spr <= 0.0) 0.0 else (sqrt(1 + 2 * spr) - 1) / 2

    fun geometricBet(pot: Double, stack: Double): Double = pot * geometricFraction(stack / pot)

    fun elasticity(continueAt: (Double) -> Double, small: Double = 0.33, large: Double = 1.0): Double {
        val cs = continueAt(small); val cl = continueAt(large)
        if (cs <= 0.0 || cl <= 0.0) return 0.0
        return ln(cl / cs) / ln(large / small)
    }

    fun candidates(spr: Double, nutAdvantage: Double, villainCapped: Boolean, policy: TurnPolicy): List<Double> {
        if (spr <= 0.0) return emptyList()
        val out = sortedSetOf(policy.smallBet, policy.standardBet, geometricFraction(spr))
        if (nutAdvantage > policy.overbetNutEdge && villainCapped) { out += policy.overbetLow; out += policy.overbetHigh }
        return out.map { minOf(it, spr) }.filter { it > 0.0 }.map { Math.round(it * 1000) / 1000.0 }.distinct()
    }
}
