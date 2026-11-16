package com.solverin.engine.preflop

import com.solverin.engine.core.HandClass
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.range.SpotStrategy

/**
 * Call module for every node with a passive option, all positions:
 *  cold call vs open (IP: MP/CO/BTN, OOP: SB/BB), overcall vs open + caller, flat vs 3bet, vs 4bet,
 *  vs squeeze (opener / caller) and vs 5bet jam. Every flat is priced by the master equation and kept
 *  only if EV_call − callEdge(stake) beats both fold and the raise. Each rejection names the killing term.
 */
enum class ColdCallVerdict {
    CALL, PREFER_RAISE, REJECT_RAKE, REJECT_REALIZATION, REJECT_DOMINATION, REJECT_SQUEEZE_RISK, REJECT_NEGATIVE_EV
}

class ColdCallAnalysis(
    /** True for a cold call (no voluntary chips in yet): VS_RFI and SQUEEZE overcall. */
    val cold: Boolean,
    val inPosition: Boolean,
    val terms: Array<EvTerms>,
    val verdict: Array<ColdCallVerdict>,
    private val frequencyProvider: () -> Double,
    /** Combo-weighted share of all hands whose flat is killed by rake. */
    val rakeRejected: Double,
) {
    /** Combo-weighted call frequency over hero's range reaching the node. */
    val frequency: Double by lazy { frequencyProvider() }

    fun count(v: ColdCallVerdict): Int = verdict.count { it == v }
}

object ColdCallModule {

    fun isCold(key: SpotKey): Boolean = key.scenario == Scenario.VS_RFI || key.scenario == Scenario.SQUEEZE

    private fun withoutSqueeze(t: EvTerms, branch: Double) = (1 - t.pReraise) * branch - t.pReraise * t.investment

    fun analyze(
        key: SpotKey, call: CallNode, terms: Array<EvTerms>, strategy: SpotStrategy, policy: StakePolicy, frequency: () -> Double,
    ): ColdCallAnalysis {
        val edge = policy.callEdgeBb
        val verdict = Array(HandClass.COUNT) { h ->
            val t = terms[h]
            val noRake = withoutSqueeze(t, t.equity * t.realization * t.potTotal - t.investment + t.implied)
            val fullReal = withoutSqueeze(t, minOf(t.equity, PreflopMathEngine.MAX_EQR) * t.potTotal - t.investment - t.rake + t.implied)
            val noDom = withoutSqueeze(t, minOf(t.equity * t.realizationNoDomination, PreflopMathEngine.MAX_EQR) * t.potTotal -
                t.investment - t.rake + t.implied)
            when {
                strategy.call(h) > 0f -> ColdCallVerdict.CALL
                strategy.raise(h) > 0f && t.ev - edge > 0 -> ColdCallVerdict.PREFER_RAISE
                t.ev <= edge && noRake > edge -> ColdCallVerdict.REJECT_RAKE
                t.ev <= edge && noDom > edge -> ColdCallVerdict.REJECT_DOMINATION
                t.ev <= edge && fullReal > edge -> ColdCallVerdict.REJECT_REALIZATION
                t.pReraise > 0 && t.calledBranch > edge -> ColdCallVerdict.REJECT_SQUEEZE_RISK
                strategy.raise(h) > 0f -> ColdCallVerdict.PREFER_RAISE
                else -> ColdCallVerdict.REJECT_NEGATIVE_EV
            }
        }
        var rake = 0.0
        for (h in 0 until HandClass.COUNT) if (verdict[h] == ColdCallVerdict.REJECT_RAKE) rake += HandClass.combos(h)
        return ColdCallAnalysis(isCold(key), call.heroInPosition, terms, verdict, frequency, rake / HandClass.TOTAL_COMBOS)
    }
}
