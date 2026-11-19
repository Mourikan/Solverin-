package com.solverin.app.presentation

import com.solverin.engine.core.Position

/** Sub-state of the sequential hand flow (drives Zone 3). */
enum class StreetFlowPhase { PICK_HERO_CARDS, PICK_HERO_POSITION, VILLAIN_ACTION_STEPPER, DEAL_BOARD_CARDS, HAND_COMPLETE }

/** Street of the live table (SHOWDOWN once the river betting closes). */
enum class TableStreet { PREFLOP, FLOP, TURN, RIVER, SHOWDOWN }

enum class ActKind { FOLD, CHECK, CALL, LIMP, BET, RAISE, ALL_IN }

/** One seat of the 6-max table. Amounts in bb; [streetInvest] = chips put in on the current street. */
data class SeatState(
    val position: Position,
    val inHand: Boolean = true,
    val allIn: Boolean = false,
    val streetInvest: Double = 0.0,
    val totalInvest: Double = 0.0,
    val lastLabel: String? = null,
)

/** A registered table action; [toAmount] = the player's street total after the action. */
data class ActionRecord(
    val street: TableStreet,
    val position: Position,
    val kind: ActKind,
    val toAmount: Double,
    val raiseLevel: Int,
    val isHero: Boolean,
    /** True for an aggressive action (bet / raise / all-in that raised the street maximum). */
    val aggressive: Boolean,
)

/** A legal one-tap action for the player to act. */
@androidx.compose.runtime.Immutable
data class VillainOption(val label: String, val kind: ActKind, val toAmount: Double)

/**
 * Immutable live-table state of one hand: hero cards / seat, board, every seat's investments, whose turn it is and
 * the betting round bookkeeping. All transitions are pure functions of [StreetFlowStateMachine].
 */
data class FlowState(
    val phase: StreetFlowPhase = StreetFlowPhase.PICK_HERO_CARDS,
    val street: TableStreet = TableStreet.PREFLOP,
    val heroCards: List<Int> = emptyList(),
    val heroPosition: Position? = null,
    val board: List<Int> = emptyList(),
    val seats: List<SeatState> = Position.entries.map { SeatState(it) },
    val toAct: Position? = null,
    val acted: Set<Position> = emptySet(),
    val maxBet: Double = 0.0,
    val lastRaise: Double = 0.0,
    val raiseLevel: Int = 0,
    val potStart: Double = 0.0,
    /** Pot at the start of each postflop street (bet fractions for the engine). */
    val streetPotStart: Map<TableStreet, Double> = emptyMap(),
    val history: List<ActionRecord> = emptyList(),
    /** Starting stack of every seat (bb), indexed by [Position.ordinal]. */
    val stacks: List<Double> = List(6) { 100.0 },
) {
    fun seat(p: Position): SeatState = seats[p.ordinal]
    fun startStack(p: Position): Double = stacks[p.ordinal]
    val potBb: Double get() = potStart + seats.sumOf { it.streetInvest }
    fun stackLeft(p: Position): Double = (startStack(p) - seat(p).totalInvest).coerceAtLeast(0.0)
    /** Hero's effective starting stack: min(hero, deepest opponent still in the hand). */
    val stackBb: Double get() {
        val h = heroPosition ?: return stacks.max()
        val deepest = Position.entries.filter { it != h && seat(it).inHand }.maxOfOrNull { startStack(it) } ?: startStack(h)
        return minOf(startStack(h), deepest)
    }
    /** Stack-to-pot ratio of the current street (effective stack behind / pot). */
    val spr: Double get() = if (potBb <= 0.0) Double.POSITIVE_INFINITY else effectiveStackBb / potBb
    fun toCall(p: Position): Double = (maxBet - seat(p).streetInvest).coerceAtLeast(0.0)
    val inHand: List<Position> get() = seats.filter { it.inHand }.map { it.position }
    val heroFolded: Boolean get() = heroPosition != null && !seat(heroPosition).inHand
    val effectiveStackBb: Double get() {
        val live = inHand.map { stackLeft(it) }.sortedDescending()
        return if (live.size >= 2) live[1] else live.firstOrNull() ?: 0.0
    }
    /** Board cards the current deal step still needs (3 for the flop, 1 for turn / river). */
    val cardsNeeded: Int get() = when {
        phase == StreetFlowPhase.PICK_HERO_CARDS -> 2 - heroCards.size
        phase != StreetFlowPhase.DEAL_BOARD_CARDS -> 0
        board.size < 3 -> 3 - board.size
        else -> 1
    }
    val usedCards: Set<Int> get() = (heroCards + board).toSet()
    fun streetRecords(s: TableStreet): List<ActionRecord> = history.filter { it.street == s }
}

