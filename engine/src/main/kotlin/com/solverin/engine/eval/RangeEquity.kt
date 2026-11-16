package com.solverin.engine.eval

import com.solverin.engine.core.HandClass

/** Hero class equity vs a weighted range, card-removal exact at class level. */
class RangeEquity(private val table: EquityTable = EquityTable.get()) {

    fun vs(hero: Int, range: FloatArray): Float {
        var num = 0.0; var den = 0.0
        for (v in 0 until HandClass.COUNT) {
            val w = range[v]
            if (w <= 0f) continue
            val c = w * CardRemoval.freeCombos(hero, v)
            num += c * table.equity(hero, v); den += c
        }
        return if (den <= 0.0) 0.5f else (num / den).toFloat()
    }

    /**
     * 3-way equity approximation from pairwise equities e1, e2 (villain-vs-villain assumed 50%).
     */
    fun multiway(e1: Float, e2: Float): Float {
        val h = e1 * e2
        val v1 = (1 - e1) * 0.5f
        val v2 = (1 - e2) * 0.5f
        val s = h + v1 + v2
        return if (s <= 0f) 0f else h / s
    }
}
