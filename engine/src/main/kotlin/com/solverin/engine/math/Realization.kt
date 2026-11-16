package com.solverin.engine.math

import com.solverin.engine.core.HandClass
import kotlin.math.pow

/**
 * R_real = BasePos(IP/OOP) · InitiativeFactor · (1 + PlayabilityScore(hand) − DominationPenalty(hand, range))
 *
 *  PlayabilityScore  = PLAY_WEIGHT · (playability(h, SPR) − combo-weighted mean playability at SPR)
 *  DominationPenalty = DOM_WEIGHT · share of villain range dominating hero
 *  SPR → 0 pulls R → 1 (all-in equity realizes fully); each extra player × MULTIWAY_DECAY.
 */
object Realization {
    const val BASE_IP = 0.96
    const val BASE_OOP = 0.81
    const val INITIATIVE_AGGRESSOR = 1.04
    const val INITIATIVE_CALLER = 0.95
    const val PLAY_WEIGHT = 0.5
    const val DOM_WEIGHT = 0.6
    const val MULTIWAY_DECAY = 0.88
    const val LOW_SPR = 4.0
    const val MIN_R = 0.35
    const val MAX_R = 1.25

    class Terms(
        val basePos: Double,
        val initiative: Double,
        val playability: Double,
        val domination: Double,
        val value: Float,
        /** Same R with DominationPenalty = 0 (diagnostics). */
        val valueWithoutDomination: Float,
    )

    fun basePos(inPosition: Boolean) = if (inPosition) BASE_IP else BASE_OOP
    fun initiative(aggressor: Boolean) = if (aggressor) INITIATIVE_AGGRESSOR else INITIATIVE_CALLER

    fun playabilityScore(h: Int, spr: Double): Double {
        var mean = 0.0
        for (x in 0 until HandClass.COUNT) mean += Playability.score(x, spr) * HandClass.combos(x)
        mean /= HandClass.TOTAL_COMBOS
        return PLAY_WEIGHT * (Playability.score(h, spr) - mean)
    }

    fun dominationPenalty(h: Int, villain: FloatArray?): Double =
        if (villain == null) 0.0 else DOM_WEIGHT * Domination.share(h, villain)

    fun compute(
        h: Int, villain: FloatArray?, inPosition: Boolean, aggressor: Boolean, spr: Double, players: Int = 2,
    ): Terms {
        val bp = basePos(inPosition)
        val init = initiative(aggressor)
        val ps = playabilityScore(h, spr)
        val dp = dominationPenalty(h, villain)
        val low = ((LOW_SPR - spr) / LOW_SPR).coerceIn(0.0, 1.0)
        val mw = if (players > 2) MULTIWAY_DECAY.pow(players - 2) else 1.0
        fun finish(raw: Double): Float {
            val r = raw.coerceIn(MIN_R, MAX_R)
            return ((r + (1 - r) * low) * mw).toFloat()
        }
        return Terms(bp, init, ps, dp, finish(bp * init * (1 + ps - dp)), finish(bp * init * (1 + ps)))
    }

    /** Hand-independent component (used for range averages). */
    fun factor(inPosition: Boolean, initiative: Boolean, spr: Double, players: Int = 2): Float {
        var r = basePos(inPosition) * initiative(initiative)
        val low = ((LOW_SPR - spr) / LOW_SPR).coerceIn(0.0, 1.0)
        r += (1 - r) * low
        if (players > 2) r *= MULTIWAY_DECAY.pow(players - 2)
        return r.toFloat()
    }
}
