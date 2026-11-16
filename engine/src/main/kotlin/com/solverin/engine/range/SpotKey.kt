package com.solverin.engine.range

import com.solverin.engine.core.Position

/**
 * Packed spot identifier (no allocation on lookup).
 * bits: [0..2] scenario | [3..5] hero | [6..8] villain | [9..11] caller (7 = none)
 */
@JvmInline
value class SpotKey(val packed: Int) {
    val scenario: Scenario get() = Scenario.of(packed and 7)
    val hero: Position get() = Position.of((packed ushr 3) and 7)
    val villain: Position get() = Position.of((packed ushr 6) and 7)
    val caller: Position? get() = ((packed ushr 9) and 7).let { if (it == NONE) null else Position.of(it) }

    override fun toString(): String =
        "${scenario.name}:${hero.short}" + "_vs_${villain.short}" + (caller?.let { "+${it.short}" } ?: "")

    companion object {
        private const val NONE = 7

        fun of(scenario: Scenario, hero: Position, villain: Position = Position.BB, caller: Position? = null): SpotKey {
            require(hero != villain) { "hero == villain" }
            return SpotKey(
                scenario.ordinal or (hero.ordinal shl 3) or (villain.ordinal shl 6) or
                    ((caller?.ordinal ?: NONE) shl 9)
            )
        }
    }
}
