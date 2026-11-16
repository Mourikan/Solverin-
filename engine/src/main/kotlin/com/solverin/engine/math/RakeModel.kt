package com.solverin.engine.math

import com.solverin.engine.core.RakeStructure

/**
 * Rake_model(Stake) = min(Pot_postflop_avg · %Rake_stake, Cap_bb(Stake))
 *   Pot_postflop_avg = min(flopPot · POT_GROWTH, flopPot + 2 · stack behind)   (all-in: flopPot)
 * No flop, no drop: pots won preflop are never raked.
 *
 * Attribution in the master equation:
 *   FULL          − Rake_model                  (hero charged the whole rake: maximum micro-stakes penalty)
 *   WINNER_SHARE  − EQ·R · Rake_model           (hero pays rake only on the share of pots he wins)
 */
enum class RakeAttribution { FULL, WINNER_SHARE }

object RakeModel {
    const val POT_GROWTH = 1.8

    fun postflopAvgPot(flopPot: Double, behind: Double): Double =
        minOf(flopPot * POT_GROWTH, flopPot + 2 * behind.coerceAtLeast(0.0))

    fun rakeModel(rake: RakeStructure, flopPot: Double, behind: Double): Double =
        minOf(postflopAvgPot(flopPot, behind) * rake.rate, rake.capBb)

    fun charge(rake: RakeStructure, attribution: RakeAttribution, flopPot: Double, behind: Double, eqR: Double): Double {
        val r = rakeModel(rake, flopPot, behind)
        return when (attribution) {
            RakeAttribution.FULL -> r
            RakeAttribution.WINNER_SHARE -> eqR * r
        }
    }
}
