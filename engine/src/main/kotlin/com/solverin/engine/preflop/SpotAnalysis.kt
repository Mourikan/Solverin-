package com.solverin.engine.preflop

import com.solverin.engine.core.HandClass
import com.solverin.engine.range.ActionMix
import com.solverin.engine.range.SpotKey
import com.solverin.engine.range.SpotStrategy

/** Engine output for one spot. EVs in bb relative to fold. */
class SpotAnalysis(
    val key: SpotKey,
    val strategy: SpotStrategy,
    /** Master-equation terms of the passive option per hand; null if no call option. */
    val callTerms: Array<EvTerms>?,
    /** Master-equation terms of the aggressive option per hand; null if no raise option. */
    val raiseTerms: Array<EvTerms>?,
    /** Equity vs villain's continuing range when raising. */
    val valueEquity: FloatArray,
    /** Per-hand fold probability of the first opponent vs hero's raise (card removal applied). */
    val foldEquity: FloatArray,
    val blindDefense: BlindDefenseReport?,
    val coldCall: ColdCallAnalysis?,
    /** Projected flop SPR if hero flats (NaN if no call option). */
    val sprCall: Double,
    /** Projected flop SPR if hero raises and is called (NaN if no raise option). */
    val sprRaise: Double,
    /** Hands that bypassed the bluff cap (blocker / pure-exploit overrides); null = none. */
    val capExempt: BooleanArray? = null,
    /** Bluff:value ratio applied at this node (+∞ = uncapped). */
    val bluffRatio: Double = Double.POSITIVE_INFINITY,
    private val reachProvider: () -> FloatArray?,
) {
    /** Hero's range reaching this node (class weights); null = all hands. Resolved lazily (parent node). */
    val reach: FloatArray? by lazy { reachProvider() }

    /** EV of hero's mixed strategy at this node for hand [h], relative to folding here. */
    fun continuationValue(h: Int): Double {
        var v = 0.0
        val r = strategy.raise(h); val c = strategy.call(h)
        if (r > 0f) v += r * evRaise(h)
        if (c > 0f) v += c * evCall(h)
        return v
    }

    /** Frequencies conditional on hero's range at the node. */
    fun raiseFrequency(): Double = frequency(strategy, reach) { s, h -> s.raise(h) }
    fun callFrequency(): Double = frequency(strategy, reach) { s, h -> s.call(h) }
    fun foldFrequency(): Double = 1.0 - raiseFrequency() - callFrequency()
    /** Share of all 1326 combos that reach this node. */
    fun reachFrequency(): Double = reach?.let { r ->
        var s = 0.0
        for (h in 0 until HandClass.COUNT) s += r[h] * HandClass.combos(h)
        s / HandClass.TOTAL_COMBOS
    } ?: 1.0

    fun evCall(h: Int): Double = callTerms?.get(h)?.ev ?: Double.NaN
    fun evRaise(h: Int): Double = raiseTerms?.get(h)?.ev ?: Double.NaN

    fun decision(h: Int) = HandDecision(
        mix = strategy.mix(h),
        call = callTerms?.get(h),
        raise = raiseTerms?.get(h),
        valueEquity = valueEquity[h],
        foldEquity = foldEquity[h],
        isValue = strategy.isValue(h),
        coldCall = coldCall?.verdict?.get(h),
    )

    companion object {
        inline fun frequency(s: SpotStrategy, reach: FloatArray?, f: (SpotStrategy, Int) -> Float): Double {
            var num = 0.0; var den = 0.0
            for (h in 0 until HandClass.COUNT) {
                val w = (reach?.get(h) ?: 1f) * HandClass.combos(h)
                num += w * f(s, h); den += w
            }
            return if (den > 0) num / den else 0.0
        }
    }
}

data class HandDecision(
    val mix: ActionMix,
    val call: EvTerms?,
    val raise: EvTerms?,
    val valueEquity: Float,
    val foldEquity: Float,
    val isValue: Boolean,
    val coldCall: ColdCallVerdict?,
) {
    val evCall: Double get() = call?.ev ?: Double.NaN
    val evRaise: Double get() = raise?.ev ?: Double.NaN
}
