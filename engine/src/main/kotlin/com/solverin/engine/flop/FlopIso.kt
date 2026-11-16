package com.solverin.engine.flop

import com.solverin.engine.core.Card

/** Canonical-flop API (delegates to the bit-packed [FlopIsomorphismEngine]). */
object FlopIso {
    const val COUNT = FlopIsomorphismEngine.CANONICAL
    const val RAW_FLOPS = FlopIsomorphismEngine.RAW

    enum class Pattern {
        RAINBOW, MONOTONE, TWO_TONE_HIGH_MID, TWO_TONE_HIGH_LOW, TWO_TONE_MID_LOW,
        PAIRED_RAINBOW, PAIRED_TWO_TONE, TRIPS;

        companion object { private val V = entries.toTypedArray(); fun of(o: Int) = V[o] }
    }

    fun id(c1: Int, c2: Int, c3: Int): Int = FlopIsomorphismEngine.canonicalId(c1, c2, c3)
    fun id(board: IntArray): Int = FlopIsomorphismEngine.canonicalId(board)
    /** Ranks high → low. */
    fun ranks(id: Int): IntArray = FlopIsomorphismEngine.ranks(id)
    fun pattern(id: Int): Pattern = FlopIsomorphismEngine.pattern(id)
    /** Number of raw flops in the class (frequency weight). */
    fun weight(id: Int): Int = FlopIsomorphismEngine.weight(id)
    fun representative(id: Int): IntArray = FlopIsomorphismEngine.representative(id)

    fun name(id: Int): String {
        val s = ranks(id).joinToString("") { Card.RANK_CHARS[it].toString() }
        val p = when (pattern(id)) {
            Pattern.RAINBOW, Pattern.PAIRED_RAINBOW, Pattern.TRIPS -> "r"
            Pattern.MONOTONE -> "m"
            else -> "tt"
        }
        return "$s$p"
    }
}
