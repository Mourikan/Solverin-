package com.solverin.engine.math

import com.solverin.engine.core.RakeStructure

/**
 * Rake_model(Stake): expected rake borne by hero in a pot that sees a flop.
 *  finalPot = min(flopPot * POT_GROWTH, flopPot + 2 * behind)   (all-in pots: no growth)
 *  rakeTaken = min(rate * finalPot, cap)
 *  heroRake = eqR * rakeTaken   (rake comes out of the pot hero wins)
 * No flop, no drop: pots won preflop are unraked.
 */
object RakeModel {
    const val POT_GROWTH = 1.8

    fun finalPot(flopPot: Double, behind: Double): Double =
        minOf(flopPot * POT_GROWTH, flopPot + 2 * behind.coerceAtLeast(0.0))

    fun rakeTaken(rake: RakeStructure, flopPot: Double, behind: Double): Double =
        rake.rakeBb(finalPot(flopPot, behind))

    fun heroRake(rake: RakeStructure, flopPot: Double, behind: Double, eqR: Double): Double =
        eqR * rakeTaken(rake, flopPot, behind)
}
