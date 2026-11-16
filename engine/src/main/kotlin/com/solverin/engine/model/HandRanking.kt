package com.solverin.engine.model

import com.solverin.engine.core.HandClass
import com.solverin.engine.eval.EquityTable
import com.solverin.engine.math.Playability

/** Orderings used to synthesize villain ranges from frequencies. */
class HandRanking(table: EquityTable) {
    /** Value ordering: 80% equity vs random, 20% deep-stack playability. */
    val strength: FloatArray
    val byStrength: IntArray
    /** Bluff ordering: ace/king blockers, suitedness, playability. */
    val bluff: FloatArray
    val byBluff: IntArray

    init {
        val vr = table.vsRandom
        val lo = vr.min(); val hi = vr.max()
        strength = FloatArray(HandClass.COUNT) { 0.8f * (vr[it] - lo) / (hi - lo) + 0.2f * Playability.score(it, 12.0) }
        bluff = FloatArray(HandClass.COUNT) {
            val top = HandClass.highRank(it)
            val blk = when (top) { 12 -> 1f; 11 -> 0.6f; else -> 0f }
            0.45f * blk + 0.3f * (if (HandClass.isSuited(it)) 1f else 0f) + 0.25f * Playability.score(it, 12.0)
        }
        byStrength = (0 until HandClass.COUNT).sortedByDescending { strength[it] }.toIntArray()
        byBluff = (0 until HandClass.COUNT).sortedByDescending { bluff[it] }.toIntArray()
    }
}
