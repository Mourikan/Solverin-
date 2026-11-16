package com.solverin.engine.range

/**
 * Preflop decision nodes (3 bits in [SpotKey]).
 * raise = the aggressive option at the node.
 */
enum class Scenario {
    /** Raise first in. villain = BB. raise = open. */
    RFI,
    /** Facing a single open. villain = opener. raise = 3bet. */
    VS_RFI,
    /** Opener facing a 3bet. villain = 3bettor. raise = 4bet. */
    VS_3BET,
    /** 3bettor facing a 4bet. villain = opener/4bettor; caller != null => hero squeezed over caller. raise = jam. */
    VS_4BET,
    /** Facing open + cold call. villain = opener, caller = cold caller. raise = squeeze. */
    SQUEEZE,
    /** Opener facing a squeeze. villain = squeezer, caller = cold caller. raise = 4bet. */
    VS_SQUEEZE_OPENER,
    /** Cold caller facing a squeeze (opener folded). villain = squeezer, caller = opener. raise = 4bet. */
    VS_SQUEEZE_CALLER,
    /** Opener who 4bet facing a 5bet jam. villain = 3bettor. call / fold only. */
    VS_5BET;

    companion object {
        private val VALUES = entries.toTypedArray()
        fun of(ordinal: Int) = VALUES[ordinal]
    }
}
