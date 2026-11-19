package com.solverin.engine.math

/**
 * Strict MDF floor vs min-bets / weakness donks: folds above [maxFold] are turned into calls, best call EV first.
 * A hand whose call loses more than [MAX_LOSS_SHARE] of the amount to call and more than [SMALL_LOSS_BB] (no real
 * equity vs the betting range) is never forced in: defending the MDF protects against bluffs, it does not pay off value
 * with air.
 */
object MdfFloor {
    const val MAX_LOSS_SHARE = 0.5
    /** Micro / min-bets: a call losing at most this much (bb) stays defendable (the floor's purpose vs 1 bb stabs). */
    const val SMALL_LOSS_BB = 1.5

    /** Equity below this vs the betting range is dead: never forced to call, whatever the bet size. */
    const val MIN_LIVE_EQUITY = 0.02f

    /**
     * Min-bets: every hand with live equity defends (its loss is bounded by the small call). Other floors (weakness
     * donks): the call must not lose more than max(MAX_LOSS_SHARE × call, SMALL_LOSS_BB).
     */
    fun eligible(callEv: Double, toCall: Double, equity: Float, minBet: Boolean): Boolean =
        callEv.isFinite() && equity >= MIN_LIVE_EQUITY && (minBet || callEv >= -maxOf(MAX_LOSS_SHARE * toCall, SMALL_LOSS_BB))

    /** Applies the floor in place; returns the fold ceiling actually reached (≥ [maxFold] when hands were ineligible). */
    fun enforce(
        heroIdx: List<Int>, w: FloatArray, maxFold: Double, fold: FloatArray, call: FloatArray, toCall: Double, minBet: Boolean,
        equity: (Int) -> Float, callEv: (Int) -> Double,
    ): Double {
        var total = 0.0; var folded = 0.0
        for (h in heroIdx) { total += w[h]; folded += w[h] * fold[h] }
        if (total <= 0.0) return maxFold
        var excess = folded - maxFold * total
        if (excess <= 1e-12) return maxFold
        for (h in heroIdx.filter { fold[it] > 0f && eligible(callEv(it), toCall, equity(it), minBet) }.sortedByDescending(callEv)) {
            if (excess <= 1e-12) break
            val cut = minOf(fold[h].toDouble() * w[h], excess)
            val moved = minOf(fold[h], (cut / w[h]).toFloat())
            fold[h] -= moved; call[h] += moved
            excess -= cut
        }
        var after = 0.0
        for (h in heroIdx) after += w[h] * fold[h]
        return maxOf(maxFold, after / total)
    }
}
