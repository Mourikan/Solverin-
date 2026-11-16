package com.solverin.engine.preflop

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
) {
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
