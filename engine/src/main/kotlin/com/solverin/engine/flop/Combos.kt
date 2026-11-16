package com.solverin.engine.flop

import com.solverin.engine.core.Card
import com.solverin.engine.core.HandClass

/**
 * 1326 concrete hole-card combos. index k: C1[k] < C2[k] (card ids), so C2 holds the higher rank.
 * Postflop ranges are FloatArray(1326) of combo weights in [0, 1].
 */
object Combos {
    const val COUNT = 1326
    val C1 = IntArray(COUNT)
    val C2 = IntArray(COUNT)
    val MASK = LongArray(COUNT)
    val HAND_CLASS = IntArray(COUNT)
    /** Combos containing each card (51 per card). */
    val BY_CARD: Array<IntArray>
    private val INDEX = IntArray(52 * 52) { -1 }

    init {
        var k = 0
        for (a in 0 until 52) for (b in a + 1 until 52) {
            C1[k] = a; C2[k] = b
            MASK[k] = (1L shl a) or (1L shl b)
            HAND_CLASS[k] = HandClass.fromCards(a, b)
            INDEX[a * 52 + b] = k; INDEX[b * 52 + a] = k
            k++
        }
        val lists = Array(52) { IntArray(51) }
        val fill = IntArray(52)
        for (i in 0 until COUNT) {
            lists[C1[i]][fill[C1[i]]++] = i
            lists[C2[i]][fill[C2[i]]++] = i
        }
        BY_CARD = lists
    }

    fun index(a: Int, b: Int): Int {
        require(a != b && a in 0..51 && b in 0..51) { "Invalid combo $a $b" }
        return INDEX[a * 52 + b]
    }

    /** "AhKh" -> combo index. */
    fun parse(s: String): Int {
        val t = s.trim()
        require(t.length == 4) { "Invalid combo '$s'" }
        return index(Card.parse(t.substring(0, 2)), Card.parse(t.substring(2, 4)))
    }

    fun name(k: Int): String = Card.toString(C2[k]) + Card.toString(C1[k])

    fun mask(cards: IntArray): Long {
        var m = 0L
        for (c in cards) m = m or (1L shl c)
        return m
    }

    fun isLive(k: Int, dead: Long): Boolean = MASK[k] and dead == 0L

    /** Class weights (169) -> combo weights (1326), dead combos removed. */
    fun fromClassWeights(classWeights: FloatArray, dead: Long = 0L): FloatArray {
        require(classWeights.size == HandClass.COUNT)
        return FloatArray(COUNT) { if (isLive(it, dead)) classWeights[HAND_CLASS[it]] else 0f }
    }

    fun total(w: FloatArray): Double {
        var s = 0.0
        for (x in w) s += x
        return s
    }

    /** Weight of [w] not sharing a card with combo [h] (card removal), given [total] = total(w). */
    fun remaining(h: Int, w: FloatArray, total: Double): Double {
        var r = total
        for (v in BY_CARD[C1[h]]) r -= w[v]
        for (v in BY_CARD[C2[h]]) r -= w[v]
        return r + w[h]
    }

    /** Combo-weighted per-class aggregation of [values] (weights [weights]); NaN where no weight. */
    fun toClass(values: FloatArray, weights: FloatArray): FloatArray {
        val num = DoubleArray(HandClass.COUNT)
        val den = DoubleArray(HandClass.COUNT)
        for (k in 0 until COUNT) {
            val w = weights[k]
            if (w <= 0f || values[k].isNaN()) continue
            num[HAND_CLASS[k]] += w * values[k]
            den[HAND_CLASS[k]] += w.toDouble()
        }
        return FloatArray(HandClass.COUNT) { if (den[it] > 0) (num[it] / den[it]).toFloat() else Float.NaN }
    }
}
