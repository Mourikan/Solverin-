package com.solverin.engine.range

import com.solverin.engine.core.HandClass

/** Human-editable chart in range notation. raise = value + bluff. */
data class ChartSpec(val value: String, val bluff: String = "", val call: String = "") {
    fun compile(): SpotStrategy {
        val v = RangeParser.parse(value)
        val b = RangeParser.parse(bluff)
        val c = RangeParser.parse(call)
        val builder = SpotStrategy.Builder()
        for (h in 0 until HandClass.COUNT) {
            val r = minOf(1f, v[h] + b[h])
            builder.raise[h] = r
            builder.call[h] = minOf(c[h], 1f - r)
            builder.value[h] = v[h] > 0f
        }
        return builder.build()
    }
}
