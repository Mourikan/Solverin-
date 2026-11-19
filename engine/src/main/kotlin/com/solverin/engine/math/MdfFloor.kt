package com.solverin.engine.math

/**
 * Strict MDF floor vs min-bets / weakness donks: folds above [maxFold] are turned into calls, best call EV first.
 * A hand without live equity, or whose call loses more than [MAX_FORCED_LOSS_BB] (or [MAX_LOSS_SHARE] of a non-min-bet
 * call), is never forced in: defending the MDF protects against bluffs, it does not pay off value with air.
 */
object MdfFloor {
    const val MAX_LOSS_SHARE = 0.5
    /** Largest loss (bb) a call forced by the floor may carry. */
    const val MAX_FORCED_LOSS_BB = 1.5

    /** Equity below this vs the betting range is dead: never forced to call, whatever the bet size. */
    const val MIN_LIVE_EQUITY = 0.02f

    /**
     * A hand is defended by the floor only with live equity and a bounded cost: min-bets accept a loss up to
     * MAX_FORCED_LOSS_BB (the price of a micro stab), other floors (weakness donks) up to
     * min(MAX_LOSS_SHARE × call, MAX_FORCED_LOSS_BB). Never a destructive forced call.
     */
    fun eligible(callEv: Double, toCall: Double, equity: Float, minBet: Boolean): Boolean =
        callEv.isFinite() && equity >= MIN_LIVE_EQUITY &&
            callEv >= -(if (minBet) MAX_FORCED_LOSS_BB else minOf(MAX_LOSS_SHARE * toCall, MAX_FORCED_LOSS_BB))

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