/**
 * 6-max betting-round state machine: blinds, preflop order UTG → BB, postflop order SB → BTN, fold / check / call /
 * limp / bet / raise / all-in with min-raise rules, unlimited raise levels, street closing when every live player
 * not all-in has acted and matched the street maximum, board dealing gated on a closed street, all-in run-outs.
 */
object StreetFlowStateMachine {
    const val SB_BLIND = 0.5
    const val BB_BLIND = 1.0

    private val PREFLOP_ORDER = listOf(Position.UTG, Position.HJ, Position.CO, Position.BTN, Position.SB, Position.BB)
    private val POSTFLOP_ORDER = listOf(Position.SB, Position.BB, Position.UTG, Position.HJ, Position.CO, Position.BTN)

    fun newHand(stackBb: Double): FlowState = FlowState(stacks = List(6) { stackBb })

    /** Replaces starting stacks (never below what a seat already invested); street flags are recomputed. */
    fun withStacks(s: FlowState, stacks: List<Double>): FlowState {
        require(stacks.size == 6 && stacks.all { it.isFinite() && it >= 1.0 }) { "Stacks must be >= 1bb" }
        val fixed = stacks.mapIndexed { i, v -> maxOf(v, s.seats[i].totalInvest) }
        val seats = s.seats.mapIndexed { i, seat -> seat.copy(allIn = seat.inHand && s.phase != StreetFlowPhase.PICK_HERO_CARDS &&
            s.phase != StreetFlowPhase.PICK_HERO_POSITION && fixed[i] - seat.totalInvest <= 1e-9) }
        return s.copy(stacks = fixed, seats = seats)
    }

    fun pickHeroCard(s: FlowState, card: Int): FlowState {
        require(s.phase == StreetFlowPhase.PICK_HERO_CARDS) { "Hole cards are already set" }
        require(card in 0..51 && card !in s.heroCards) { "Card not available" }
        val cards = s.heroCards + card
        return s.copy(heroCards = cards, phase = if (cards.size == 2) StreetFlowPhase.PICK_HERO_POSITION else s.phase)
    }

    /** Seats hero, posts the blinds and opens the preflop stepper at UTG. */
    fun setHeroPosition(s: FlowState, p: Position): FlowState {
        require(s.phase == StreetFlowPhase.PICK_HERO_POSITION) { "Pick the hole cards first" }
        val seats = Position.entries.map {
            val blind = when (it) { Position.SB -> SB_BLIND; Position.BB -> BB_BLIND; else -> 0.0 }
            SeatState(it, streetInvest = blind, totalInvest = blind, lastLabel = if (blind > 0) "Blind ${fmt(blind)}" else null)
        }
        return s.copy(
            phase = StreetFlowPhase.VILLAIN_ACTION_STEPPER, street = TableStreet.PREFLOP, heroPosition = p, seats = seats,
            toAct = PREFLOP_ORDER.first(), acted = emptySet(), maxBet = BB_BLIND, lastRaise = BB_BLIND, raiseLevel = 0, potStart = 0.0,
        )
    }

    private fun order(street: TableStreet) = if (street == TableStreet.PREFLOP) PREFLOP_ORDER else POSTFLOP_ORDER

    private fun needsAction(s: FlowState, p: Position): Boolean {
        val seat = s.seat(p)
        if (!seat.inHand || seat.allIn || s.stackLeft(p) <= 0.0) return false
        return p !in s.acted || seat.streetInvest < s.maxBet - 1e-9
    }

    private fun nextToAct(s: FlowState, after: Position?): Position? {
        val ord = order(s.street)
        val start = after?.let { ord.indexOf(it) + 1 } ?: 0
        for (i in 0 until ord.size) {
            val p = ord[(start + i) % ord.size]
            if (needsAction(s, p)) return p
        }
        return null
    }

    /** Legal minimum street total for a raise. */
    fun minRaiseTo(s: FlowState): Double = if (s.maxBet <= 0.0) BB_BLIND else s.maxBet + maxOf(s.lastRaise, BB_BLIND)

