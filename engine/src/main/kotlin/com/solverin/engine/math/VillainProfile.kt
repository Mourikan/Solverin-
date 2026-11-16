package com.solverin.engine.math

import com.solverin.engine.core.Stake
import com.solverin.engine.range.Scenario

/**
 * Villain fold frequency vs hero's aggressive action at each node.
 * Nash reference vs population/read priors: delta drives Max-Exploit deviations.
 */
data class VillainProfile(
    val foldVsOpen: Double,      // blinds combined fold vs steal (RFI)
    val foldVs3Bet: Double,      // opener folds to our 3bet (VS_RFI)
    val foldVs4Bet: Double,      // 3bettor folds to our 4bet (VS_3BET)
    val foldVs5Bet: Double,      // 4bettor folds to our jam (VS_4BET)
    val foldVsSqueeze: Double,   // opener+caller fold to our squeeze
) {
    fun foldVs(s: Scenario): Double = when (s) {
        Scenario.RFI -> foldVsOpen
        Scenario.VS_RFI -> foldVs3Bet
        Scenario.VS_3BET -> foldVs4Bet
        Scenario.VS_4BET -> foldVs5Bet
        Scenario.SQUEEZE -> foldVsSqueeze
    }

    companion object {
        /** Equilibrium reference the baseline charts assume. */
        val NASH = VillainProfile(0.55, 0.55, 0.58, 0.40, 0.66)

        /** Population priors by stake (override with HUD reads). */
        fun population(stake: Stake): VillainProfile = when (stake) {
            Stake.NL2 -> VillainProfile(0.46, 0.52, 0.45, 0.30, 0.60)
            Stake.NL5 -> VillainProfile(0.48, 0.55, 0.50, 0.33, 0.62)
            Stake.NL10 -> VillainProfile(0.50, 0.57, 0.53, 0.36, 0.64)
            Stake.NL25 -> VillainProfile(0.53, 0.58, 0.56, 0.38, 0.66)
            Stake.NL50 -> VillainProfile(0.55, 0.57, 0.58, 0.40, 0.66)
            Stake.NL100 -> VillainProfile(0.55, 0.56, 0.59, 0.41, 0.65)
            Stake.NL200 -> VillainProfile(0.55, 0.55, 0.60, 0.42, 0.65)
        }
    }
}
