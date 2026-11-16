package com.solverin.engine.preflop

import com.solverin.engine.core.Position
import com.solverin.engine.core.RakeStructure
import com.solverin.engine.core.Stake
import com.solverin.engine.math.RakeAttribution
import com.solverin.engine.model.NodeLocks
import com.solverin.engine.model.VillainProfile

data class PreflopContext(
    val stake: Stake,
    val effectiveStackBb: Double = 100.0,
    val rake: RakeStructure = stake.defaultRake,
    val villain: VillainProfile = VillainProfile.population(stake),
    val sizing: Sizing = Sizing(),
    val policy: StakePolicy = StakePolicy.of(stake),
    val rakeAttribution: RakeAttribution = RakeAttribution.WINNER_SHARE,
    /** Max Exploit: villain node locks applied on top of [villain]. */
    val locks: NodeLocks = NodeLocks.NONE,
)

/** Raise sizes (bb). */
data class Sizing(
    val openBb: Double = 2.5,
    val sbOpenBb: Double = 3.0,
    val threeBetIpMult: Double = 3.0,
    val threeBetOopMult: Double = 4.0,
    val fourBetMult: Double = 2.25,
    /** Cold 4bet over an open + 3bet (dead money of the opener => slightly larger). */
    val coldFourBetMult: Double = 2.4,
    val squeezeBaseMult: Double = 4.0,
    val squeezePerCallerMult: Double = 1.0,
) {
    fun open(p: Position) = if (p == Position.SB) sbOpenBb else openBb
    fun threeBet(opener: Position, threeBettor: Position) =
        open(opener) * if (threeBettor.isInPositionVs(opener)) threeBetIpMult else threeBetOopMult
    fun fourBet(threeBetTo: Double) = threeBetTo * fourBetMult
    fun coldFourBet(threeBetTo: Double) = threeBetTo * coldFourBetMult
    fun squeeze(opener: Position, callers: Int) = open(opener) * (squeezeBaseMult + squeezePerCallerMult * callers)
}

/**
 * Minimum net EV (bb, after rake) an action must clear vs folding, per stake.
 * NL2-NL10: calls need a clear margin => marginal cold calls / defenses that rake turns
 * negative or near-zero are discarded. NL100-NL200: edges ~0 => strict max-EV.
 */
data class StakePolicy(
    val callEdgeBb: Double,
    val raiseEdgeBb: Double,
    val mixBandBb: Double,
    /** Range-consistency cap on bluff raises (see BluffBudget). */
    val bluffCap: Boolean = true,
    /**
     * Max-EV override of the cap, 3bet / squeeze nodes only: a bluff raise with a real blocker
     * (BlockerFactor < 1) and EV(raise) >= bluffExemptEvBb is always executed.
     */
    val bluffExemptEvBb: Double = 0.7,
    /**
     * 4bet / cold 4bet nodes (VS_3BET, VS_SQUEEZE_*, VS_3BET_COLD): blocker bluffs with EV(raise) >= this get
     * PRIORITY inside the bluff cap (never a bypass of it).
     */
    val bluffExemptFourBetEvBb: Double = 1.3,
    /** Equity vs villain's continuing range that makes a 4bet / cold 4bet a value raise (AK: see akValueMaxStackBb). */
    val fourBetValueEquity: Float = 0.5f,
    /** AKo / AKs are always value 4bets / cold 4bets up to this effective stack (bb). */
    val akValueMaxStackBb: Double = 150.0,
    /** If true the override also requires villain to over-fold vs MDF: P(fold) > 1 − MDF. */
    val bluffExemptRequiresOverfold: Boolean = true,
) {
    companion object {
        fun of(stake: Stake): StakePolicy = when (stake) {
            Stake.NL2 -> StakePolicy(0.10, 0.02, 0.04)
            Stake.NL5 -> StakePolicy(0.08, 0.02, 0.04)
            Stake.NL10 -> StakePolicy(0.06, 0.015, 0.04)
            Stake.NL25 -> StakePolicy(0.04, 0.01, 0.035)
            Stake.NL50 -> StakePolicy(0.025, 0.005, 0.03)
            Stake.NL100 -> StakePolicy(0.01, 0.0, 0.025)
            Stake.NL200 -> StakePolicy(0.0, 0.0, 0.02)
        }
    }
}
