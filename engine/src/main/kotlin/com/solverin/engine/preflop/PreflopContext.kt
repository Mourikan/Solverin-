package com.solverin.engine.preflop

import com.solverin.engine.core.RakeStructure
import com.solverin.engine.core.Stake
import com.solverin.engine.math.Sizing
import com.solverin.engine.math.VillainProfile

data class PreflopContext(
    val stake: Stake,
    val effectiveStackBb: Double = 100.0,
    val rake: RakeStructure = stake.defaultRake,
    val villain: VillainProfile = VillainProfile.population(stake),
    val sizing: Sizing = Sizing(),
    val tuning: Tuning = Tuning(),
) {
    companion object {
        const val REFERENCE_STACK_BB = 100.0
    }
}

/** Deviation gains. All adjustments scale linearly with these. */
data class Tuning(
    /** Bluff-raise freq change per unit of relative fold-equity delta. */
    val foldEquityGain: Float = 2.0f,
    /** Bluff-raise reweighting by normalized blocker score. */
    val blockerGain: Float = 0.6f,
    /** Calls -> value raises vs stations, per unit of fold deficit. */
    val valueGain: Float = 3.0f,
    /** New bluff raises carved from flats when villain overfolds. */
    val bluffExpansion: Float = 1.5f,
    /** Playability threshold to keep a cut bluff as a flat instead of folding. */
    val flatKeepPlayability: Float = 0.55f,
    /** Toggle components. */
    val applyRake: Boolean = true,
    val applySpr: Boolean = true,
    val applyBlockers: Boolean = true,
    val applyExploit: Boolean = true,
)
