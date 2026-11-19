package com.solverin.engine.math

/**
 * Suicidal-jam guard: an all-in with less than [BLUFF_MAX_EQUITY] showdown equity vs the calling range and no draw
 * ([SEMI_BLUFF_OUTS]) needs the villain to fold at least [MIN_FOLD] of the time; against a locked station / maniac whose range collapsed below
 * [COLLAPSED_COMBOS] combos no bluff jam under [READ_BLUFF_MAX_EQUITY] is allowed at all (his fold rate is not
 * evidence on a range that small).
 */
object JamGuard {
    const val BLUFF_MAX_EQUITY = 0.25f
    const val MIN_FOLD = 0.35
    const val READ_BLUFF_MAX_EQUITY = 0.25f
    const val COLLAPSED_COMBOS = 1.5
    /** Flop / turn draws with at least this many outs are semi-bluffs, not suicidal jams. */
    const val SEMI_BLUFF_OUTS = 8

    fun suicidal(allIn: Boolean, equity: Float, pFold: Double, outs: Int = 0): Boolean =
        allIn && equity < BLUFF_MAX_EQUITY && pFold < MIN_FOLD && outs < SEMI_BLUFF_OUTS

    fun blockedVsRead(allIn: Boolean, equity: Float, stationOrManiac: Boolean, villainCombos: Double): Boolean =
        allIn && stationOrManiac && villainCombos < COLLAPSED_COMBOS && equity < READ_BLUFF_MAX_EQUITY
}
