package com.solverin.engine.range

import com.solverin.engine.core.Position

/**
 * Packed spot identifier (no allocation on lookup).
 * bits: [0..3] scenario | [4..6] hero | [7..9] villain | [10..12] caller (7 = none)
 */
@JvmInline
value class SpotKey(val packed: Int) {
    val scenario: Scenario get() = Scenario.of(packed and 15)
    val hero: Position get() = Position.of((packed ushr 4) and 7)
    val villain: Position get() = Position.of((packed ushr 7) and 7)
    val caller: Position? get() = ((packed ushr 10) and 7).let { if (it == NONE) null else Position.of(it) }

    override fun toString(): String =
        "${scenario.name}:${hero.short}" + "_vs_${villain.short}" + (caller?.let { "+${it.short}" } ?: "")

    companion object {
        private const val NONE = 7

        fun of(scenario: Scenario, hero: Position, villain: Position = Position.BB, caller: Position? = null): SpotKey {
            require(hero != villain) { "hero == villain" }
            require(Scenario.entries.size <= 16)
            return SpotKey(
                scenario.ordinal or (hero.ordinal shl 4) or (villain.ordinal shl 7) or
                    ((caller?.ordinal ?: NONE) shl 10)
            )
        }

        /** Converts a key packed with the v1 layout (3-bit scenario). */
        fun fromV1(packed: Int): SpotKey = SpotKey(
            (packed and 7) or (((packed ushr 3) and 7) shl 4) or (((packed ushr 6) and 7) shl 7) or
                (((packed ushr 9) and 7) shl 10)
        )
    }
}