    /** Applies [kind] for the player to act; [toAmount] = street total for bets / raises. */
    fun act(s: FlowState, kind: ActKind, toAmount: Double = 0.0, isHero: Boolean = false): FlowState {
        require(s.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER) { "No betting round in progress" }
        val p = requireNotNull(s.toAct) { "Nobody to act" }
        val seat = s.seat(p)
        val left = s.stackLeft(p)
        val toCall = s.toCall(p)
        // A short all-in that does not exceed the street maximum is an all-in call.
        if (kind == ActKind.ALL_IN && seat.streetInvest + left <= s.maxBet + 1e-9 && toCall > 1e-9) return act(s, ActKind.CALL, isHero = isHero)
        var maxBet = s.maxBet; var lastRaise = s.lastRaise; var level = s.raiseLevel
        var acted = s.acted + p
        val newSeat: SeatState
        var aggressive = false
        val recordAmount: Double
        when (kind) {
            ActKind.FOLD -> { newSeat = seat.copy(inHand = false, lastLabel = "Folded"); recordAmount = seat.streetInvest }
            ActKind.CHECK -> {
                require(toCall <= 1e-9) { "Cannot check facing ${fmt(toCall)}bb" }
                newSeat = seat.copy(lastLabel = "Check"); recordAmount = seat.streetInvest
            }
            ActKind.CALL, ActKind.LIMP -> {
                require(toCall > 1e-9) { "Nothing to call" }
                val add = minOf(toCall, left)
                val allIn = add >= left - 1e-9
                newSeat = seat.copy(streetInvest = seat.streetInvest + add, totalInvest = seat.totalInvest + add, allIn = allIn,
                    lastLabel = when { allIn -> "All-In"; kind == ActKind.LIMP -> "Limp ${fmt(seat.streetInvest + add)}"; else -> "Call ${fmt(seat.streetInvest + add)}" })
                recordAmount = newSeat.streetInvest
            }
            ActKind.BET, ActKind.RAISE, ActKind.ALL_IN -> {
                val cap = seat.streetInvest + left
                val target = if (kind == ActKind.ALL_IN) cap else minOf(toAmount, cap)
                require(target > s.maxBet + 1e-9) { "A ${kind.name.lowercase()} must exceed ${fmt(s.maxBet)}bb" }
                require(target >= minRaiseTo(s) - 1e-9 || target >= cap - 1e-9) { "Minimum is ${fmt(minRaiseTo(s))}bb (or all-in)" }
                val add = target - seat.streetInvest
                val allIn = target >= cap - 1e-9
                val increment = target - s.maxBet
                if (increment >= lastRaise - 1e-9 || s.maxBet <= 0.0) { lastRaise = maxOf(increment, BB_BLIND); acted = setOf(p) } else acted = acted
                maxBet = target; level += 1; aggressive = true
                newSeat = seat.copy(streetInvest = target, totalInvest = seat.totalInvest + add, allIn = allIn,
                    lastLabel = if (allIn) "All-In ${fmt(target)}" else "${raiseName(s.street, level, s.maxBet <= 0.0)} ${fmt(target)}")
                recordAmount = target
            }
        }
        val record = ActionRecord(s.street, p, if (aggressive && kind != ActKind.ALL_IN) (if (s.maxBet <= 0.0) ActKind.BET else ActKind.RAISE) else kind,
            recordAmount, level, isHero, aggressive)
        val seats = s.seats.toMutableList().also { it[p.ordinal] = newSeat }
        var next = s.copy(seats = seats, maxBet = maxBet, lastRaise = lastRaise, raiseLevel = level, acted = acted, history = s.history + record)
        if (next.heroFolded || next.inHand.size <= 1) return next.copy(phase = StreetFlowPhase.HAND_COMPLETE, toAct = null)
        val n = nextToAct(next, p)
        return if (n != null) next.copy(toAct = n) else closeStreet(next)
    }

    /** Implicit pass of a villain that was not assigned an action: check when nothing is owed, otherwise fold. */
    private fun implicitPass(s: FlowState): FlowState {
        val p = requireNotNull(s.toAct)
        return act(s, if (s.toCall(p) <= 1e-9) ActKind.CHECK else ActKind.FOLD)
    }

