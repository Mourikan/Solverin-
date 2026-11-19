package com.solverin.engine.model

/**
 * NIT node-lock read: a villain locked to over-fold facing bets ([MIN_FOLD]) whose betting / continuing range is
 * value-heavy ([MIN_VALUE_SHARE]). Against this read the range that keeps calling is condensed toward strong hands,
 * so PostflopMassiveExploitOverride is closed on every street and bluffs stay under the base bluff cap.
 */
object NitRead {
    const val MIN_FOLD = 0.60
    const val MIN_VALUE_SHARE = 0.90

    fun of(folds: Iterable<Double?>, valueShares: Iterable<Double?>): Boolean =
        folds.any { it != null && it >= MIN_FOLD } && valueShares.any { it != null && it >= MIN_VALUE_SHARE }
}
