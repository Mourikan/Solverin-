package com.solverin.engine.flop

import com.solverin.engine.core.Card
import com.solverin.engine.eval.HandEvaluator

/**
 * Suit / texture isomorphism of the 22,100 raw flops into 1,755 canonical flops.
 *
 *  - Raw flop index: combinatorial number system C(a,1)+C(b,2)+C(c,3) for card ids a < b < c.
 *  - RAW_TO_CANON: 22,100 × 11 bits (≈ 30 KB) → O(1) canonical id.
 *  - CLASS_INFO: 1,755 × 32-bit words packing ranks, suit pattern, suit texture, pairing, connectivity,
 *    height, straight-making rank pairs and class weight (≈ 7 KB).
 * Lookups are a sort of 3 ints + 2 array reads (sub-microsecond).
 */
object FlopIsomorphismEngine {
    const val CANONICAL = 1755
    const val RAW = 22100

    private val BINOM2 = IntArray(52) { it * (it - 1) / 2 }
    private val BINOM3 = IntArray(52) { it * (it - 1) * (it - 2) / 6 }

    private val RAW_TO_CANON = BitPackedArray(RAW, 11)
    private val CLASS_INFO = IntArray(CANONICAL)
    private val REPRESENTATIVE = IntArray(CANONICAL)

    // CLASS_INFO layout (LSB first): r1 4 | r2 4 | r3 4 | pattern 3 | suits 2 | pairing 2 | connectivity 2 |
    //                                height 2 | straightCombos 4 | weight 5   (= 32 bits)
    private const val SH_R1 = 0; private const val SH_R2 = 4; private const val SH_R3 = 8
    private const val SH_PATTERN = 12; private const val SH_SUITS = 15; private const val SH_PAIRING = 17
    private const val SH_CONN = 19; private const val SH_HEIGHT = 21; private const val SH_STRAIGHTS = 23
    private const val SH_WEIGHT = 27

    init {
        val keyToId = ShortArray(13 * 13 * 13 * 8) { -1 }
        var next = 0
        for (a in 0 until 52) for (b in a + 1 until 52) for (c in b + 1 until 52) {
            val key = canonicalKey(a, b, c)
            var id = keyToId[key].toInt()
            if (id < 0) {
                id = next++
                keyToId[key] = id.toShort()
                CLASS_INFO[id] = packInfo(key)
                REPRESENTATIVE[id] = a or (b shl 6) or (c shl 12)
            }
            RAW_TO_CANON[rawIndex(a, b, c)] = id
            val w = (CLASS_INFO[id] ushr SH_WEIGHT) + 1
            CLASS_INFO[id] = (CLASS_INFO[id] and ((1 shl SH_WEIGHT) - 1)) or (w shl SH_WEIGHT)
        }
        check(next == CANONICAL) { "Expected $CANONICAL canonical flops, got $next" }
    }

    /** Raw index of sorted card ids a < b < c. */
    fun rawIndex(a: Int, b: Int, c: Int): Int = a + BINOM2[b] + BINOM3[c]

    fun canonicalId(c1: Int, c2: Int, c3: Int): Int {
        require(c1 != c2 && c1 != c3 && c2 != c3) { "Duplicate flop card" }
        var a = c1; var b = c2; var c = c3
        if (a > b) { val t = a; a = b; b = t }
        if (b > c) { val t = b; b = c; c = t }
        if (a > b) { val t = a; a = b; b = t }
        return RAW_TO_CANON[rawIndex(a, b, c)]
    }

    fun canonicalId(board: IntArray): Int {
        require(board.size == 3) { "Flop needs 3 cards" }
        return canonicalId(board[0], board[1], board[2])
    }

    private fun field(id: Int, shift: Int, bits: Int) = (CLASS_INFO[id] ushr shift) and ((1 shl bits) - 1)

