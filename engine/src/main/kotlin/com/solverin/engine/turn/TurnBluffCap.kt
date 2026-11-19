package com.solverin.engine.turn

import com.solverin.engine.flop.Combos

/**
 * Turn range consistency: bluffs (equity < 50% vs villain's continuing range, not nut) are capped at
 * value × ratio, ratio = base(size) × (villainFold / (1 − MDF))², base from bluffBaseMin (33%) to
 * bluffBaseMax (150%) — raises use bluffBaseMin. Clamped to [0.2, maxBluffRatio], or [0.2, maxBluffRatioLocked]
 * when villain's fold is node-locked (a read: max exploitation).
 * Priority: semi-bluffs (≥ 8 clean outs) and nut blockers (BF < 1) take the budget first, then EV surplus.
 * Calling station (locked fold ≤ stationFold): pure bluffs (non-priority) get no budget at all.
 */
object TurnBluffCap {
    fun base(raise: Boolean, sizeFraction: Double, p: TurnPolicy): Double {
        if (raise) return p.bluffBaseMin
        val t = ((sizeFraction - p.smallBet) / (p.overbetHigh - p.smallBet)).coerceIn(0.0, 1.0)
        return p.bluffBaseMin + (p.bluffBaseMax - p.bluffBaseMin) * t
    }

    fun ratio(raise: Boolean, sizeFraction: Double, villainFold: Double, mdf: Double, locked: Boolean, p: TurnPolicy): Double {
        val ref = (1 - mdf).coerceAtLeast(0.05)
        val r = base(raise, sizeFraction, p) * (villainFold / ref).let { it * it }
        return r.coerceIn(MIN_RATIO, if (locked) p.maxBluffRatioLocked else p.maxBluffRatio)
    }

    const val MIN_RATIO = 0.2

    fun apply(
        heroIdx: List<Int>, weights: FloatArray, ratio: Double, station: Boolean,
        agg: FloatArray, passive: FloatArray, fold: FloatArray?,
        aggEv: (Int) -> Double, passiveEv: (Int) -> Double, isValue: (Int) -> Boolean,
        priority: (Int) -> Boolean, foldAllowed: (Int) -> Boolean,
        /** PostflopMassiveExploitOverride: bluffs with a blocker / clean draw and a massive EV edge keep their frequency. */
        exempt: (Int) -> Boolean = { false },
    ): BooleanArray {
        val capped = BooleanArray(Combos.COUNT)
        var value = 0.0
        val bluffs = ArrayList<Int>()
        for (h in heroIdx) {
            if (agg[h] <= 0f) continue
            if (isValue(h)) value += agg[h] * weights[h] else if (!exempt(h)) bluffs += h
        }
        var budget = value * ratio
        fun alt(h: Int) = maxOf(if (foldAllowed(h) && fold != null) 0.0 else Double.NEGATIVE_INFINITY, passiveEv(h))
        val prio = BooleanArray(Combos.COUNT).also { a -> for (h in bluffs) a[h] = priority(h) }
        bluffs.sortWith(compareByDescending<Int> { if (prio[it]) 1 else 0 }.thenByDescending { aggEv(it) - alt(it) })
        for (h in bluffs) {
            val w = agg[h] * weights[h]
            val keep = when {
                station && !prio[h] -> 0f
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

    /**
     * Total aggressive-frequency cap (OOP check-raise): trims the smallest EV gains first, bluffs and marginal raises
     * before [protect] hands (value raises: ≥ 50% vs the continuing range or nuts), which are cut only if the cap binds.
     */
    fun capTotal(
        heroIdx: List<Int>, weights: FloatArray, maxFrequency: Double,
        agg: FloatArray, passive: FloatArray, fold: FloatArray?, capped: BooleanArray,
        aggEv: (Int) -> Double, passiveEv: (Int) -> Double, foldAllowed: (Int) -> Boolean,
        protect: (Int) -> Boolean = { false },
    ) {
        var total = 0.0; var raised = 0.0
        for (h in heroIdx) { total += weights[h]; raised += weights[h] * agg[h] }
        if (total <= 0.0 || raised <= maxFrequency * total) return
        var excess = raised - maxFrequency * total
        fun alt(h: Int) = maxOf(if (fold != null && foldAllowed(h)) 0.0 else Double.NEGATIVE_INFINITY, passiveEv(h))
        val live = heroIdx.filter { agg[it] > 0f }.sortedBy { aggEv(it) - alt(it) }
        // Bluffs / marginal raises are trimmed first; value raises only if the cap still binds.
        for (h in live.filter { !protect(it) } + live.filter { protect(it) }) {
            if (excess <= 1e-12) break
            val cut = minOf(agg[h].toDouble() * weights[h], excess)
            val removed = minOf(agg[h], (cut / weights[h]).toFloat())
            agg[h] -= removed
            excess -= cut
            capped[h] = true
            // A trimmed value raise keeps the hand (passive line), it is never turned into a fold.
            val toPassive = fold == null || !foldAllowed(h) || passiveEv(h) >= 0.0 || protect(h)
            if (toPassive) passive[h] += removed else fold!![h] += removed
        }
    }
}
