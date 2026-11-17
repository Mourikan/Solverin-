package com.solverin.engine.flop

import com.solverin.engine.core.Stake

/**
 * Villain flop tendencies (population priors; all frequencies of villain's range at the node).
 * Fold-to-bet is measured at the half-pot reference size and rescaled by MDF for other sizes.
 */
data class FlopProfile(
    val foldToBet: Double,
    val raiseVsBet: Double,
    val cbet: Double,
    val stabVsCheck: Double,
    val donk: Double,
    val foldToRaise: Double,
    val reraiseVsRaise: Double,
    val callJam: Double,
    val betValueShare: Double,
    val raiseValueShare: Double,
    /** Villain's own bet size as a fraction of pot. */
    val betSizeFraction: Double = 0.5,
    /** Preflop aggressor facing a donk bet: fold frequency at the half-pot reference size. */
    val foldToDonk: Double = 0.38,
    /** Weakness-read donks: share of the non-value part made of weak made hands (rest: draws / semi-bluffs). */
    val weakDonkMadeShare: Double = 0.7,
    /** Max fold frequency vs a min-bet (< minBetRatio × pot): pools almost never fold to tiny bets. */
    val microBetMaxFold: Double = 0.10,
) {
    companion object {
        fun population(stake: Stake): FlopProfile = when (stake) {
            Stake.NL2 -> FlopProfile(.38, .07, .55, .40, .10, .42, .05, .75, .72, .85)
            Stake.NL5 -> FlopProfile(.40, .08, .56, .42, .09, .45, .05, .72, .70, .82)
            Stake.NL10 -> FlopProfile(.42, .08, .58, .43, .08, .48, .06, .68, .67, .78)
            Stake.NL25 -> FlopProfile(.44, .09, .59, .44, .07, .52, .06, .64, .64, .74)
            Stake.NL50 -> FlopProfile(.45, .10, .60, .45, .06, .55, .07, .60, .62, .70)
            Stake.NL100 -> FlopProfile(.46, .11, .59, .47, .05, .57, .08, .57, .60, .67)
            Stake.NL200 -> FlopProfile(.47, .12, .58, .48, .05, .58, .08, .55, .58, .65)
        }
    }
}

/** Villain flop decision points that can be node-locked. */
enum class FlopVillainNode {
    /** Facing hero's bet (c-bet, stab): fold / raise frequencies. */
    VS_BET,
    /** Preflop aggressor facing hero's donk lead: fold / raise frequencies. */
    VS_DONK,
    /** Facing hero's raise: fold / re-raise frequencies. */
    VS_RAISE,
    /** Facing hero's all-in: fold frequency. */
    VS_JAM,
    /** Villain as preflop aggressor: c-bet frequency. */
    CBET,
    /** Villain IP after hero checks, without initiative: stab frequency. */
    STAB,
    /** Villain OOP leading into the preflop aggressor: donk frequency. */
    DONK,
}

/** Locked frequencies; null keeps the profile value. */
data class FlopNodeLock(
    val fold: Double? = null,
    val raise: Double? = null,
    val frequency: Double? = null,
    val valueShare: Double? = null,
) {
    init {
        for (v in listOfNotNull(fold, raise, frequency, valueShare)) require(v in 0.0..1.0) { "Lock values must be in [0, 1]" }
        require((fold ?: 0.0) + (raise ?: 0.0) <= 1.0 + 1e-9) { "fold + raise > 1" }
    }
}

data class FlopNodeLocks(val locks: Map<FlopVillainNode, FlopNodeLock> = emptyMap()) {
    operator fun get(node: FlopVillainNode): FlopNodeLock? = locks[node]
    fun with(node: FlopVillainNode, lock: FlopNodeLock) = FlopNodeLocks(locks + (node to lock))

    companion object {
        val NONE = FlopNodeLocks()
        fun foldToCbet(fold: Double) = NONE.with(FlopVillainNode.VS_BET, FlopNodeLock(fold = fold))
        fun raiseVsCbet(raise: Double) = NONE.with(FlopVillainNode.VS_BET, FlopNodeLock(raise = raise))
        fun donkFrequency(freq: Double, valueShare: Double? = null) =
            NONE.with(FlopVillainNode.DONK, FlopNodeLock(frequency = freq, valueShare = valueShare))
    }
}
