package com.solverin.engine.flop

/** Combo weights of a villain range split by response. raise + call + fold = prior. */
class ComboPartition(val raise: FloatArray, val call: FloatArray, val fold: FloatArray) {
    val prior: FloatArray by lazy { FloatArray(Combos.COUNT) { raise[it] + call[it] + fold[it] } }
    val continueRange: FloatArray by lazy { FloatArray(Combos.COUNT) { raise[it] + call[it] } }
    val priorTotal: Double by lazy { Combos.total(prior) }
    val raiseTotal: Double by lazy { Combos.total(raise) }
    val callTotal: Double by lazy { Combos.total(call) }
    val foldTotal: Double by lazy { Combos.total(fold) }

    /** Card-removal probabilities for hero combo [h]: (fold, call, raise). */
    fun probabilities(h: Int): DoubleArray {
        val f = Combos.remaining(h, fold, foldTotal)
        val c = Combos.remaining(h, call, callTotal)
        val r = Combos.remaining(h, raise, raiseTotal)
        val all = f + c + r
        return if (all <= 0.0) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(f / all, c / all, r / all)
    }

    companion object {
        /**
         * Splits [prior] by weight fractions: value raises from the top of [byStrength], calls next,
         * bluff raises ((1 − valueShare) of raises) from the remaining pool by [byBluff].
         */
        fun split(
            prior: FloatArray, raiseFrac: Double, callFrac: Double, valueShare: Double,
            byStrength: IntArray, byBluff: IntArray,
        ): ComboPartition {
            val rem = prior.copyOf()
            val raise = FloatArray(Combos.COUNT)
            val call = FloatArray(Combos.COUNT)
            val total = Combos.total(prior)
            val r = raiseFrac.coerceIn(0.0, 1.0)
            val c = callFrac.coerceIn(0.0, 1.0 - r)
            val vs = valueShare.coerceIn(0.0, 1.0)
            fill(rem, raise, total * r * vs, byStrength)
            fill(rem, call, total * c, byStrength)
            fill(rem, raise, total * r * (1 - vs), byBluff)
            return ComboPartition(raise, call, rem)
        }

        /**
         * First-action split (villain bets / checks): value bets and bluffs are taken PROPORTIONALLY across tiers of
         * [tierMultiplier] × their budgets (players neither bet every strong hand nor every draw); the checking
         * range (fold) therefore keeps strong hands and draws instead of being capped / draw-free.
         */
        fun splitFirstAction(
            prior: FloatArray, betFrac: Double, valueShare: Double, byStrength: IntArray, byBluff: IntArray,
            tierMultiplier: Double = 2.0,
        ): ComboPartition {
            val rem = prior.copyOf()
            val bet = FloatArray(Combos.COUNT)
            val total = Combos.total(prior)
            val f = betFrac.coerceIn(0.0, 1.0)
            val vs = valueShare.coerceIn(0.0, 1.0)
            proportional(rem, bet, total * f * vs, byStrength, tierMultiplier)
            proportional(rem, bet, total * f * (1 - vs), byBluff, tierMultiplier)
            return ComboPartition(bet, FloatArray(Combos.COUNT), rem)
        }

        /** Takes [budget] proportionally from the first [tierMultiplier] × budget of [order]. */
        private fun proportional(rem: FloatArray, dst: FloatArray, budget: Double, order: IntArray, tierMultiplier: Double) {
            if (budget <= 0.0) return
            val tier = FloatArray(Combos.COUNT)
            fill(rem.copyOf(), tier, minOf(Combos.total(rem), budget * tierMultiplier), order)
            val tierTotal = Combos.total(tier)
            if (tierTotal <= 0.0) return
            val share = (budget / tierTotal).coerceAtMost(1.0).toFloat()
            for (k in 0 until Combos.COUNT) if (tier[k] > 0f) {
                val take = tier[k] * share
                dst[k] += take; rem[k] = (rem[k] - take).coerceAtLeast(0f)
            }
        }

        private fun fill(rem: FloatArray, dst: FloatArray, budget: Double, order: IntArray) {
            var left = budget
            for (k in order) {
                if (left <= 1e-9) return
                val avail = rem[k]
                if (avail <= 0f) continue
                val take = minOf(avail.toDouble(), left).toFloat()
                dst[k] += take; rem[k] = (rem[k] - take).coerceAtLeast(0f)
                left -= take
            }
        }
    }
}
