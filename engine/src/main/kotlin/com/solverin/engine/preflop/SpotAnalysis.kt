package com.solverin.engine.preflop

import com.solverin.engine.range.ActionMix
import com.solverin.engine.range.SpotKey
import com.solverin.engine.range.SpotStrategy

/** Engine output for one spot. EVs in bb relative to fold; NaN = option unavailable. */
class SpotAnalysis(
    val key: SpotKey,
    val strategy: SpotStrategy,
    val evCall: FloatArray,
    val evRaise: FloatArray,
    /** Equity vs villain's continuing range when raising. */
    val valueEquity: FloatArray,
    /** Per-hand fold probability of the first opponent vs hero's raise (card removal applied). */
    val foldEquity: FloatArray,
    val blindDefense: BlindDefenseReport?,
) {
    fun decision(h: Int) = HandDecision(strategy.mix(h), evCall[h], evRaise[h], valueEquity[h], foldEquity[h], strategy.isValue(h))
}

data class HandDecision(
    val mix: ActionMix,
    val evCall: Float,
    val evRaise: Float,
    val valueEquity: Float,
    val foldEquity: Float,
    val isValue: Boolean,
)
