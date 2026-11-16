package com.solverin.engine.flop

/**
 * Range consistency for hero's aggressive flop actions: bluffs (equity < 50% vs villain's calling range and
 * not in the nut tier) are capped at valueCombos × ratio. Villain's fold rates are measured vs normal
 * frequencies; without the cap any over-folding profile would make every bet / raise profitable.
 *
 *  ratio = base(action, size) × (villainFold / (1 − MDF))², clamped to [0.25, 3]
 *
 * Bluffs compete for the budget by EV surplus over their best non-aggressive option; removed bluffs fall back
 * to that option (check / call, or fold when allowed).
 */
object FlopBluffCap {
    fun base(raise: Boolean, sizeFraction: Double): Double = when {
        raise -> 0.6
        sizeFraction <= 0.4 -> 1.5
        else -> 1.0
    }

    fun ratio(raise: Boolean, sizeFraction: Double, villainFold: Double, mdf: Double): Double {
        val ref = (1 - mdf).coerceAtLeast(0.05)
        return (base(raise, sizeFraction) * (villainFold / ref).let { it * it }).coerceIn(0.25, 3.0)
    }

    /**
     * [agg] / [passive] / [fold] frequencies are mutated; returns the combos whose aggressive frequency was cut. [aggEv], [passiveEv] per combo; [isValue] per combo;
     * [foldAllowed] false forbids falling back to fold.
     */
    fun apply(
        heroIdx: List<Int>, weights: FloatArray, ratio: Double,
        agg: FloatArray, passive: FloatArray, fold: FloatArray?,
        aggEv: (Int) -> Double, passiveEv: (Int) -> Double, isValue: (Int) -> Boolean, foldAllowed: (Int) -> Boolean,
    ): BooleanArray {
        val capped = BooleanArray(Combos.COUNT)
        var value = 0.0
        val bluffs = ArrayList<Int>()
        for (h in heroIdx) {
            if (agg[h] <= 0f) continue
            if (isValue(h)) value += agg[h] * weights[h] else bluffs += h
        }
        var budget = value * ratio
        bluffs.sortByDescending { aggEv(it) - maxOf(if (foldAllowed(it) && fold != null) 0.0 else Double.NEGATIVE_INFINITY, passiveEv(it)) }
        for (h in bluffs) {
            val w = agg[h] * weights[h]
            val keep = when {
                budget >= w -> agg[h]
                budget > 0 -> (budget / weights[h]).toFloat()
                else -> 0f
            }
            budget -= keep * weights[h]
            val removed = agg[h] - keep
            if (removed <= 0f) continue
            capped[h] = true
            agg[h] = keep
            val toPassive = fold == null || !foldAllowed(h) || passiveEv(h) >= 0.0
            if (toPassive) passive[h] += removed else fold!![h] += removed
        }
        return capped
    }
}
