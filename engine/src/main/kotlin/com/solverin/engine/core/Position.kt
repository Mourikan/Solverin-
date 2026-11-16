package com.solverin.engine.core

/** 6-max positions in preflop action order. */
enum class Position(val short: String) {
    UTG("UTG"), HJ("HJ"), CO("CO"), BTN("BTN"), SB("SB"), BB("BB");

    val isBlind get() = this == SB || this == BB
    val isLate get() = this == CO || this == BTN || this == SB

    /** Postflop acting order: SB(0), BB(1), UTG(2) .. BTN(5). Higher acts later. */
    val postflopOrder: Int get() = when (this) {
        SB -> 0; BB -> 1; UTG -> 2; HJ -> 3; CO -> 4; BTN -> 5
    }

    /** Blind posted (bb). */
    val blindBb: Double get() = when (this) { SB -> 0.5; BB -> 1.0; else -> 0.0 }

    fun isInPositionVs(other: Position): Boolean = postflopOrder > other.postflopOrder

    companion object {
        private val VALUES = entries.toTypedArray()
        fun of(ordinal: Int) = VALUES[ordinal]
    }
}
