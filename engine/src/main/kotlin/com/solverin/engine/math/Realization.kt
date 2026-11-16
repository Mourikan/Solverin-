package com.solverin.engine.math

import com.solverin.engine.core.HandClass
import kotlin.math.pow

/**
 * R_real = BasePos(IP/OOP) · InitiativeFactor · (1 + PlayabilityScore(hand) − DominationPenalty(hand, range))
 *
 *  InitiativeFactor  = aggressor 1.00 | caller by pot level: single-raised 0.97, 3bet pot 0.88, 4bet+ pot 0.82
 *  PlayabilityScore  = PLAY_WEIGHT · (playability(h, SPR) − combo-weighted mean playability at SPR)
 *  DominationPenalty = DOM_WEIGHT · share of villain range dominating hero
 *  SPR < LOW_SPR pulls R → 1 (stacks go in, equity realizes); each extra player × MULTIWAY_DECAY.
 */
object Realization {
    const val BASE_IP = 1.06
    const val BASE_OOP = 0.86
    const val INITIATIVE_AGGRESSOR = 1.00
    const val INITIATIVE_CALLER = 0.97
    const val INITIATIVE_CALLER_3BET_POT = 0.88
    const val INITIATIVE_CALLER_4BET_POT = 0.82
    const val PLAY_WEIGHT = 0.7
    const val DOM_WEIGHT = 0.25
    const val MULTIWAY_DECAY = 0.88
    const val LOW_SPR = 1.5
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
    /** [potLevel]: 1 = single-raised pot, 2 = 3bet pot, 3+ = 4bet pot or more. */
    fun initiative(aggressor: Boolean, potLevel: Int = 1) = when {
        aggressor -> INITIATIVE_AGGRESSOR
        potLevel <= 1 -> INITIATIVE_CALLER
        potLevel == 2 -> INITIATIVE_CALLER_3BET_POT
        else -> INITIATIVE_CALLER_4BET_POT
    }

    fun playabilityScore(h: Int, spr: Double): Double {
        var mean = 0.0
        for (x in 0 until HandClass.COUNT) mean += Playability.score(x, spr) * HandClass.combos(x)
        mean /= HandClass.TOTAL_COMBOS
        return PLAY_WEIGHT * (Playability.score(h, spr) - mean)
    }

    fun dominationPenalty(h: Int, villain: FloatArray?): Double =
        if (villain == null) 0.0 else DOM_WEIGHT * Domination.share(h, villain)

    fun compute(
        h: Int, villain: FloatArray?, inPosition: Boolean, aggressor: Boolean, spr: Double, players: Int = 2, potLevel: Int = 1,
    ): Terms {
        val bp = basePos(inPosition)
        val init = initiative(aggressor, potLevel)
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
    fun factor(inPosition: Boolean, initiative: Boolean, spr: Double, players: Int = 2, potLevel: Int = 1): Float {
        var r = basePos(inPosition) * initiative(initiative, potLevel)
        val low = ((LOW_SPR - spr) / LOW_SPR).coerceIn(0.0, 1.0)
        r += (1 - r) * low
        if (players > 2) r *= MULTIWAY_DECAY.pow(players - 2)
        return r.toFloat()
    }
}
