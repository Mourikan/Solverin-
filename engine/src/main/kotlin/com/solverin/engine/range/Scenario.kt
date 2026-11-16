package com.solverin.engine.range

/**
 * Preflop decision nodes.
 * raise = the aggressive option at the node (open / 3bet / 4bet / 5bet-jam / squeeze).
 */
enum class Scenario {
    /** Raise first in. villain = BB (default defender). */
    RFI,
    /** Facing a single open. villain = opener. raise = 3bet. */
    VS_RFI,
    /** Opener facing a 3bet. villain = 3bettor. raise = 4bet. */
    VS_3BET,
    /** 3bettor facing a 4bet. villain = 4bettor. raise = 5bet jam. */
    VS_4BET,
    /** Facing open + cold call(s). villain = opener, caller = first caller. raise = squeeze. */
    SQUEEZE;

    companion object {
        private val VALUES = entries.toTypedArray()
        fun of(ordinal: Int) = VALUES[ordinal]
    }
}
