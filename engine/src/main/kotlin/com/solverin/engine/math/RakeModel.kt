package com.solverin.engine.math

import com.solverin.engine.core.RakeStructure

/**
 * Converts rake into an equity-realization drag on passive (flat) continues.
 * drag = effectiveRate(projected final pot) * SENSITIVITY.
 * Flats have thin EV margins, so rake hits them proportionally harder than raises.
 */
object RakeModel {
    /** Average final pot / preflop pot when the hand goes postflop. */
    const val POT_GROWTH = 1.8
    const val SENSITIVITY = 4.0

    fun drag(rake: RakeStructure, preflopPotBb: Double): Double =
        rake.effectiveRate(preflopPotBb * POT_GROWTH) * SENSITIVITY

    /**
     * Continue multiplier for a hand with realization [eqr]: (eqr - drag) / (eqr - refDrag).
     * <1 when rake is above reference (tighten flats), >1 when below (widen).
     */
    fun multiplier(eqr: Float, drag: Double, refDrag: Double): Float {
        val num = eqr - drag
        val den = eqr - refDrag
        if (den <= 1e-4) return 1f
        return (num / den).toFloat().coerceIn(0f, 1.5f)
    }
}
