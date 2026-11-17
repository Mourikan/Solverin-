package com.solverin.app.presentation

import com.solverin.engine.core.Stake
import com.solverin.engine.pipeline.HandStatus
import com.solverin.engine.pipeline.Street

/** One viable action at the current node: solver (Nash / exploit) frequency, exact EV in bb and the Max-EV flag. */
data class ActionOption(
    val action: String,
    val frequency: Float,
    val evBb: Double,
    val isMaxEv: Boolean,
)

/** Compact record of a solved street (history strip). */
data class StreetSummary(
    val street: Street,
    val line: String,
    val potBb: Double,
    val bestAction: String,
    val bestEvBb: Double,
)

/**
 * Immutable unidirectional UI state of the hand being analysed (Compose / XML).
 * [actions] are sorted by EV (best first); exactly one carries [ActionOption.isMaxEv] when the node has actions.
 */
data class HandViewState(
    val stake: Stake = Stake.NL50,
    val heroCards: List<String> = emptyList(),
    val board: List<String> = emptyList(),
    val street: Street = Street.PREFLOP,
    val status: HandStatus = HandStatus.IN_PROGRESS,
    val potBb: Double = 0.0,
    val heroStackBb: Double = 0.0,
    val effectiveStackBb: Double = 0.0,
    val spr: Double = 0.0,
    val betSizeFraction: Double? = null,
    val mdf: Double? = null,
    val actions: List<ActionOption> = emptyList(),
    val history: List<StreetSummary> = emptyList(),
    val heroShowdownEquity: Double? = null,
    val rakeAccruedBb: Double = 0.0,
    val latencyMs: Double = 0.0,
    val isComputing: Boolean = false,
    val error: String? = null,
) {
    val maxEvAction: ActionOption? get() = actions.firstOrNull { it.isMaxEv }
}
