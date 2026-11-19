package com.solverin.engine

import com.solverin.engine.core.HandClass
import com.solverin.engine.preflop.PreflopContext
import com.solverin.engine.preflop.SpotAnalysis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Engine-wide invariants checked on every solved spot. */
object Invariants {
    private const val EPS = 1e-6

    /** Probabilities, EV identities and strategy bounds of the master equation. */
    fun equation(a: SpotAnalysis) {
        for (x in 0 until HandClass.COUNT) {
            val r = a.strategy.raise(x); val c = a.strategy.call(x)
            assertTrue(r >= 0f && c >= 0f && r + c <= 1.0001f, "${a.key} ${HandClass.name(x)} strategy bounds")
            a.raiseTerms?.get(x)?.let { t ->
                assertEquals(1.0, t.pFold + t.pCall + t.pReraise, 1e-6, "${a.key} ${HandClass.name(x)} raise probabilities")
                assertEquals(t.pFold * t.foldWin + t.pCall * t.calledEv + t.pReraise * t.evVsReraise, t.ev, 1e-6,
                    "${a.key} ${HandClass.name(x)} raise EV identity")
                assertTrue(t.ev.isFinite() && t.mainFold in 0.0..1.0)
            }
            a.callTerms?.get(x)?.let { t ->
                assertEquals(1.0, t.pCall + t.pReraise, 1e-6)
                assertEquals((1 - t.pReraise) * t.calledEv - t.pReraise * t.investment, t.ev, 1e-6,
                    "${a.key} ${HandClass.name(x)} call EV identity")
            }
        }
    }

    /**
     * Max-EV consistency: every action taken is within the policy mixing band of the best option.
     * The only allowed deviation is a bluff raise removed by the cap (then the hand takes its best
     * remaining option). Cap-exempt hands are never removed.
     */
    fun maxEv(a: SpotAnalysis, ctx: PreflopContext) {
        val p = ctx.policy
        for (x in 0 until HandClass.COUNT) {
            val d = a.decision(x)
            val call = if (d.evCall.isNaN()) Double.NEGATIVE_INFINITY else d.evCall - p.callEdge(a.key.scenario, a.key.hero, x)
            val raise = if (d.evRaise.isNaN()) Double.NEGATIVE_INFINITY else d.evRaise - p.raiseEdge(a.key.scenario, a.key.hero, x)
            val best = maxOf(0.0, call, raise)
            val bestNoRaise = maxOf(0.0, call)
            val name = "${a.key} ${HandClass.name(x)}"
            if (d.mix.raise > 0f) assertTrue(raise >= best - p.mixBandBb - EPS, "$name raises below max EV")
            val capped = raise > bestNoRaise && !d.isValue && d.mix.raise == 0f
            if (capped) assertTrue(a.capExempt?.get(x) != true, "$name cap-exempt raise suppressed")
            if (d.mix.call > 0f) assertTrue(call >= best - p.mixBandBb - EPS || capped || (raise > call && !d.isValue),
                "$name calls below max EV")
            if (d.mix.fold >= 0.999f) assertTrue(best <= p.mixBandBb + EPS || capped, "$name folds a +EV option")
            if (capped && call > p.mixBandBb) assertTrue(d.mix.call > 0f, "$name capped bluff must fall back to its +EV call")
        }
    }

    /** Non-exempt bluff raises respect value × ratio (1-byte quantization tolerance). */
    fun bluffCap(a: SpotAnalysis) {
        if (a.bluffRatio.isInfinite()) return
        var value = 0.0; var bluff = 0.0
        for (x in 0 until HandClass.COUNT) {
            val r = a.strategy.raise(x) * HandClass.combos(x)
            if (a.strategy.isValue(x)) value += r
            else if (a.capExempt?.get(x) != true) bluff += r
        }
        assertTrue(bluff <= value * a.bluffRatio * 1.02 + 0.6, "${a.key} bluffs $bluff > value $value × ${a.bluffRatio}")
    }

    fun all(a: SpotAnalysis, ctx: PreflopContext) {
        equation(a); maxEv(a, ctx); bluffCap(a)
    }
}
