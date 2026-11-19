package com.solverin.app.presentation

import androidx.compose.runtime.Immutable
import com.solverin.engine.core.Stake

/** One hero option of the oracle: Nash frequency, exact EV (bb), EV lost vs the optimum and its table action. */
@Immutable
data class ActionOption(
    val key: String,
    val label: String,
    val frequency: Float,
    val evBb: Double,
    /** EV − Max EV (≤ 0). */
    val deltaEvBb: Double,
    val isMaxEv: Boolean,
    val kind: ActKind,
    val toAmountBb: Double,
    /** Short action name for the banner (OPEN, ISO-RAISE, 3-BET, C-BET, BARREL, OVERBET, ALL-IN, CALL, CHECK, FOLD…). */
    val shortName: String = key,
    /**
     * Explicit amount in bb: street total for bets / raises / all-in, chips to add for a call / limp, 0 for check / fold.
     */
    val amountBb: Double = 0.0,
    /** Bet / raise size as a fraction of the pot before it (0 for passive actions). */
    val potPct: Double = 0.0,
) {
    /**
     * Blinds won implicitly by taking this action (its EV), labelled so it is never read as chips to put in the pot
     * ([amountBb] is the amount to bet / pay).
     */
    val implicitEvLabel: String get() = implicitEvLabel(evBb)

    companion object {
        fun implicitEvLabel(evBb: Double): String =
            "EV impl: " + if (evBb.isFinite()) String.format(java.util.Locale.US, "%+.2fbb", evBb) else "—"
    }
}

/** One seat of the hand (strip chips and preflop matrix cards). */
@Immutable
data class SeatView(
    val label: String,
    val position: com.solverin.engine.core.Position,
    val status: String,
    /** Chips committed on the current street (bb). */
    val investBb: Double,
    /** Remaining stack (bb). */
    val stackBb: Double,
    val inHand: Boolean,
    val allIn: Boolean,
    val isHero: Boolean,
    val isToAct: Boolean,
    val isSelected: Boolean,
    /** Registered an explicit action on the current street. */
    val actedThisStreet: Boolean = false,
    /** Villain that can still be assigned an action on this street (matrix). */
    val canAct: Boolean = false,
    /** Seat whose action buttons the matrix currently shows. */
    val isMatrixSelected: Boolean = false,
)

/** Immutable UI state of the live hand (single source of truth for the Compose screen). */
@Immutable
data class HandViewState(
    val stake: Stake = Stake.NL50,
    val profile: VillainProfilePreset = VillainProfilePreset.POPULATION,
    val phase: StreetFlowPhase = StreetFlowPhase.PICK_HERO_CARDS,
    val street: TableStreet = TableStreet.PREFLOP,
    val heroCards: List<String> = emptyList(),
    val heroCardIds: List<Int> = emptyList(),
    val heroPosition: String? = null,
    val board: List<String> = emptyList(),
    val boardIds: List<Int> = emptyList(),
    val seats: List<SeatView> = emptyList(),
    val toAct: String? = null,
    /** Villain seat whose action buttons are shown (matrix selection or the seat to act). */
    val actingSeat: String? = null,
    /** "✓ Avanzar al Flop (Resto Fold)" available. */
    val canAdvancePreflop: Boolean = false,
    val potBb: Double = 0.0,
    val effectiveStackBb: Double = 100.0,
    val spr: Double = Double.POSITIVE_INFINITY,
    /** Default starting stack of the table (stack chip). */
    val tableStackBb: Double = 100.0,
    /** Seat whose stack the stack selector edits (null = whole table). */
    val selectedSeat: String? = null,
    val toCallBb: Double = 0.0,
    /** Highest street commitment the player to act must match (includes hero's assumed bet / raise). */
    val streetMaxBetBb: Double = 0.0,
    /** Hero's chips committed on the current street (assumed action included). */
    val heroCommittedBb: Double = 0.0,
    /** Explicit bb amount of the Max-EV action (see [ActionOption.amountBb]); never empty. */
    val recommendedAmountBb: Double = 0.0,
    val recommendedPotPct: Double = 0.0,
    /** EV (bb) of the recommended action: blinds won implicitly, shown small next to the dominant [recommendedAmountBb]. */
    val recommendedImplicitEvBb: Double = Double.NaN,
    /** Oracle shows hero's NEXT node (hero has not acted yet on it): its bet is not yet in front of the villains. */
    val oraclePreview: Boolean = false,
    /** Oracle options of hero's current / last decision node (Nash frequency + exact EV in bb). */
    val options: List<ActionOption> = emptyList(),
    val nodeLabel: String = "",
    val approximate: Boolean = false,
    val latencyMs: Double = 0.0,
    /** Key of hero's assumed action (auto Max-EV or manual deviation); null = preview of hero's next node. */
    val heroCommittedKey: String? = null,
    /** True while hero's assumed action can still be changed (no later input registered). */
    val heroOverridable: Boolean = false,
    /** Hero's assumed action differs from the Max-EV recommendation. */
    val manualDeviation: Boolean = false,
    /** Max-EV was FOLD: the hand stays recoverable through "Continuar en la mano" / a further villain input. */
    val heroFoldPending: Boolean = false,
    val explanation: StrategyExplanation? = null,
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
    val actions: List<ActionOption> get() = options
    val maxEvAction: ActionOption? get() = options.firstOrNull { it.isMaxEv }
    val committedAction: ActionOption? get() = options.firstOrNull { it.key == heroCommittedKey }
    val heroToAct: Boolean get() = toAct != null && toAct == heroPosition
}
