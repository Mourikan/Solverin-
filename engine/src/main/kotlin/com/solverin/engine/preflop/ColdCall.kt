package com.solverin.engine.preflop

import com.solverin.engine.core.HandClass
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.range.SpotStrategy

/**
 * Cold Call module: flat vs a single open (IP: MP/CO/BTN, OOP: SB/BB) and overcall in a squeeze spot.
 * Each hand's flat is accepted only if EV_call - callEdge(stake) > max(0, EV_raise - raiseEdge).
 * Rejections are attributed to the term of the master equation that kills the call.
 */
enum class ColdCallVerdict {
    CALL, PREFER_RAISE, REJECT_RAKE, REJECT_REALIZATION, REJECT_DOMINATION, REJECT_SQUEEZE_RISK, REJECT_NEGATIVE_EV
}

class ColdCallAnalysis(
    val inPosition: Boolean,
    val terms: Array<EvTerms>,
    val verdict: Array<ColdCallVerdict>,
    /** Combo-weighted cold call frequency. */
    val frequency: Double,
    /** Combo-weighted share of all hands rejected because of rake. */
    val rakeRejected: Double,
) {
    fun count(v: ColdCallVerdict): Int = verdict.count { it == v }
}

object ColdCallModule {

    fun applies(key: SpotKey): Boolean = key.scenario == Scenario.VS_RFI || key.scenario == Scenario.SQUEEZE

    fun analyze(call: CallNode, terms: Array<EvTerms>, strategy: SpotStrategy, policy: StakePolicy): ColdCallAnalysis {
        val edge = policy.callEdgeBb
        val verdict = Array(HandClass.COUNT) { h ->
            val t = terms[h]
            val pSq = t.pReraise
            when {
                strategy.call(h) > 0f -> ColdCallVerdict.CALL
                strategy.raise(h) > 0f && t.ev - edge > 0 -> ColdCallVerdict.PREFER_RAISE
                t.ev + (1 - pSq) * t.rake > edge -> ColdCallVerdict.REJECT_RAKE
                (1 - pSq) * (t.equity * t.potTotal - t.investment - t.rake + t.implied) - pSq * t.investment > edge ->
                    ColdCallVerdict.REJECT_REALIZATION
                t.implied < 0 && t.ev - (1 - pSq) * t.implied > edge -> ColdCallVerdict.REJECT_DOMINATION
                pSq > 0 && t.calledBranch > edge -> ColdCallVerdict.REJECT_SQUEEZE_RISK
                strategy.raise(h) > 0f -> ColdCallVerdict.PREFER_RAISE
                else -> ColdCallVerdict.REJECT_NEGATIVE_EV
            }
        }
        var rake = 0.0
        for (h in 0 until HandClass.COUNT) if (verdict[h] == ColdCallVerdict.REJECT_RAKE) rake += HandClass.combos(h)
        return ColdCallAnalysis(call.heroInPosition, terms, verdict, strategy.callFrequency(), rake / HandClass.TOTAL_COMBOS)
    }
}
