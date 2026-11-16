package com.solverin.engine.flop

import com.solverin.engine.eval.HandEvaluator
import java.util.Arrays

/**
 * Exact combo-vs-range equity on a flop by enumerating turn+river runouts (C(49,2) = 1,176), with exact
 * card removal per hero combo. [stride] > 1 evaluates every stride-th runout (deterministic subsample) for
 * mobile latency: 1 = exact, 4 ≈ 294 runouts.
 */
class FlopEquity(val stride: Int = FAST_STRIDE) {

    /** Per hero combo: win = Σ villain weight beaten (+½ ties), den = Σ villain weight live, over runouts. */
    class Result(val win: DoubleArray, val den: DoubleArray) {
        fun equity(h: Int): Float = if (den[h] > 0) (win[h] / den[h]).toFloat() else Float.NaN

        /** Card-removal-weighted range equity of [hero] vs the villain range this result was built against. */
        fun rangeEquity(hero: FloatArray): Double {
            var n = 0.0; var d = 0.0
            for (h in hero.indices) {
                val w = hero[h]
                if (w <= 0f || den[h] <= 0) continue
                n += w * win[h]; d += w * den[h]
            }
            return if (d > 0) n / d else 0.5
        }
    }

    fun handsVsRange(board: IntArray, villain: FloatArray, heroFilter: FloatArray? = null): Result {
        require(board.size == 3 && villain.size == Combos.COUNT)
        val dead = Combos.mask(board)
        val win = DoubleArray(Combos.COUNT)
        val den = DoubleArray(Combos.COUNT)
        val heroIdx = (0 until Combos.COUNT).filter {
            Combos.isLive(it, dead) && (heroFilter == null || heroFilter[it] > 0f)
        }.toIntArray()
        if (heroIdx.isEmpty()) return Result(win, den)
        val rest = (0 until 52).filter { dead and (1L shl it) == 0L }.toIntArray()
        val str = IntArray(Combos.COUNT)
        val keys = LongArray(Combos.COUNT)
        val sorted = IntArray(Combos.COUNT)
        val prefix = DoubleArray(Combos.COUNT + 1)
        val cards = IntArray(7)
        cards[0] = board[0]; cards[1] = board[1]; cards[2] = board[2]
        val ev = HandEvaluator()
        val c1 = Combos.C1; val c2 = Combos.C2; val mask = Combos.MASK
        var runout = 0
        for (i in rest.indices) for (j in i + 1 until rest.size) {
            if (runout++ % stride != 0) continue
            val t = rest[i]; val r = rest[j]
            cards[3] = t; cards[4] = r
            val d5 = dead or (1L shl t) or (1L shl r)
            var n = 0
            var total = 0.0
            for (k in 0 until Combos.COUNT) {
                if (mask[k] and d5 != 0L) { str[k] = -1; continue }
                cards[5] = c1[k]; cards[6] = c2[k]
                val s = ev.evaluate(cards, 7)
                str[k] = s
                val w = villain[k]
                if (w > 0f) { keys[n++] = (s.toLong() shl 11) or k.toLong(); total += w }
            }
            Arrays.sort(keys, 0, n)
            for (m in 0 until n) {
                val k = (keys[m] and 0x7FF).toInt()
                sorted[m] = (keys[m] ushr 11).toInt()
                prefix[m + 1] = prefix[m] + villain[k]
            }
            for (h in heroIdx) {
                val s = str[h]
                if (s < 0) continue
                val lo = lowerBound(sorted, n, s)
                val hi = upperBound(sorted, n, s)
                var less = prefix[lo]
                var tie = prefix[hi] - prefix[lo]
                var tot = total
                for (v in Combos.BY_CARD[c1[h]]) { val w = villain[v]; if (w <= 0f) continue; val sv = str[v]; if (sv < 0) continue; tot -= w; if (sv < s) less -= w else if (sv == s) tie -= w }
                for (v in Combos.BY_CARD[c2[h]]) { val w = villain[v]; if (w <= 0f) continue; val sv = str[v]; if (sv < 0) continue; tot -= w; if (sv < s) less -= w else if (sv == s) tie -= w }
                val self = villain[h]
                if (self > 0f) { tot += self; tie += self }
                win[h] += less + tie * 0.5
                den[h] += tot
            }
        }
        return Result(win, den)
    }

    private fun lowerBound(a: IntArray, n: Int, v: Int): Int {
        var lo = 0; var hi = n
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
        return lo
    }

    private fun upperBound(a: IntArray, n: Int, v: Int): Int {
        var lo = 0; var hi = n
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] <= v) lo = m + 1 else hi = m }
        return lo
    }

    companion object {
        const val EXACT = 1
        const val FAST_STRIDE = 4
    }
}
