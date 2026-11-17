package com.solverin.engine.river

import com.solverin.engine.eval.HandEvaluator
import com.solverin.engine.flop.Combos

/** Exact river showdown strengths of every live combo (1326 evaluations, no tables). */
class RiverShowdown(val board: IntArray) {
    val dead: Long = Combos.mask(board)
    val strength = IntArray(Combos.COUNT) { -1 }

    init {
        require(board.size == 5)
        val ev = HandEvaluator()
        val cards = IntArray(7); board.copyInto(cards)
        for (k in 0 until Combos.COUNT) {
            if (!Combos.isLive(k, dead)) continue
            cards[5] = Combos.C1[k]; cards[6] = Combos.C2[k]
            strength[k] = ev.evaluate(cards, 7)
        }
    }

    fun category(k: Int): Int = if (strength[k] < 0) -1 else strength[k] ushr 20

    /** Non-zero combos of [w], strongest first. */
    fun order(w: FloatArray): IntArray = (0 until Combos.COUNT).filter { w[it] > 0f && strength[it] >= 0 }
        .sortedByDescending { strength[it] }.toIntArray()

    /** Showdown share of [h] vs [w] with exact card removal (NaN when nothing is live). */
    fun equity(h: Int, w: FloatArray, idx: IntArray): Float {
        val m = Combos.MASK[h]; val s = strength[h]
        var n = 0.0; var d = 0.0
        for (b in idx) {
            val wb = w[b]
            if (wb <= 0f || m and Combos.MASK[b] != 0L) continue
            d += wb
            val t = strength[b]
            if (s > t) n += wb else if (s == t) n += 0.5 * wb
        }
        return if (d > 0) (n / d).toFloat() else Float.NaN
    }

    /** Equity of every combo of [of] vs [w]. */
    fun equities(of: FloatArray, w: FloatArray): FloatArray {
        val idx = order(w)
        return FloatArray(Combos.COUNT) { k -> if (of[k] <= 0f || strength[k] < 0) Float.NaN else equity(k, w, idx) }
    }
}

/**
 * Terminal blocker decomposition vs a villain reference range V (exact card removal by bitboards):
 *  - B_nut(h)  = [V_nut weight left given h / V weight left given h] ÷ [V_nut / V]; V_nut = combos with ≥
 *    blockerNutEquity vs hero's range (nuts / calls). B_nut < 1: h removes villain's continuing range.
 *  - B_fold(h) = same ratio over V_fold = combos below blockerFoldEquity (missed draws / air). B_fold < 1: h blocks the
 *    hands villain folds; B_fold > 1: h leaves his folding range intact.
 * Bluff selection prefers low B_nut and high B_fold (no blocking of missed draws); bluff-catching prefers low
 * B_nut on his value and does not block his natural bluffs.
 */
class RiverBlockerEngine(range: FloatArray, villainEquity: FloatArray, nutEquity: Float, foldEquity: Float) {
    private val all = DoubleArray(52); private val nut = DoubleArray(52); private val fold = DoubleArray(52)
    private val wAll = FloatArray(Combos.COUNT); private val wNut = FloatArray(Combos.COUNT); private val wFold = FloatArray(Combos.COUNT)
    private var tAll = 0.0; private var tNut = 0.0; private var tFold = 0.0

    init {
        for (k in 0 until Combos.COUNT) {
            val w = range[k]
            if (w <= 0f) continue
            val e = villainEquity[k]
            if (e.isNaN()) continue
            val c1 = Combos.C1[k]; val c2 = Combos.C2[k]
            wAll[k] = w; tAll += w; all[c1] += w; all[c2] += w
            if (e >= nutEquity) { wNut[k] = w; tNut += w; nut[c1] += w; nut[c2] += w }
            if (e < foldEquity) { wFold[k] = w; tFold += w; fold[c1] += w; fold[c2] += w }
        }
    }

    private fun left(h: Int, by: DoubleArray, w: FloatArray, total: Double): Double =
        total - by[Combos.C1[h]] - by[Combos.C2[h]] + w[h]

    private fun ratio(h: Int, by: DoubleArray, w: FloatArray, total: Double): Double {
        if (total <= 0.0 || tAll <= 0.0) return 1.0
        val la = left(h, all, wAll, tAll)
        if (la <= 0.0) return 1.0
        return (left(h, by, w, total) / la) / (total / tAll)
    }

    fun nutBlocker(h: Int): Double = ratio(h, nut, wNut, tNut)
    fun foldBlocker(h: Int): Double = ratio(h, fold, wFold, tFold)
    val nutShare: Double get() = if (tAll > 0) tNut / tAll else 0.0
    val foldShare: Double get() = if (tAll > 0) tFold / tAll else 0.0
}
