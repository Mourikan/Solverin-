package com.solverin.engine.model

import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake

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
        var k = kotlin.math.sqrt(rfi(opener) / ref)
        if (opener == Position.SB && defender == Position.BB) k = maxOf(k, 1.1)
        val t2 = t * k; val c2 = c * k
        val s = t2 + c2
        return if (s > 0.95) Pair(t2 * 0.95 / s, c2 * 0.95 / s) else Pair(t2, c2)
    }

    companion object {
        private fun p(r: DoubleArray, cc: Double, c3: Double, sbc: Double, sb3: Double, bbc: Double, bb3: Double,
                      f3: Double, b4: Double, f4: Double, b5: Double, f5: Double, sq: Double, cfs: Double,
                      v3: Double, v4: Double) =
            VillainProfile(r[0], r[1], r[2], r[3], r[4], cc, c3, sbc, sb3, bbc, bb3, f3, b4, f4, b5, f5, sq, cfs, v3, v4)

        fun population(stake: Stake): VillainProfile = when (stake) {
            Stake.NL2 -> p(doubleArrayOf(.14, .17, .23, .36, .30), .09, .045, .14, .06, .45, .08, .50, .05, .42, .25, .30, .04, .70, .85, .95)
            Stake.NL5 -> p(doubleArrayOf(.15, .18, .25, .38, .33), .085, .05, .13, .07, .44, .09, .52, .055, .45, .24, .32, .045, .72, .80, .90)
            Stake.NL10 -> p(doubleArrayOf(.15, .19, .26, .40, .35), .08, .055, .12, .08, .43, .10, .54, .06, .48, .23, .34, .05, .74, .75, .85)
            Stake.NL25 -> p(doubleArrayOf(.16, .20, .27, .42, .37), .075, .06, .11, .09, .42, .11, .55, .065, .50, .22, .36, .055, .76, .70, .80)
            Stake.NL50 -> p(doubleArrayOf(.16, .20, .27, .43, .39), .07, .065, .10, .10, .41, .12, .56, .07, .52, .22, .38, .06, .78, .65, .80)
            Stake.NL100 -> p(doubleArrayOf(.17, .21, .28, .44, .40), .065, .07, .09, .11, .40, .13, .56, .075, .53, .21, .40, .065, .80, .62, .75)
            Stake.NL200 -> p(doubleArrayOf(.17, .21, .28, .45, .42), .06, .075, .08, .12, .40, .13, .55, .08, .54, .21, .42, .07, .80, .60, .72)
        }
    }
}
