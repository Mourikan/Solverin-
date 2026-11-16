package com.solverin.engine.preflop

import com.solverin.engine.core.HandClass
import com.solverin.engine.eval.CardRemoval
import com.solverin.engine.model.Partition
import com.solverin.engine.range.Scenario
import kotlin.math.pow

/**
 * Caps bluff raises (equity < 50% vs villain's continue range) at valueCombos x ratio.
 * Profile fold rates are measured vs normal-width ranges; this keeps the raising range
 * polarized at a defensible value:bluff ratio, scaled up vs over-folders and down vs stations.
 * Bluffs compete for the budget by EV surplus over their best alternative (blockers included via FE).
 * Max-EV override: hands flagged [exempt] (3bet / squeeze nodes: real blocker + large EV) are outside the cap;
 * hands flagged [priority] (4bet / cold 4bet nodes) are served first but stay inside it.
 */
object BluffBudget {

    /** Bluff:value ratio at the reference fold rate (4bet-class nodes: 100bb jam dynamics => fewer bluffs). */
    private fun base(s: Scenario): Double = when (s) {
        Scenario.RFI -> Double.POSITIVE_INFINITY
        Scenario.VS_RFI -> 1.0
        Scenario.SQUEEZE -> 0.8
        Scenario.VS_3BET -> 0.45
        Scenario.VS_4BET -> 0.25
        Scenario.VS_SQUEEZE_OPENER, Scenario.VS_SQUEEZE_CALLER -> 0.4
        Scenario.VS_5BET -> 0.0
        Scenario.VS_3BET_COLD -> 0.35
    }

    /** 4bet-class nodes: hero's raise is a 4bet or cold 4bet (stricter exemption, lower value threshold). */
    fun isFourBetNode(s: Scenario): Boolean = when (s) {
        Scenario.VS_3BET, Scenario.VS_SQUEEZE_OPENER, Scenario.VS_SQUEEZE_CALLER, Scenario.VS_3BET_COLD -> true
        else -> false
    }

    /** Cap bypass (3bet / squeeze nodes only); +∞ = no bypass at this node. */
    fun exemptThreshold(s: Scenario, policy: StakePolicy): Double =
        if (s == Scenario.VS_RFI || s == Scenario.SQUEEZE) policy.bluffExemptEvBb else Double.POSITIVE_INFINITY

    /**
     * Hands that bypass the cap at 3bet / squeeze nodes:
     *  - blocker override: BlockerFactor < 1, EV >= bluffExemptEvBb, (optionally) main villain over-folds vs MDF;
     *  - pure-exploit override: EV >= massiveExploitEvBb and main villain over-folds by massiveOverfoldMargin.
     */
    fun capExempt(s: Scenario, t: EvTerms, policy: StakePolicy): Boolean {
        if (s != Scenario.VS_RFI && s != Scenario.SQUEEZE) return false
        val blocker = t.blockerFactor < 1.0 && t.ev >= policy.bluffExemptEvBb &&
            (!policy.bluffExemptRequiresOverfold || t.overfolds())
        val massive = t.ev >= policy.massiveExploitEvBb && t.overfolds(policy.massiveOverfoldMargin)
        return blocker || massive
    }

    /** Priority inside the cap at 4bet / cold 4bet nodes: blocker bluffs with EV >= bluffExemptFourBetEvBb. */
    fun capPriority(s: Scenario, t: EvTerms, policy: StakePolicy): Boolean =
        isFourBetNode(s) && t.blockerFactor < 1.0 && t.ev >= policy.bluffExemptFourBetEvBb &&
            (!policy.bluffExemptRequiresOverfold || t.overfolds())

    /** Priority inside the cap (4bet / cold 4bet nodes only); +∞ = none. */
    fun priorityThreshold(s: Scenario, policy: StakePolicy): Double =
        if (isFourBetNode(s)) policy.bluffExemptFourBetEvBb else Double.POSITIVE_INFINITY

    /** Value raise test at node [s]. */
    fun isValue(s: Scenario, hand: Int, valueEquity: Float, policy: StakePolicy, effectiveStackBb: Double): Boolean {
        if (!isFourBetNode(s)) return valueEquity >= 0.5f
        if (effectiveStackBb <= policy.akValueMaxStackBb && hand in AK) return true
        return valueEquity >= policy.fourBetValueEquity
    }

    private val AK = intArrayOf(HandClass.parse("AKs"), HandClass.parse("AKo"))

    /** Equilibrium-reference fold rate of the first opponent at each node. */
    fun referenceFold(s: Scenario): Double = when (s) {
        Scenario.RFI -> 1.0
        Scenario.VS_RFI -> 0.55
        Scenario.SQUEEZE -> 0.62
        Scenario.VS_3BET -> 0.50
        Scenario.VS_4BET -> 0.35
        Scenario.VS_SQUEEZE_OPENER, Scenario.VS_SQUEEZE_CALLER -> 0.50
        Scenario.VS_5BET -> 1.0
        Scenario.VS_3BET_COLD -> 0.50
    }

    /**
     * Bluff:value cap. [villainFold] = first opponent's actual (unblocked) fold share at the node,
     * profile or node-locked.
     */
    fun ratio(s: Scenario, villainFold: Double): Double {
        val b = base(s)
        if (b.isInfinite()) return b
        return b * (villainFold / referenceFold(s)).pow(3).coerceIn(0.25, 5.0)
    }

    /** Unblocked fold share of a response partition. */
    fun foldShare(response: Partition): Double {
        val f = CardRemoval.total(response.fold)
        val all = f + CardRemoval.total(response.call) + CardRemoval.total(response.raise)
        return if (all > 0) f / all else 0.0
    }

    /**
     * Mutates raise/call in place. [surplus] = adjusted raise EV minus best alternative;
     * [fallbackCall] = call share the hand takes if its raise is removed.
     */
    fun apply(
        ratio: Double, raise: FloatArray, call: FloatArray, valueEq: FloatArray,
        surplus: DoubleArray, fallbackCall: FloatArray, exempt: BooleanArray? = null, priority: BooleanArray? = null,
    ) {
        if (ratio.isInfinite()) return
        var value = 0.0
        val bluffs = ArrayList<Int>()
        for (h in 0 until HandClass.COUNT) {
            if (raise[h] <= 0f) continue
            if (valueEq[h] >= 0.5f) value += raise[h] * HandClass.combos(h)
            else if (exempt == null || !exempt[h]) bluffs += h
        }
        var budget = value * ratio
        // Priority bluffs (blocker + high EV at 4bet nodes) take the slots first, then by EV surplus.
        bluffs.sortWith(compareByDescending<Int> { if (priority?.get(it) == true) 1 else 0 }.thenByDescending { surplus[it] })
        for (h in bluffs) {
            val combos = raise[h] * HandClass.combos(h)
            val keep = when {
                budget >= combos -> raise[h]
                budget > 0 -> (budget / HandClass.combos(h)).toFloat()
                else -> 0f
            }
            budget -= keep * HandClass.combos(h)
            val removed = raise[h] - keep
            if (removed > 0f) {
                raise[h] = keep
                call[h] = (call[h] + removed * fallbackCall[h]).coerceAtMost(1f - keep)
            }
        }
    }
}
