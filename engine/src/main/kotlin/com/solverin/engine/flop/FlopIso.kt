package com.solverin.engine.flop

import com.solverin.engine.core.Card

/**
 * Suit isomorphism: the 22,100 raw flops collapse to 1,755 strategically distinct flops.
 * Canonical key = ranks sorted high→low + suit pattern. Lookup table: 17,576 shorts (35 KB); per-flop data
 * in byte/short arrays (~12 KB).
 */
object FlopIso {
    const val COUNT = 1755
    const val RAW_FLOPS = 22100

    enum class Pattern {
        RAINBOW, MONOTONE, TWO_TONE_HIGH_MID, TWO_TONE_HIGH_LOW, TWO_TONE_MID_LOW,
        PAIRED_RAINBOW, PAIRED_TWO_TONE, TRIPS;

        companion object { private val V = entries.toTypedArray(); fun of(o: Int) = V[o] }
    }

    private val LOOKUP = ShortArray(13 * 13 * 13 * 8) { -1 }
    private val RANKS = ByteArray(COUNT * 3)
    private val PATTERN = ByteArray(COUNT)
    private val WEIGHT = ShortArray(COUNT)
    private val REP = IntArray(COUNT)

    init {
        var next = 0
        for (a in 0 until 52) for (b in a + 1 until 52) for (c in b + 1 until 52) {
            val key = key(a, b, c)
            var id = LOOKUP[key].toInt()
            if (id < 0) {
                id = next++
                LOOKUP[key] = id.toShort()
                val p = key and 7
                val rest = key ushr 3
                RANKS[id * 3] = (rest / 169).toByte()
                RANKS[id * 3 + 1] = ((rest / 13) % 13).toByte()
                RANKS[id * 3 + 2] = (rest % 13).toByte()
                PATTERN[id] = p.toByte()
                REP[id] = a or (b shl 6) or (c shl 12)
            }
            WEIGHT[id] = (WEIGHT[id] + 1).toShort()
        }
        check(next == COUNT) { "Expected $COUNT canonical flops, got $next" }
    }

    private fun key(c1: Int, c2: Int, c3: Int): Int {
        var a = c1; var b = c2; var c = c3
        if (Card.rank(a) < Card.rank(b)) { val t = a; a = b; b = t }
        if (Card.rank(b) < Card.rank(c)) { val t = b; b = c; c = t }
        if (Card.rank(a) < Card.rank(b)) { val t = a; a = b; b = t }
        val r1 = Card.rank(a); val r2 = Card.rank(b); val r3 = Card.rank(c)
        val s1 = Card.suit(a); val s2 = Card.suit(b); val s3 = Card.suit(c)
        val p = when {
            r1 == r2 && r2 == r3 -> Pattern.TRIPS
            r1 == r2 -> if (s3 == s1 || s3 == s2) Pattern.PAIRED_TWO_TONE else Pattern.PAIRED_RAINBOW
            r2 == r3 -> if (s1 == s2 || s1 == s3) Pattern.PAIRED_TWO_TONE else Pattern.PAIRED_RAINBOW
            s1 == s2 && s2 == s3 -> Pattern.MONOTONE
            s1 != s2 && s1 != s3 && s2 != s3 -> Pattern.RAINBOW
            s1 == s2 -> Pattern.TWO_TONE_HIGH_MID
            s1 == s3 -> Pattern.TWO_TONE_HIGH_LOW
            else -> Pattern.TWO_TONE_MID_LOW
        }
        return (((r1 * 13 + r2) * 13 + r3) shl 3) or p.ordinal
    }

    fun id(c1: Int, c2: Int, c3: Int): Int {
        require(c1 != c2 && c1 != c3 && c2 != c3) { "Duplicate flop card" }
        return LOOKUP[key(c1, c2, c3)].toInt()
    }

    fun id(board: IntArray): Int {
        require(board.size == 3) { "Flop needs 3 cards" }
        return id(board[0], board[1], board[2])
    }

    /** Ranks high → low. */
    fun ranks(id: Int): IntArray = intArrayOf(RANKS[id * 3].toInt(), RANKS[id * 3 + 1].toInt(), RANKS[id * 3 + 2].toInt())
    fun pattern(id: Int): Pattern = Pattern.of(PATTERN[id].toInt())
    /** Number of raw flops in the class (frequency weight). */
    fun weight(id: Int): Int = WEIGHT[id].toInt()
    fun representative(id: Int): IntArray = REP[id].let { intArrayOf(it and 63, (it ushr 6) and 63, (it ushr 12) and 63) }

    fun name(id: Int): String {
        val r = ranks(id)
        val s = r.joinToString("") { Card.RANK_CHARS[it].toString() }
        val p = when (pattern(id)) {
            Pattern.RAINBOW, Pattern.PAIRED_RAINBOW, Pattern.TRIPS -> "r"
            Pattern.MONOTONE -> "m"
            else -> "tt"
        }
        return "$s$p"
    }
}
