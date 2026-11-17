package com.solverin.engine.pipeline

import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.FlopNodeLocks
import com.solverin.engine.model.NodeLocks
import com.solverin.engine.river.RiverNodeLocks
import com.solverin.engine.turn.TurnNodeLocks

enum class Street { PREFLOP, FLOP, TURN, RIVER }

enum class HandStatus { IN_PROGRESS, FOLDED, ALL_IN_RUNOUT, SHOWDOWN }

/** Players of the hand: preflop opener, the player who responded (caller / 3-bettor) and an optional third (BB overcall). */
enum class Seat { OPENER, RESPONDER, THIRD }

/** Preflop line resolved by [com.solverin.engine.preflop.PreflopEngine] (no injected ranges). */
enum class PreflopLine {
    /** Opener raises first in, responder flats (VS_RFI call). Initiative: opener. */
    RFI_CALL,
    /** Opener raises, responder 3-bets, opener calls (VS_3BET call). Initiative: responder. */
    THREE_BET_CALL,
    /** Opener raises, responder cold-calls, BB overcalls (SQUEEZE node call). Initiative: opener. 3-way. */
    RFI_COLD_CALL_BB_CALL,
    /**
     * Limpers in front: the opener (hero) isolates via [com.solverin.engine.preflop.IsoRaiseInterceptor], the responder
     * (one of the limpers) calls, the other limpers fold their dead 1bb. Initiative: opener.
     */
    ISO_RAISE_CALL,
}

data class PreflopAction(val line: PreflopLine, val opener: Position, val responder: Position, val limpers: List<Position> = emptyList()) {
    val third: Position? get() = if (line == PreflopLine.RFI_COLD_CALL_BB_CALL) Position.BB else null
    init {
        require(opener != responder) { "opener == responder" }
        if (line == PreflopLine.ISO_RAISE_CALL) {
            require(limpers.isNotEmpty() && responder in limpers && opener !in limpers) { "ISO_RAISE_CALL: the responder must be one of the limpers" }
            require(limpers.none { it.isBlind } && limpers.all { it.ordinal < opener.ordinal }) { "Limpers act before the isolating player" }
        } else require(limpers.isEmpty()) { "Limpers only with ISO_RAISE_CALL" }
        if (line == PreflopLine.RFI_COLD_CALL_BB_CALL) require(responder != Position.BB && opener != Position.BB) { "3-way line needs the BB as third player" }
    }
}

/** Postflop street action sequence. */
enum class StreetKind { BET_CALL, CHECK_CHECK, RAISE_CALL, MULTIWAY_BET_CALL, BET_FOLD }

/**
 * One street's action: [bettor] bets (and for RAISE_CALL the other main player raises, the bettor calls);
 * [betFraction] = pot fraction actually bet (null = the bettor's solved size).
 */
data class StreetScript(val kind: StreetKind, val bettor: Seat? = null, val betFraction: Double? = null) {
    init {
        require((kind == StreetKind.CHECK_CHECK) == (bettor == null)) { "CHECK_CHECK has no bettor; every other line needs one" }
        require(betFraction == null || (betFraction.isFinite() && betFraction > 0.0)) { "betFraction must be > 0" }
    }
}

/** Hero's terminal river decision: hero acts (bet / check node) or faces villain's bet of [villainBetFraction] pot. */
data class RiverScript(val villainBetFraction: Double? = null, val bettor: Seat? = null)

/** Node locks per street (Max Exploit from preflop to river). */
data class HandLocks(
    val preflop: NodeLocks = NodeLocks.NONE,
    val flop: FlopNodeLocks = FlopNodeLocks.NONE,
    val turn: TurnNodeLocks = TurnNodeLocks.NONE,
    val river: RiverNodeLocks = RiverNodeLocks.NONE,
)

/**
 * Full hand script: preflop line, hero seat and hole cards, board cards and the action of every street.
 * Missing streets (null turn / river script) end the hand at that street.
 */
data class HandScript(
    val stake: Stake,
    val preflop: PreflopAction,
    val heroSeat: Seat,
    val heroHand: String,
    val flop: String,
    val turn: String? = null,
    val river: String? = null,
    val flopAction: StreetScript? = null,
    val turnAction: StreetScript? = null,
    val riverDecision: RiverScript = RiverScript(),
    val stackBb: Double = 100.0,
    val locks: HandLocks = HandLocks(),
) {
    init {
        require(stackBb.isFinite() && stackBb > 0) { "Stack must be > 0" }
        require(heroSeat != Seat.THIRD || preflop.third != null) { "THIRD seat needs a 3-way line" }
        require(preflop.line != PreflopLine.ISO_RAISE_CALL || heroSeat == Seat.OPENER) { "ISO_RAISE_CALL: hero is the isolating player" }
        require(flop.length == 6 && (turn == null || turn.length == 2) && (river == null || river.length == 2)) { "Board cards as 'AsKd7c', 'Th', '2c'" }
    }
}
