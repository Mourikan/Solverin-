package com.solverin.engine.math

import com.solverin.engine.core.HandClass
import kotlin.math.ln

/**
 * Postflop playability per hand class, decomposed into 3 features (0..1),
 * blended by projected SPR:
 *  - high:  raw high-card / top-pair strength (dominates at low SPR)
 *  - nut:   ability to make nut hands: sets, nut flushes (dominates at high SPR / implied odds)
 *  - conn:  straight connectivity / board coverage
 * 3 x FloatArray(169) = ~2 KB, computed once.
 */
object Playability {
    private val HIGH = FloatArray(HandClass.COUNT)
    private val NUT = FloatArray(HandClass.COUNT)
    private val CONN = FloatArray(HandClass.COUNT)

    private const val SPR_LOW = 3.0
    private const val SPR_HIGH = 15.0
    private val GAP_CONN = floatArrayOf(1.0f, 0.8f, 0.6f, 0.35f, 0.15f)

    init {
        for (h in 0 until HandClass.COUNT) {
            val hi = HandClass.highRank(h)
            val lo = HandClass.lowRank(h)
            val hiN = hi / 12f
            if (HandClass.isPair(h)) {
                HIGH[h] = 0.35f + 0.65f * hiN
                NUT[h] = 0.8f + 0.2f * hiN
                CONN[h] = 0.2f
                continue
            }
            val suited = HandClass.isSuited(h)
            val broadway = lo >= 8
            HIGH[h] = ((hi + lo) / 24f * 0.85f + if (broadway) 0.15f else 0f).coerceAtMost(1f)
            NUT[h] = if (suited) {
                (0.45f + (if (hi == 12) 0.4f else 0f) + 0.15f * hiN).coerceAtMost(1f)
            } else {
                0.1f + if (hi == 12) 0.15f else 0f
            }
            val gap = hi - lo - 1
            var conn = if (gap < GAP_CONN.size) GAP_CONN[gap] else 0f
            if (hi == 12 && lo <= 3) conn = maxOf(conn, 0.45f) // wheel Ax
            if (lo == 0 && gap > 0) conn *= 0.8f               // deuce low end
            CONN[h] = if (suited) conn else conn * 0.7f
        }
    }

    fun high(h: Int) = HIGH[h]
    fun nut(h: Int) = NUT[h]
    fun conn(h: Int) = CONN[h]

    /** SPR interpolation t in [0,1] on log scale between SPR_LOW and SPR_HIGH. */
    fun sprT(spr: Double): Float {
        if (spr <= SPR_LOW) return 0f
        if (spr >= SPR_HIGH) return 1f
        return ((ln(spr) - ln(SPR_LOW)) / (ln(SPR_HIGH) - ln(SPR_LOW))).toFloat()
    }

    /** Playability score in [0,1] at the given projected SPR. */
    fun score(h: Int, spr: Double): Float {
        val t = sprT(spr)
        val wHigh = 0.7f - 0.35f * t
        val wNut = 0.1f + 0.25f * t
        val wConn = 0.2f + 0.1f * t
        return wHigh * HIGH[h] + wNut * NUT[h] + wConn * CONN[h]
    }
}
