package com.solverin.engine.math

import kotlin.math.pow

/**
 * R_real(pos, SPR): share of raw equity converted into pot share postflop.
 * Hand-specific postflop value (nut potential, domination) lives in [ImpliedOdds].
 *  - OOP < IP, caller < aggressor (initiative).
 *  - SPR -> 0 pushes R -> 1 (all-in equity realizes fully).
 *  - Each extra player multiplies by MULTIWAY_DECAY.
 */
object Realization {
    const val IP_INITIATIVE = 1.00f
    const val IP_CALLER = 0.92f
    const val OOP_INITIATIVE = 0.86f
    const val OOP_CALLER = 0.76f
    const val MULTIWAY_DECAY = 0.88f
    const val LOW_SPR = 4.0

    fun factor(inPosition: Boolean, initiative: Boolean, spr: Double, players: Int = 2): Float {
        var r = when {
            inPosition && initiative -> IP_INITIATIVE
            inPosition -> IP_CALLER
            initiative -> OOP_INITIATIVE
            else -> OOP_CALLER
        }
        val low = ((LOW_SPR - spr) / LOW_SPR).coerceIn(0.0, 1.0).toFloat()
        r += (1f - r) * low
        if (players > 2) r *= MULTIWAY_DECAY.pow(players - 2)
        return r
    }
}
