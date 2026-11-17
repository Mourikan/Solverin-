package com.solverin.engine.flop

import com.solverin.engine.core.RakeStructure
import com.solverin.engine.core.Stake
import com.solverin.engine.math.RakeAttribution

/** Hero's decision point on the flop. */
enum class FlopNodeType {
    /** Hero OOP, first to act: CHECK or BET (a donk bet if hero is not the preflop aggressor). */
    FIRST_TO_ACT,
    /** Hero IP after villain checked: CHECK (back) or BET. */
    VS_CHECK,
    /** Hero facing a villain bet (c-bet, stab or donk): FOLD, CALL or RAISE. */
    VS_BET,
}

/** DONK = hero's lead OOP into the preflop aggressor (FIRST_TO_ACT without initiative). */
enum class FlopAction { CHECK, BET, DONK, FOLD, CALL, RAISE }

/**
 * Flop spot. Amounts in bb; [potBb] excludes [facingBetBb]; [effectiveStackBb] = stacks behind at the
 * start of the flop (before the facing bet).
 */
class FlopSpot(
    val board: IntArray,
    val heroRange: FloatArray,
    val villainRange: FloatArray,
    val heroInPosition: Boolean,
    val heroIsAggressor: Boolean,
    val potBb: Double,
    val effectiveStackBb: Double,
    val facingBetBb: Double = 0.0,
) {
    init {
        require(board.size == 3 && board.toSet().size == 3) { "Invalid flop" }
        require(heroRange.size == Combos.COUNT && villainRange.size == Combos.COUNT)
        require(potBb > 0 && effectiveStackBb >= 0 && facingBetBb >= 0)
    }

    val nodeType: FlopNodeType get() = when {
        facingBetBb > 0.0 -> FlopNodeType.VS_BET
        heroInPosition -> FlopNodeType.VS_CHECK
        else -> FlopNodeType.FIRST_TO_ACT
    }

    /** Villain's decision that produced (or will produce) his bet at this node. */
    val villainBetNode: FlopVillainNode get() = when {
        // Villain acts first (OOP): a lead is a c-bet if he raised preflop, otherwise a donk.
        heroInPosition -> if (heroIsAggressor) FlopVillainNode.DONK else FlopVillainNode.CBET
        // Villain acts after hero checked (IP): c-bet if he is the aggressor, otherwise a stab.
        else -> if (heroIsAggressor) FlopVillainNode.STAB else FlopVillainNode.CBET
    }

    val isDonk: Boolean get() = nodeType == FlopNodeType.VS_BET && villainBetNode == FlopVillainNode.DONK
}

/** Tunables of the flop model. */
data class FlopPolicy(
    val smallBet: Double = 0.33,
    val mediumBet: Double = 0.66,
    val overbet: Double = 1.25,
    /** ΔNuts at which the overbet becomes a candidate size. */
    val overbetNutEdge: Double = 0.04,
    /** Elasticity above which villain is "inelastic" (adds a larger size); below elasticThreshold adds a smaller one. */
    val inelasticThreshold: Double = -0.25,
    val elasticThreshold: Double = -0.9,
    val streetsToRiver: Int = 3,
    val raiseMultiplier: Double = 3.0,
    /** Raises over a min-bet are pot-weighted: to prev + raisePotFraction × (pot incl. bet + prev). */
    val raisePotFraction: Double = 0.75,
    /** OOP check-raise frequency caps (hero range share): connected / volatile boards vs the rest. */
    val checkRaiseCapConnected: Double = 0.26,
    val checkRaiseCap: Double = 0.35,
    /** Volatility at which a board counts as connected for the check-raise cap. */
    val connectedVolatility: Double = 0.5,
    /** Bets up to this fraction of the pot (or ≤ 1bb) are min-bets: folding with pot-odds equity is forbidden. */
    val minBetRatio: Double = 0.2,
    /** A call / bet leaving less than commitSpr × pot behind is valued as all-in. */
    val commitSpr: Double = 0.5,
    val mixBandBb: Double = 0.05,
    val nutFraction: Double = 0.03,
    /** Equity above which a combo counts as "nuts" for ΔNuts. */
    val nutEquity: Double = 0.85,
    val strongFraction: Double = 0.15,
    /** R_post vulnerability: R × (1 − vulnerabilityK · villain draw density · hand vulnerability). */
    val vulnerabilityK: Double = 0.6,
    /** Vulnerability of unpaired hands: plain air vs IP overcards / backdoor-equity hands. */
    val airVulnerability: Double = 0.5,
    val backdoorVulnerabilityIp: Double = 0.15,
    val extractIp: Double = 0.8,
    val extractOop: Double = 0.5,
    val realizationIp: Double = 1.0,
    val realizationOop: Double = 0.9,
    val initiativeAggressor: Double = 1.03,
    val initiativeCaller: Double = 0.97,
    val lowSpr: Double = 1.5,
)

data class FlopContext(
    val stake: Stake,
    val rake: RakeStructure = stake.defaultRake,
    val rakeAttribution: RakeAttribution = RakeAttribution.WINNER_SHARE,
    val profile: FlopProfile = FlopProfile.population(stake),
    val locks: FlopNodeLocks = FlopNodeLocks.NONE,
    val policy: FlopPolicy = FlopPolicy(),
)