    /**
     * Auto-fold by omission: every villain between the pointer and [target] passes (fold, or check when nothing is
     * owed). Null when hero acts before [target], or [target] cannot act on this street.
     */
    fun foldUntil(s: FlowState, target: Position): FlowState? {
        if (target == s.heroPosition) return null
        var c = s
        repeat(MAX_IMPLICIT_STEPS) {
            if (c.phase != StreetFlowPhase.VILLAIN_ACTION_STEPPER) return null
            val p = c.toAct ?: return null
            if (p == target) return c
            if (p == c.heroPosition) return null
            c = implicitPass(c)
        }
        return null
    }

    /** Remaining villains pass by omission until it is hero's turn, the street closes or the hand ends. */
    fun foldVillainsUntilHero(s: FlowState): FlowState {
        var c = s
        repeat(MAX_IMPLICIT_STEPS) {
            if (c.phase != StreetFlowPhase.VILLAIN_ACTION_STEPPER) return c
            val p = c.toAct ?: return c
            if (p == c.heroPosition) return c
            c = implicitPass(c)
        }
        return c
    }

    /**
     * Auto-fold by omission in raised pots: villains with no voluntary action on this street pass until hero's turn;
     * it stops at a villain who already put chips in voluntarily (that answer must be entered) or when the round ends.
     */
    fun foldNonParticipantsUntilHero(s: FlowState): FlowState {
        var c = s
        repeat(MAX_IMPLICIT_STEPS) {
            if (c.phase != StreetFlowPhase.VILLAIN_ACTION_STEPPER) return c
            val p = c.toAct ?: return c
            if (p == c.heroPosition) return c
            if (c.streetRecords(c.street).any { it.position == p }) return c
            c = implicitPass(c)
        }
        return c
    }

    /** True when [p] is still to act on the current street (the matrix can assign it an action). */
    fun canStillAct(s: FlowState, p: Position): Boolean =
        s.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER && needsAction(s, p)

    private const val MAX_IMPLICIT_STEPS = 64

    private fun closeStreet(s: FlowState): FlowState {
        val pot = s.potBb
        val seats = s.seats.map { it.copy(streetInvest = 0.0) }
        val closed = s.copy(seats = seats, potStart = pot, maxBet = 0.0, lastRaise = 0.0, raiseLevel = 0, acted = emptySet(), toAct = null)
        return if (s.street == TableStreet.RIVER) closed.copy(street = TableStreet.SHOWDOWN, phase = StreetFlowPhase.HAND_COMPLETE)
        else closed.copy(phase = StreetFlowPhase.DEAL_BOARD_CARDS)
    }

    /** Deals one board card; opens the next street's stepper once the street's cards are complete. */
    fun dealCard(s: FlowState, card: Int): FlowState {
        require(s.phase == StreetFlowPhase.DEAL_BOARD_CARDS) { "The ${s.street} betting round is not closed yet" }
        require(card in 0..51 && card !in s.usedCards) { "Card not available" }
        val board = s.board + card
        val street = when (board.size) { 3 -> TableStreet.FLOP; 4 -> TableStreet.TURN; 5 -> TableStreet.RIVER; else -> null }
        if (street == null) return s.copy(board = board)
        var next = s.copy(board = board, street = street, phase = StreetFlowPhase.VILLAIN_ACTION_STEPPER,
            streetPotStart = s.streetPotStart + (street to s.potBb), acted = emptySet(),
            seats = s.seats.map { if (it.inHand && !it.allIn) it.copy(lastLabel = null) else it })
        val first = nextToAct(next, null)
        val bettors = next.inHand.count { !next.seat(it).allIn && next.stackLeft(it) > 0.0 }
        if (first == null || bettors <= 1) next = closeStreet(next.copy(toAct = null)) else next = next.copy(toAct = first)
        return next
    }

    /** Last aggressor of the current street, or of the previous street (initiative). */
    fun initiative(s: FlowState): Position? =
        s.history.lastOrNull { it.aggressive && it.street == s.street }?.position ?: s.history.lastOrNull { it.aggressive }?.position

    fun raiseName(street: TableStreet, level: Int, openingBet: Boolean): String = when {
        street == TableStreet.PREFLOP -> when (level) { 1 -> "Open"; 2 -> "3-Bet"; 3 -> "4-Bet"; else -> "${level + 1}-Bet" }
        openingBet || level <= 1 -> "Bet"
        level == 2 -> "Raise"
        else -> "$level-Bet"
    }

