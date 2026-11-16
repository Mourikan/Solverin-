package com.solverin.engine.preflop

/**
 * EV thresholds -> action frequencies.
 * adjusted EVs: fold 0 | call - callEdge - OOP penalty (or discarded) | raise - raiseEdge.
 * Actions within mixBand of the best are mixed linearly by distance.
 */
class DecisionPolicy(private val p: StakePolicy) {

    class Mix(val raise: Float, val call: Float)

    fun adjustedCall(evCall: Double, oop: Boolean, playability: Float): Double {
        if (evCall.isNaN()) return Double.NEGATIVE_INFINITY
        if (oop && playability < p.minOopCallPlayability && evCall < p.marginalCallBb) return Double.NEGATIVE_INFINITY
        return evCall - p.callEdgeBb - if (oop) p.oopCallPenaltyBb else 0.0
    }

    fun adjustedRaise(evRaise: Double): Double = evRaise - p.raiseEdgeBb

    fun mix(callAdj: Double, raiseAdj: Double): Mix {
        val best = maxOf(0.0, callAdj, raiseAdj)
        val band = p.mixBandBb
        fun w(v: Double) = if (v == Double.NEGATIVE_INFINITY) 0.0 else ((band - (best - v)) / band).coerceAtLeast(0.0)
        val wf = w(0.0); val wc = w(callAdj); val wr = w(raiseAdj)
        val s = wf + wc + wr
        if (s <= 0.0) return Mix(0f, 0f)
        return Mix((wr / s).toFloat(), (wc / s).toFloat())
    }
}