    fun ranks(id: Int): IntArray = intArrayOf(field(id, SH_R1, 4), field(id, SH_R2, 4), field(id, SH_R3, 4))
    fun pattern(id: Int): FlopIso.Pattern = FlopIso.Pattern.of(field(id, SH_PATTERN, 3))
    fun suits(id: Int): SuitTexture = SuitTexture.entries[field(id, SH_SUITS, 2)]
    fun pairing(id: Int): Pairing = Pairing.entries[field(id, SH_PAIRING, 2)]
    fun connectivity(id: Int): Connectivity = Connectivity.entries[field(id, SH_CONN, 2)]
    fun height(id: Int): Height = Height.entries[field(id, SH_HEIGHT, 2)]
    fun straightCombos(id: Int): Int = field(id, SH_STRAIGHTS, 4)
    fun weight(id: Int): Int = CLASS_INFO[id] ushr SH_WEIGHT
    fun representative(id: Int): IntArray = REPRESENTATIVE[id].let { intArrayOf(it and 63, (it ushr 6) and 63, (it ushr 12) and 63) }

    /** Bytes of the packed lookup structures. */
    val memoryBytes: Int get() = RAW_TO_CANON.bytes + CLASS_INFO.size * 4 + REPRESENTATIVE.size * 4

    private fun canonicalKey(c1: Int, c2: Int, c3: Int): Int {
        var a = c1; var b = c2; var c = c3
        if (Card.rank(a) < Card.rank(b)) { val t = a; a = b; b = t }
        if (Card.rank(b) < Card.rank(c)) { val t = b; b = c; c = t }
        if (Card.rank(a) < Card.rank(b)) { val t = a; a = b; b = t }
        val r1 = Card.rank(a); val r2 = Card.rank(b); val r3 = Card.rank(c)
        val s1 = Card.suit(a); val s2 = Card.suit(b); val s3 = Card.suit(c)
        val p = when {
            r1 == r2 && r2 == r3 -> FlopIso.Pattern.TRIPS
            r1 == r2 -> if (s3 == s1 || s3 == s2) FlopIso.Pattern.PAIRED_TWO_TONE else FlopIso.Pattern.PAIRED_RAINBOW
            r2 == r3 -> if (s1 == s2 || s1 == s3) FlopIso.Pattern.PAIRED_TWO_TONE else FlopIso.Pattern.PAIRED_RAINBOW
            s1 == s2 && s2 == s3 -> FlopIso.Pattern.MONOTONE
            s1 != s2 && s1 != s3 && s2 != s3 -> FlopIso.Pattern.RAINBOW
            s1 == s2 -> FlopIso.Pattern.TWO_TONE_HIGH_MID
            s1 == s3 -> FlopIso.Pattern.TWO_TONE_HIGH_LOW
            else -> FlopIso.Pattern.TWO_TONE_MID_LOW
        }
        return (((r1 * 13 + r2) * 13 + r3) shl 3) or p.ordinal
    }

    private fun packInfo(key: Int): Int {
        val p = FlopIso.Pattern.of(key and 7)
        val rest = key ushr 3
        val r1 = rest / 169; val r2 = (rest / 13) % 13; val r3 = rest % 13
        val suits = when (p) {
            FlopIso.Pattern.MONOTONE -> SuitTexture.MONOTONE
            FlopIso.Pattern.RAINBOW, FlopIso.Pattern.PAIRED_RAINBOW, FlopIso.Pattern.TRIPS -> SuitTexture.RAINBOW
            else -> SuitTexture.TWO_TONE
        }
        val pairing = when {
            r1 == r2 && r2 == r3 -> Pairing.TRIPS
            r1 == r2 || r2 == r3 -> Pairing.PAIRED
            else -> Pairing.UNPAIRED
        }
        val board = (1 shl r1) or (1 shl r2) or (1 shl r3)
        var straights = 0
        for (x in 0 until 13) for (y in x + 1 until 13) {
            if (HandEvaluator.straightHigh(board or (1 shl x) or (1 shl y)) >= 0) straights++
        }
        val conn = when { straights >= 3 -> Connectivity.HIGH; straights >= 1 -> Connectivity.MEDIUM; else -> Connectivity.LOW }
        val height = when { r1 >= 10 -> Height.HIGH; r1 >= 6 -> Height.MIDDLE; else -> Height.LOW }
        return (r1 shl SH_R1) or (r2 shl SH_R2) or (r3 shl SH_R3) or (p.ordinal shl SH_PATTERN) or
            (suits.ordinal shl SH_SUITS) or (pairing.ordinal shl SH_PAIRING) or (conn.ordinal shl SH_CONN) or
            (height.ordinal shl SH_HEIGHT) or (minOf(straights, 15) shl SH_STRAIGHTS)
    }
}
