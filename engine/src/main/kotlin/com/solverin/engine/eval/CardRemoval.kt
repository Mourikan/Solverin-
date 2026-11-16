package com.solverin.engine.eval

import com.solverin.engine.core.HandClass

/**
 * Card-removal lookup: FREE[h * 169 + v] = combos of class v compatible with one combo of class h.
 * Exact for suit-symmetric ranges. 28.5 KB, O(1) per query.
 */
object CardRemoval {
    private val FREE = ByteArray(HandClass.COUNT * HandClass.COUNT)

    init {
        val masks = HandClass.COMBO_MASKS
        val off = HandClass.OFFSETS
        for (h in 0 until HandClass.COUNT) {
            val hm = HandClass.representative(h)
            for (v in 0 until HandClass.COUNT) {
                var free = 0
                for (i in off[v] until off[v + 1]) if (masks[i] and hm == 0L) free++
                FREE[h * HandClass.COUNT + v] = free.toByte()
            }
        }
    }

    fun freeCombos(hero: Int, villain: Int): Int = FREE[hero * HandClass.COUNT + villain].toInt()

    /** Weighted villain combos remaining given hero class. */
    fun remaining(hero: Int, range: FloatArray): Double {
        var s = 0.0
        val base = hero * HandClass.COUNT
        for (v in 0 until HandClass.COUNT) {
            val w = range[v]
            if (w > 0f) s += w * FREE[base + v]
        }
        return s
    }

    /** Unblocked combos of range (no hero cards). */
    fun total(range: FloatArray): Double {
        var s = 0.0
        for (v in 0 until HandClass.COUNT) s += range[v] * HandClass.combos(v)
        return s
    }
}
