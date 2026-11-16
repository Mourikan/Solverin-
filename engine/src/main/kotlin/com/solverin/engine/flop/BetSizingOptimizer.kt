package com.solverin.engine.flop

import kotlin.math.ln
import kotlin.math.pow

/**
 * Bet-size candidates for a flop node:
 *  - geometric size: the constant pot fraction that gets stacks in by the river,
 *    f = ((1 + 2·SPR)^(1/streets) − 1) / 2;
 *  - a small range-bet size when hero holds the range advantage;
 *  - an overbet when hero holds a clear nut advantage (ΔNuts ≥ overbetNutEdge);
 *  - villain elasticity e = d ln(continue) / d ln(size) (≤ 0): inelastic villains (|e| small, e.g. node-locked
 *    fold frequency) add a larger value size, very elastic villains add a smaller one.
 * The engine then picks the candidate with the highest range EV.
 */
object BetSizingOptimizer {
    fun geometric(spr: Double, streets: Int): Double {
        if (spr <= 0.0 || streets <= 0) return 0.0
        return ((1 + 2 * spr).pow(1.0 / streets) - 1) / 2
    }

    /** Elasticity of villain's continue frequency between two sizes (fractions of pot). */
    fun elasticity(continueAt: (Double) -> Double, small: Double = 0.33, large: Double = 1.0): Double {
        val cs = continueAt(small); val cl = continueAt(large)
        if (cs <= 0.0 || cl <= 0.0) return 0.0
        return ln(cl / cs) / ln(large / small)
    }

    fun candidates(spr: Double, rangeAdvantage: Double, nutAdvantage: Double, elasticity: Double, policy: FlopPolicy): List<Double> {
        val maxFraction = if (spr > 0) spr else 0.0
        val out = sortedSetOf<Double>()
        val geo = geometric(spr, policy.streetsToRiver).coerceIn(policy.smallBet, policy.overbet)
        out += policy.smallBet
        out += geo
        if (rangeAdvantage <= 0.0) out += policy.mediumBet
        if (nutAdvantage >= policy.overbetNutEdge) out += policy.overbet
        if (elasticity > policy.inelasticThreshold) out += minOf(policy.overbet, maxOf(geo, policy.mediumBet) * 1.25)
        if (elasticity < policy.elasticThreshold) out += maxOf(0.2, policy.smallBet * 0.75)
        return out.map { minOf(it, maxFraction) }.filter { it > 0.0 }.distinct().map { Math.round(it * 1000) / 1000.0 }.distinct()
    }
}
