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

    /** Set-mining rule: a small pair calling a re-raise needs stack behind ≥ this multiple of the call. */
    const val SET_MINE_RATIO = 15.0
    const val DOMINATED_OFFSUIT_VS_RERAISE = 0.85
    const val WEAK_SUITED_ACE_VS_RERAISE = 0.92
    const val SMALL_PAIR_NO_SET_ODDS = 0.80
    const val BLIND_TRASH = 0.85
    const val SB_FLAT_SANDWICH = 0.88
    const val DOMINATED_VS_FOUR_BET = 0.75

    /**
     * Preflop calling-realization penalties (multiplier ≤ 1):
     *  - facing a re-raise (potLevel ≥ 2): dominated offsuit broadways (ATo, KJo, QJo…), weak suited aces A2s–A9s,
     *    small pairs 22–99 without 15× set-mining odds; facing a 4-bet also AQo / AJo / ATo / KQo / ATs / KJs / QJs,
     *    connectors T9 and below and underpairs 22–99;
     *  - blind defense (single-raised pot): disconnected offsuit trash (52o, 84o, 73o, 42o, 86o, K2o–K6o, Q2o–Q7o, J2o–J7o);
     *  - SB flat vs an open: sandwiched between the opener and the BB, no initiative (3-bet or fold).
     */
    fun callPenalty(h: Int, potLevel: Int, blindPosted: Double, behindToCallRatio: Double): Double {
        val hi = HandClass.highRank(h); val lo = HandClass.lowRank(h)
        val pair = HandClass.isPair(h); val suited = HandClass.isSuited(h)
        var f = 1.0
        if (potLevel >= 2) {
            if (!pair && !suited && lo >= 8 && !(hi == 12 && lo >= 10)) f *= DOMINATED_OFFSUIT_VS_RERAISE
            if (suited && hi == 12 && lo <= 7) f *= WEAK_SUITED_ACE_VS_RERAISE
            if (pair && hi <= 7 && behindToCallRatio < SET_MINE_RATIO) f *= SMALL_PAIR_NO_SET_ODDS
            // Facing a 4-bet: easily dominated hands (AQo, AJo, ATo, KQo, ATs, KJs, QJs) do not flat.
            if (potLevel >= 3 && !pair && ((!suited && ((hi == 12 && lo in 8..10) || (hi == 11 && lo == 10))) ||
                    (suited && ((hi == 12 && lo == 8) || (hi == 11 && lo == 9) || (hi == 10 && lo == 9))))) f *= DOMINATED_VS_FOUR_BET
            // Facing a 4-bet: connectors T9 and below (suited or not) and underpairs 22–99 (no set odds, no domination
            // equity): penalized alike so a weaker / offsuit version never continues where the stronger one folds.
            if (potLevel >= 3 && !pair && hi <= 8 && hi - lo == 1) f *= DOMINATED_VS_FOUR_BET
            if (potLevel >= 3 && pair && hi <= 7) f *= DOMINATED_VS_FOUR_BET
        } else if (blindPosted > 0.0) {
            val gap = hi - lo
            if (!pair && !suited && hi < 12 && lo < 8 && (gap >= 3 || (hi <= 6 && gap >= 2))) f *= BLIND_TRASH
            if (blindPosted < 1.0) f *= SB_FLAT_SANDWICH
        }
        return f
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
