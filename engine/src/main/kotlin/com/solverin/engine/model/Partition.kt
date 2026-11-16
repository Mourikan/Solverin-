package com.solverin.engine.model

import com.solverin.engine.core.HandClass

/** Absolute class weights of a range split by response. raise + call + fold = prior. */
class Partition(val raise: FloatArray, val call: FloatArray, val fold: FloatArray) {
    val prior: FloatArray get() = FloatArray(HandClass.COUNT) { raise[it] + call[it] + fold[it] }
    val continueRange: FloatArray get() = FloatArray(HandClass.COUNT) { raise[it] + call[it] }

    companion object {
        /**
         * Splits [prior] by combo fractions. Value raises from the top of [ranking].byStrength,
         * calls next, bluff raises ((1 - valueShare) of raises) from the remaining pool by bluff ordering.
         */
        fun split(prior: FloatArray, raiseFrac: Double, callFrac: Double, valueShare: Double, ranking: HandRanking): Partition {
            val rem = prior.copyOf()
            val raise = FloatArray(HandClass.COUNT)
            val call = FloatArray(HandClass.COUNT)
            var total = 0.0
            for (h in 0 until HandClass.COUNT) total += prior[h] * HandClass.combos(h)
            val r = raiseFrac.coerceIn(0.0, 1.0)
            val c = callFrac.coerceIn(0.0, 1.0 - r)
            val vs = valueShare.coerceIn(0.0, 1.0)
            fill(rem, raise, total * r * vs, ranking.byStrength)
            fill(rem, call, total * c, ranking.byStrength)
            fill(rem, raise, total * r * (1 - vs), ranking.byBluff)
            return Partition(raise, call, rem)
        }

        private fun fill(rem: FloatArray, dst: FloatArray, budget: Double, order: IntArray) {
            var left = budget
            for (h in order) {
                if (left <= 1e-9) return
                val avail = rem[h] * HandClass.combos(h)
                if (avail <= 0f) continue
                val take = minOf(avail.toDouble(), left)
                val w = (take / HandClass.combos(h)).toFloat()
                dst[h] += w; rem[h] = (rem[h] - w).coerceAtLeast(0f)
                left -= take
            }
        }
    }
}
