package com.solverin.app.presentation

import com.solverin.engine.core.Stake

/** One hero option of the oracle banner: Nash / exploit frequency, exact EV (bb), Max-EV flag and its table action. */
data class ActionOption(
    val key: String,
    val label: String,
    val frequency: Float,
    val evBb: Double,
    val isMaxEv: Boolean,
    val kind: ActKind,
    val toAmountBb: Double,
)

/** One seat of the compact 6-max table. */
data class SeatView(
    val label: String,
    val status: String,
    val investBb: Double,
    val inHand: Boolean,
    val allIn: Boolean,
    val isHero: Boolean,
    val isToAct: Boolean,
)

/** Immutable UI state of the live hand (single source of truth for the Compose screen). */
data class HandViewState(
    val stake: Stake = Stake.NL50,
    val profile: VillainProfilePreset = VillainProfilePreset.POPULATION,
    val phase: StreetFlowPhase = StreetFlowPhase.PICK_HERO_CARDS,
    val street: TableStreet = TableStreet.PREFLOP,
    /** Hero hole cards as "As", "Kd" (size 0..2). */
    val heroCards: List<String> = emptyList(),
    val heroCardIds: List<Int> = emptyList(),
    val heroPosition: String? = null,
    val board: List<String> = emptyList(),
    val boardIds: List<Int> = emptyList(),
    val seats: List<SeatView> = emptyList(),
    val toAct: String? = null,
    val potBb: Double = 0.0,
    val effectiveStackBb: Double = 100.0,
    val toCallBb: Double = 0.0,
    /** Oracle options of hero's current / last decision node (Nash frequency + exact EV in bb). */
    val options: List<ActionOption> = emptyList(),
    val nodeLabel: String = "",
    val approximate: Boolean = false,
    val latencyMs: Double = 0.0,
    /** Key of hero's provisionally committed action (auto Max-EV or chip override), null = preview of the next node. */
    val heroCommittedKey: String? = null,
    /** True while the hero action can still be overridden from the chips (no later input registered). */
    val heroOverridable: Boolean = false,
    val villainOptions: List<VillainOption> = emptyList(),
    val customAmountBb: Double = 2.0,
    val customStepBb: Double = 0.5,
    val minCustomBb: Double = 1.0,
    val maxCustomBb: Double = 100.0,
    val cardsNeeded: Int = 2,
    val pickerRank: Int? = null,
    val usedCards: Set<Int> = emptySet(),
    val canUndo: Boolean = false,
    val isComputing: Boolean = false,
    val error: String? = null,
    val history: List<String> = emptyList(),
) {
    /** All options with their Nash frequencies (alias for the oracle chips). */
    val actions: List<ActionOption> get() = options
    val maxEvAction: ActionOption? get() = options.firstOrNull { it.isMaxEv }
    val heroToAct: Boolean get() = toAct != null && toAct == heroPosition
}
