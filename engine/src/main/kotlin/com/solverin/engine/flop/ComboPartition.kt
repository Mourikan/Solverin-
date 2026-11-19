package com.solverin.engine.flop

/** Combo weights of a villain range split by response. raise + call + fold = prior. */
class ComboPartition(val raise: FloatArray, val call: FloatArray, val fold: FloatArray) {
    val prior: FloatArray by lazy { FloatArray(Combos.COUNT) { raise[it] + call[it] + fold[it] } }
    val continueRange: FloatArray by lazy { FloatArray(Combos.COUNT) { raise[it] + call[it] } }
    val priorTotal: Double by lazy { Combos.total(prior) }
    val raiseTotal: Double by lazy { Combos.total(raise) }
    val callTotal: Double by lazy { Combos.total(call) }
    val foldTotal: Double by lazy { Combos.total(fold) }

    /**
     * Card-removal probabilities for hero combo [h]: (fold, call, raise). Below [SHRINK_COMBOS] live combos the
     * conditional split is shrunk toward the unconditional one (a pseudo-count of SHRINK_COMBOS): a range that hero's
     * cards almost empty (e.g. only one raising combo left) must not answer with a certain raise / fold. An empty range
     * answers with a call (never a free fold that would price a bluff on nothing).
     */
    fun probabilities(h: Int): DoubleArray {
        val f = Combos.remaining(h, fold, foldTotal)
        val c = Combos.remaining(h, call, callTotal)
        val r = Combos.remaining(h, raise, raiseTotal)
        val all = f + c + r
        if (all >= SHRINK_COMBOS) return doubleArrayOf(f / all, c / all, r / all)
        val total = foldTotal + callTotal + raiseTotal
        if (total <= EMPTY_MASS) return if (all > EMPTY_MASS) doubleArrayOf(f / all, c / all, r / all) else doubleArrayOf(0.0, 1.0, 0.0)
        // A response whose combos hero blocks completely is physically impossible: it gets no unconditional share.
        val fu = if (f > BLOCKED_MASS) foldTotal else 0.0
        val cu = if (c > BLOCKED_MASS) callTotal else 0.0
        val ru = if (r > BLOCKED_MASS) raiseTotal else 0.0
        val tu = fu + cu + ru
        if (tu <= EMPTY_MASS) return doubleArrayOf(foldTotal / total, callTotal / total, raiseTotal / total)
        val k = SHRINK_COMBOS - all
        val n = all + k
        return doubleArrayOf((f + k * fu / tu) / n, (c + k * cu / tu) / n, (r + k * ru / tu) / n)
    }

    /**
     * Third player's answer (multiway): a raise / squeeze is impossible when hero's cards leave no live raising combo,
     * and a player whose whole range is blocked holds nothing and folds.
     */
    fun probabilitiesLive(h: Int): DoubleArray {
        val f = Combos.remaining(h, fold, foldTotal)
        val c = Combos.remaining(h, call, callTotal)
        val r = Combos.remaining(h, raise, raiseTotal)
        if (f + c + r <= BLOCKED_MASS) return doubleArrayOf(1.0, 0.0, 0.0)
        val p = probabilities(h)
        if (r > BLOCKED_MASS) return p
        val keep = p[0] + p[1]
        return if (keep > 0.0) doubleArrayOf(p[0] / keep, p[1] / keep, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
    }

    companion object {
        /** Below this mass (combos) a range is treated as empty. */
        const val EMPTY_MASS = 1e-9
        /** Live mass (combos) at or below which a response is blocked by hero's cards (impossible). */
        const val BLOCKED_MASS = 1e-4
        /** Live combos under which the card-removal split is shrunk toward the unconditional one. */
        const val SHRINK_COMBOS = 1.0

        /** Max extra share of the range a proportional tier may extend beyond its budget. */
        const val TIER_SLACK = 0.10

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
            tierMultiplier: Double = 2.0, altBluff: IntArray? = null, altBluffShare: Double = 0.0,
        ): ComboPartition {
            val rem = prior.copyOf()
            val bet = FloatArray(Combos.COUNT)
            val total = Combos.total(prior)
            val f = betFrac.coerceIn(0.0, 1.0)
            val vs = valueShare.coerceIn(0.0, 1.0)
            val alt = if (altBluff != null) altBluffShare.coerceIn(0.0, 1.0) else 0.0
            proportional(rem, bet, total * f * vs, byStrength, tierMultiplier)
            if (altBluff != null && alt > 0.0) proportional(rem, bet, total * f * (1 - vs) * alt, altBluff, tierMultiplier)
            proportional(rem, bet, total * f * (1 - vs) * (1 - alt), byBluff, tierMultiplier)
            return ComboPartition(bet, FloatArray(Combos.COUNT), rem)
        }

        /**
         * Takes [budget] proportionally from the first min(tierMultiplier × budget, budget + TIER_SLACK × range)
         * of [order]: small bet ranges mix (no capping), large value-heavy ranges stay concentrated at the top.
         */
        private fun proportional(rem: FloatArray, dst: FloatArray, budget: Double, order: IntArray, tierMultiplier: Double) {
            if (budget <= 0.0) return
            val tier = FloatArray(Combos.COUNT)
            val available = Combos.total(rem)
            val tierBudget = minOf(available, budget * tierMultiplier, budget + TIER_SLACK * available)
            fill(rem.copyOf(), tier, tierBudget, order)
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