    /** One-tap legal options of the player to act (presets in bb); custom sizes go through [act] directly. */
    fun options(s: FlowState): List<VillainOption> {
        val p = s.toAct ?: return emptyList()
        if (s.phase != StreetFlowPhase.VILLAIN_ACTION_STEPPER) return emptyList()
        val seat = s.seat(p)
        val cap = seat.streetInvest + s.stackLeft(p)
        val toCall = s.toCall(p)
        val out = ArrayList<VillainOption>()
        val minTo = minRaiseTo(s)
        fun raise(label: String, to: Double) {
            if (to >= cap - 1e-9) return
            if (to >= minTo - 1e-9 && out.none { kotlin.math.abs(it.toAmount - to) < 1e-6 }) out += VillainOption("$label ${fmt(to)}", if (s.maxBet <= 0.0) ActKind.BET else ActKind.RAISE, to)
        }
        if (toCall > 1e-9) out += VillainOption("Fold", ActKind.FOLD, seat.streetInvest)
        // Stack at or below the amount owed: the only continue is an all-in call.
        val shortAllIn = toCall > 1e-9 && cap <= s.maxBet + 1e-9
        if (shortAllIn) {
            out += VillainOption("All-In ${fmt(cap)}", ActKind.CALL, cap)
            return out
        }
        if (s.street == TableStreet.PREFLOP) {
            if (toCall > 1e-9) {
                if (s.raiseLevel == 0) out += VillainOption("Limp ${fmt(s.maxBet)}", ActKind.LIMP, s.maxBet)
                else out += VillainOption("Call ${fmt(minOf(s.maxBet, cap))}", ActKind.CALL, minOf(s.maxBet, cap))
            } else out += VillainOption("Check", ActKind.CHECK, seat.streetInvest)
            val limpers = s.streetRecords(TableStreet.PREFLOP).count { it.kind == ActKind.LIMP }
            when (s.raiseLevel) {
                0 -> { raise("Min", 2.0); raise("Open", 2.2); raise("Open", 2.5); raise("Open 3x", 3.0); if (limpers > 0) raise("Iso", 3.5 + limpers) }
                1 -> { raise("3-Bet IP", s.maxBet * 3); raise("3-Bet OOP", s.maxBet * 4) }
                2 -> { raise("4-Bet", s.maxBet * 2.25); raise("4-Bet", s.maxBet * 2.5) }
                else -> raise("${s.raiseLevel + 2}-Bet", s.maxBet * 2.2)
            }
        } else {
            val pot = s.potBb
            if (toCall <= 1e-9) {
                out += VillainOption("Check", ActKind.CHECK, seat.streetInvest)
                val ini = initiative(s)
                val donk = ini != null && ini != p && s.street != TableStreet.PREFLOP &&
                    s.history.none { it.street == s.street && it.aggressive } && POSTFLOP_ORDER.indexOf(p) < POSTFLOP_ORDER.indexOf(ini)
                val name = if (donk) "Donk" else "Bet"
                raise("Min", BB_BLIND)
                for ((f, n) in listOf(0.25 to "25% Block", 0.33 to "33%", 0.5 to "50%", 0.66 to "66%", 0.75 to "75%", 1.25 to "125% Overbet", 1.5 to "150%"))
                    raise("$name $n", maxOf(BB_BLIND, round1(pot * f)))
            } else {
                out += VillainOption("Call ${fmt(minOf(s.maxBet, cap))}", ActKind.CALL, minOf(s.maxBet, cap))
                val label = if (s.raiseLevel >= 2) "${s.raiseLevel + 1}-Bet" else "Raise"
                raise("$label 2.5x", round1(s.maxBet * 2.5)); raise("$label 3x", round1(s.maxBet * 3)); raise("$label pot", round1(pot + 2 * toCall + seat.streetInvest))
            }
        }
        out += VillainOption("All-In ${fmt(cap)}", ActKind.ALL_IN, cap)
        return out
    }

    fun round1(x: Double): Double = Math.round(x * 10) / 10.0
    fun fmt(x: Double): String = if (kotlin.math.abs(x - Math.round(x)) < 1e-9) "${Math.round(x)}bb" else "%.1fbb".format(x)
}
