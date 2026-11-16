package com.solverin.engine.eval

/**
 * 5..7 card evaluator. Result: category << 20 | five ranks (4 bits each). Higher wins.
 * Categories: 8 SF, 7 quads, 6 FH, 5 flush, 4 straight, 3 trips, 2 two pair, 1 pair, 0 high.
 * Allocation-free; one instance per thread (scratch buffer).
 */
class HandEvaluator {
    private val count = IntArray(13)

    fun evaluate(cards: IntArray, n: Int): Int {
        var s0 = 0; var s1 = 0; var s2 = 0; var s3 = 0
        count.fill(0)
        for (i in 0 until n) {
            val c = cards[i]
            val r = c ushr 2
            count[r]++
            when (c and 3) {
                0 -> s0 = s0 or (1 shl r)
                1 -> s1 = s1 or (1 shl r)
                2 -> s2 = s2 or (1 shl r)
                else -> s3 = s3 or (1 shl r)
            }
        }
        val all = s0 or s1 or s2 or s3
        val fm = when {
            Integer.bitCount(s0) >= 5 -> s0
            Integer.bitCount(s1) >= 5 -> s1
            Integer.bitCount(s2) >= 5 -> s2
            Integer.bitCount(s3) >= 5 -> s3
            else -> 0
        }
        if (fm != 0) {
            val sf = straightHigh(fm)
            if (sf >= 0) return (8 shl 20) or (sf shl 16)
        }
        var quad = -1; var trip1 = -1; var trip2 = -1; var pair1 = -1; var pair2 = -1
        for (r in 12 downTo 0) {
            when (count[r]) {
                4 -> quad = r
                3 -> if (trip1 < 0) trip1 = r else if (trip2 < 0) trip2 = r
                2 -> if (pair1 < 0) pair1 = r else if (pair2 < 0) pair2 = r
            }
        }
        if (quad >= 0) return (7 shl 20) or (quad shl 16) or (topBits(all and (1 shl quad).inv(), 1) shl 12)
        if (trip1 >= 0 && (trip2 >= 0 || pair1 >= 0)) {
            val p = maxOf(trip2, pair1)
            return (6 shl 20) or (trip1 shl 16) or (p shl 12)
        }
        if (fm != 0) return (5 shl 20) or topBits(fm, 5)
        val st = straightHigh(all)
        if (st >= 0) return (4 shl 20) or (st shl 16)
        if (trip1 >= 0) return (3 shl 20) or (trip1 shl 16) or (topBits(all and (1 shl trip1).inv(), 2) shl 8)
        if (pair2 >= 0) {
            val rest = all and (1 shl pair1).inv() and (1 shl pair2).inv()
            return (2 shl 20) or (pair1 shl 16) or (pair2 shl 12) or (topBits(rest, 1) shl 8)
        }
        if (pair1 >= 0) return (1 shl 20) or (pair1 shl 16) or (topBits(all and (1 shl pair1).inv(), 3) shl 4)
        return topBits(all, 5)
    }

    companion object {
        /** Highest rank of a straight in [mask] (ace-low wheel = 3), or -1. */
        fun straightHigh(mask: Int): Int {
            val ext = (mask shl 1) or ((mask ushr 12) and 1)
            for (top in 13 downTo 4) if ((ext ushr (top - 4)) and 0x1F == 0x1F) return top - 1
            return -1
        }

        /** Packs the [k] highest set ranks of [mask], 4 bits each, highest first, left-aligned to k slots. */
        fun topBits(mask: Int, k: Int): Int {
            var m = mask; var out = 0; var taken = 0
            while (taken < k && m != 0) {
                val r = 31 - Integer.numberOfLeadingZeros(m)
                out = (out shl 4) or r
                m = m and (1 shl r).inv()
                taken++
            }
            return out shl (4 * (k - taken))
        }
    }
}
