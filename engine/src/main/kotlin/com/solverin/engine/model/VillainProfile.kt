package com.solverin.engine.model

import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import kotlin.math.pow

/**
 * Opponent tendencies (conditional frequencies, 0..1). Population priors per stake;
 * override with HUD reads. All villain ranges are generated from these numbers.
 */
data class VillainProfile(
    val rfiUtg: Double, val rfiHj: Double, val rfiCo: Double, val rfiBtn: Double, val rfiSb: Double,
    /** Non-blind players facing a CO open. */
    val coldCall: Double, val cold3Bet: Double,
    /** SB facing a BTN open. */
    val sbCall: Double, val sb3Bet: Double,
    /** BB facing a BTN open. */
    val bbCall: Double, val bb3Bet: Double,
    val foldTo3Bet: Double, val fourBet: Double,
    val foldTo4Bet: Double, val fiveBet: Double,
    val foldTo5Bet: Double,
    /** Squeeze freq of each player behind when hero flats. */
    val squeeze: Double,
    val callerFoldToSqueeze: Double,
    /** 1.0 = linear (pure value), 0.5 = fully polarized. */
    val threeBetValueShare: Double,
    val fourBetValueShare: Double,
    /**
     * How strongly defense tightens vs earlier opens: defense × (rfi(opener) / rfi(reference))^sensitivity.
     * 0.35 = position-aware regs, ~0.15 = micro-stakes pool that defends almost the same vs any position.
     */
    val positionSensitivity: Double = 0.5,
) {
    fun rfi(p: Position): Double = when (p) {
        Position.UTG -> rfiUtg; Position.HJ -> rfiHj; Position.CO -> rfiCo
        Position.BTN -> rfiBtn; Position.SB -> rfiSb; Position.BB -> 0.0
    }

    /** (3bet, call) of [defender] facing an open from [opener]; scaled by opener width. */
    fun defense(defender: Position, opener: Position): Pair<Double, Double> {
        val (t, c, ref) = when (defender) {
            Position.BB -> Triple(bb3Bet, bbCall, rfiBtn)
            Position.SB -> Triple(sb3Bet, sbCall, rfiBtn)
            else -> Triple(cold3Bet, coldCall, rfiCo)
        }
        var k = (rfi(opener) / ref).pow(positionSensitivity)
        if (opener == Position.SB && defender == Position.BB) k = maxOf(k, 1.1)
        val t2 = t * k; val c2 = c * k
        val s = t2 + c2
        return if (s > 0.95) Pair(t2 * 0.95 / s, c2 * 0.95 / s) else Pair(t2, c2)
    }

    companion object {
        /**
         * Population priors. Blind 3bet vs BTN: SB + BB ≈ 8.5% at NL2 (value only), ≈ 12% at NL50, ≈ 14% at NL200.
         * Micro pools flat a lot, 3bet little and almost only for value, and barely tighten vs early opens.
         */
        fun population(stake: Stake): VillainProfile = when (stake) {
            Stake.NL2 -> VillainProfile(
                rfiUtg = .14, rfiHj = .17, rfiCo = .23, rfiBtn = .36, rfiSb = .30,
                coldCall = .14, cold3Bet = .030, sbCall = .22, sb3Bet = .045, bbCall = .56, bb3Bet = .040,
                foldTo3Bet = .45, fourBet = .040, foldTo4Bet = .40, fiveBet = .25, foldTo5Bet = .25,
                squeeze = .025, callerFoldToSqueeze = .62, threeBetValueShare = .95, fourBetValueShare = .95,
                positionSensitivity = .15,
            )
            Stake.NL5 -> VillainProfile(
                rfiUtg = .15, rfiHj = .18, rfiCo = .25, rfiBtn = .38, rfiSb = .33,
                coldCall = .12, cold3Bet = .035, sbCall = .18, sb3Bet = .050, bbCall = .55, bb3Bet = .045,
                foldTo3Bet = .48, fourBet = .045, foldTo4Bet = .43, fiveBet = .24, foldTo5Bet = .28,
                squeeze = .030, callerFoldToSqueeze = .66, threeBetValueShare = .90, fourBetValueShare = .90,
                positionSensitivity = .20,
            )
            Stake.NL10 -> VillainProfile(
                rfiUtg = .15, rfiHj = .19, rfiCo = .26, rfiBtn = .40, rfiSb = .35,
                coldCall = .10, cold3Bet = .045, sbCall = .14, sb3Bet = .055, bbCall = .53, bb3Bet = .050,
                foldTo3Bet = .52, fourBet = .055, foldTo4Bet = .47, fiveBet = .23, foldTo5Bet = .32,
                squeeze = .040, callerFoldToSqueeze = .70, threeBetValueShare = .82, fourBetValueShare = .85,
                positionSensitivity = .28,
            )
            Stake.NL25 -> VillainProfile(
                rfiUtg = .16, rfiHj = .20, rfiCo = .27, rfiBtn = .42, rfiSb = .37,
                coldCall = .085, cold3Bet = .055, sbCall = .11, sb3Bet = .060, bbCall = .50, bb3Bet = .055,
                foldTo3Bet = .54, fourBet = .060, foldTo4Bet = .50, fiveBet = .22, foldTo5Bet = .35,
                squeeze = .050, callerFoldToSqueeze = .74, threeBetValueShare = .75, fourBetValueShare = .80,
                positionSensitivity = .35,
            )
            Stake.NL50 -> VillainProfile(
                rfiUtg = .16, rfiHj = .20, rfiCo = .27, rfiBtn = .43, rfiSb = .39,
                coldCall = .070, cold3Bet = .060, sbCall = .09, sb3Bet = .065, bbCall = .52, bb3Bet = .060,
                foldTo3Bet = .55, fourBet = .065, foldTo4Bet = .52, fiveBet = .22, foldTo5Bet = .38,
                squeeze = .055, callerFoldToSqueeze = .77, threeBetValueShare = .70, fourBetValueShare = .78,
                positionSensitivity = .35,
            )
            Stake.NL100 -> VillainProfile(
                rfiUtg = .17, rfiHj = .21, rfiCo = .28, rfiBtn = .44, rfiSb = .40,
                coldCall = .065, cold3Bet = .065, sbCall = .08, sb3Bet = .070, bbCall = .53, bb3Bet = .065,
                foldTo3Bet = .56, fourBet = .070, foldTo4Bet = .53, fiveBet = .21, foldTo5Bet = .40,
                squeeze = .060, callerFoldToSqueeze = .79, threeBetValueShare = .66, fourBetValueShare = .74,
                positionSensitivity = .35,
            )
            Stake.NL200 -> VillainProfile(
                rfiUtg = .17, rfiHj = .21, rfiCo = .28, rfiBtn = .45, rfiSb = .42,
                coldCall = .060, cold3Bet = .070, sbCall = .07, sb3Bet = .072, bbCall = .54, bb3Bet = .068,
                foldTo3Bet = .55, fourBet = .075, foldTo4Bet = .54, fiveBet = .21, foldTo5Bet = .42,
                squeeze = .065, callerFoldToSqueeze = .80, threeBetValueShare = .62, fourBetValueShare = .70,
                positionSensitivity = .35,
            )
        }
    }
}
