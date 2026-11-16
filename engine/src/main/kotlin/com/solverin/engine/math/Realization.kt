package com.solverin.engine.math

import kotlin.math.pow

/**
 * Equity realization R: fraction of raw all-in equity converted into pot share postflop.
 * OOP is penalized twice: lower base AND steeper dependence on playability, so low-playability
 * hands OOP without initiative (blind defense vs CO/BTN) collapse first.
 * Low SPR pushes R -> 1 (all-in equity realizes fully). Each extra player costs MULTIWAY_DECAY.
 */
object Realization {
    const val IP_INITIATIVE = 1.12f
    const val IP_CALLER = 1.02f
    const val OOP_INITIATIVE = 0.95f
    const val OOP_CALLER = 0.85f
    const val MULTIWAY_DECAY = 0.88f
    private const val LOW_SPR = 4.0

    fun factor(hand: Int, spr: Double, inPosition: Boolean, initiative: Boolean, players: Int = 2): Float {
        val play = Playability.score(hand, spr)
        var r = if (inPosition) {
            (if (initiative) IP_INITIATIVE else IP_CALLER) * (0.70f + 0.45f * play)
        } else {
            (if (initiative) OOP_INITIATIVE else OOP_CALLER) * (0.55f + 0.65f * play)
        }
        val low = ((LOW_SPR - spr) / LOW_SPR).coerceIn(0.0, 1.0).toFloat()
        r += (1f - r) * low
        if (players > 2) r *= MULTIWAY_DECAY.pow(players - 2)
        return r
    }
}
