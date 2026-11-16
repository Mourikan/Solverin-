package com.solverin.engine

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position.*
import com.solverin.engine.core.Stake
import com.solverin.engine.model.NodeLock
import com.solverin.engine.model.NodeLocks
import com.solverin.engine.model.VillainNode
import com.solverin.engine.preflop.BluffBudget
import com.solverin.engine.preflop.PreflopContext
import com.solverin.engine.preflop.SpotAnalysis
import com.solverin.engine.preflop.Sizing
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** High-difficulty boundary spots: the engine must stay consistent with its own master equation. */
class FrontierSpotsTest {
    private val engine = EngineTest.engine
    private fun h(s: String) = HandClass.parse(s)

    /** Every action taken is the max-EV one under the stake policy, except bluffs removed by the cap. */
    private fun assertPolicyConsistent(a: SpotAnalysis, ctx: PreflopContext) {
        val p = ctx.policy
        for (x in 0 until HandClass.COUNT) {
            val d = a.decision(x)
            val call = if (d.evCall.isNaN()) Double.NEGATIVE_INFINITY else d.evCall - p.callEdgeBb
            val raise = if (d.evRaise.isNaN()) Double.NEGATIVE_INFINITY else d.evRaise - p.raiseEdgeBb
            val best = maxOf(0.0, call, raise)
            if (d.mix.raise > 0f) assertTrue(raise >= best - p.mixBandBb - 1e-9, "${HandClass.name(x)} raises below max EV")
            if (d.mix.call > 0f) assertTrue(call >= best - p.mixBandBb - 1e-9 || (raise >= best - 1e-9 && !d.isValue),
                "${HandClass.name(x)} calls below max EV")
            if (d.mix.fold >= 0.999f && best > p.mixBandBb) assertTrue(raise == best && !d.isValue,
                "${HandClass.name(x)} folds a +EV non-bluff option")
        }
    }

    @Test fun spot1_btnVsCoOpenWithActiveBlindsBehind() {
        val k = SpotKey.of(Scenario.VS_RFI, BTN, CO)
        val passive = NodeLocks.NONE
            .with(VillainNode.SQUEEZE, SB, NodeLock(raise = 0.01), versus = CO)
            .with(VillainNode.SQUEEZE, BB, NodeLock(raise = 0.01), versus = CO)
            .with(VillainNode.COLD_VS_3BET, SB, NodeLock(raise = 0.005, call = 0.01), versus = CO)
            .with(VillainNode.COLD_VS_3BET, BB, NodeLock(raise = 0.005, call = 0.01), versus = CO)
        val aggressive = NodeLocks.NONE
            .with(VillainNode.SQUEEZE, SB, NodeLock(raise = 0.12), versus = CO)
            .with(VillainNode.SQUEEZE, BB, NodeLock(raise = 0.12), versus = CO)
            .with(VillainNode.COLD_VS_3BET, SB, NodeLock(raise = 0.06, call = 0.02), versus = CO)
            .with(VillainNode.COLD_VS_3BET, BB, NodeLock(raise = 0.06, call = 0.02), versus = CO)
        val ctxP = PreflopContext(Stake.NL50, locks = passive)
        val ctxA = PreflopContext(Stake.NL50, locks = aggressive)
        val p = engine.analyze(k, ctxP)
        val a = engine.analyze(k, ctxA)
        for (x in listOf("KJo", "AQo")) {
            val dp = p.decision(h(x)); val da = a.decision(h(x))
            assertTrue(da.call!!.pReraise > dp.call!!.pReraise, "$x squeeze risk")
            assertTrue(da.evCall < dp.evCall, "$x flat loses value vs squeezy blinds")
            assertTrue(da.raise!!.pReraise > dp.raise!!.pReraise, "$x cold 4bet risk on the 3bet")
            assertTrue(da.evRaise < dp.evRaise, "$x 3bet loses value vs cold 4bets")
        }
        assertTrue(a.callFrequency() <= p.callFrequency(), "fewer flats with an active sandwich")
        assertTrue(a.decision(h("AQo")).mix.fold == 0f && p.decision(h("AQo")).mix.fold == 0f)
        assertPolicyConsistent(p, ctxP)
        assertPolicyConsistent(a, ctxA)
    }

    @Test fun spot2_blindWarWideSb3BetAndBbCold4Bet() {
        val k = SpotKey.of(Scenario.VS_3BET_COLD, BB, SB, BTN)
        val wide = NodeLocks.NONE.with(VillainNode.DEFEND_VS_OPEN, SB, NodeLock(raise = 0.14, call = 0.0, valueShare = 0.6), versus = BTN)
        val ctx = PreflopContext(Stake.NL50, locks = wide)
        val a = engine.analyze(k, ctx)
        for (x in listOf("AA", "KK", "AKs", "AKo")) {
            assertEquals(1f, a.strategy.raise(h(x)), "$x cold 4bets for value")
            assertTrue(a.strategy.isValue(h(x)))
        }
        // Weak-blocker marginal hands never bluff above the cap; no raise with negative EV.
        var value = 0.0; var bluff = 0.0
        for (x in 0 until HandClass.COUNT) {
            val r = a.strategy.raise(x)
            if (r <= 0f) continue
            assertTrue(a.evRaise(x) > 0.0, "${HandClass.name(x)} cold 4bets at negative EV")
            if (a.strategy.isValue(x)) value += r * HandClass.combos(x) else bluff += r * HandClass.combos(x)
        }
        val main = a.raiseTerms!!.first()
        val fold = engine.villainModel(ctx).threeBettorVs4Bet(SB, BTN).foldShare
        // Tolerance: 1-byte frequency quantization (0.5% steps).
        assertTrue(bluff <= value * BluffBudget.ratio(Scenario.VS_3BET_COLD, fold) * 1.02 + 0.5, "bluffs $bluff value $value")
        for (x in listOf("A2s", "A3s", "K9s", "KTs", "QJs", "J9s")) {
            val t = a.raiseTerms!![h(x)]
            if (a.strategy.raise(h(x)) > 0f) assertTrue(a.strategy.isValue(h(x)) || t.ev >= ctx.policy.raiseEdgeBb, x)
        }
        assertTrue(main.pFold + main.pCall + main.pReraise > 0.999)
        assertPolicyConsistent(a, ctx)
        // A wider SB 3bet lowers the cold 4bet value threshold hands' risk: AKo EV grows vs the default SB.
        val base = engine.analyze(k, PreflopContext(Stake.NL50))
        assertTrue(a.evRaise(h("AKo")) > base.evRaise(h("AKo")))
    }

    @Test fun spot3_a5sCoVsTightUtgAsymmetricOverfold() {
        val k = SpotKey.of(Scenario.VS_RFI, CO, UTG)
        val tight = NodeLocks.NONE.with(VillainNode.OPEN, UTG, NodeLock(raise = 0.12))
        val sticky = tight.with(VillainNode.VS_3BET, UTG, NodeLock(raise = 0.03, call = 0.62), versus = CO)
        val folder = tight.with(VillainNode.VS_3BET, UTG, NodeLock(raise = 0.06, call = 0.24), versus = CO)
        val nl2 = PreflopContext(Stake.NL2, locks = sticky)
        val nl200 = PreflopContext(Stake.NL200, locks = folder)
        val a2 = engine.analyze(k, nl2)
        val a200 = engine.analyze(k, nl200)
        val a5 = h("A5s")
        val t2 = a2.raiseTerms!![a5]; val t200 = a200.raiseTerms!![a5]
        assertTrue(t200.pFold > t2.pFold, "over-folder folds more")
        assertTrue(t200.ev > t2.ev, "A5s 3bet worth more vs the NL200 over-folder")
        assertTrue(t2.pFold < 1 - t2.mdf, "NL2 sticky UTG defends above MDF")
        assertEquals(0f, a2.strategy.raise(a5), "no A5s bluff 3bet vs a sticky NL2 UTG")
        val exempt = t200.blockerFactor < 1.0 && t200.ev >= nl200.policy.bluffExemptEvBb && t200.pFold > 1 - t200.mdf
        if (exempt) assertEquals(1f, a200.strategy.raise(a5))
        fun bluffCombos(a: SpotAnalysis) = (0 until HandClass.COUNT).sumOf { x ->
            if (a.strategy.isValue(x)) 0.0 else (a.strategy.raise(x) * HandClass.combos(x)).toDouble()
        }
        assertTrue(bluffCombos(a200) > bluffCombos(a2), "more bluff 3bets vs the over-folder")
        assertPolicyConsistent(a2, nl2)
        assertPolicyConsistent(a200, nl200)
    }

    @Test fun fourBetNodesNeverBypassTheCap() {
        val ctx = PreflopContext(Stake.NL50)
        val a = engine.analyze(SpotKey.of(Scenario.VS_3BET, UTG, CO), ctx)
        var value = 0.0; var bluff = 0.0
        for (x in 0 until HandClass.COUNT) {
            val r = a.strategy.raise(x) * (a.reach?.get(x) ?: 1f)
            if (a.strategy.isValue(x)) value += r * HandClass.combos(x) else bluff += r * HandClass.combos(x)
        }
        val fold = engine.villainModel(ctx).threeBettorVs4Bet(CO, UTG).foldShare
        assertTrue(bluff <= value * BluffBudget.ratio(Scenario.VS_3BET, fold) * 1.02 + 0.5, "4bet bluffs $bluff vs value $value")
        assertTrue(a.strategy.isValue(h("AKo")) && a.strategy.isValue(h("AKs")))
        val utg = engine.analyze(SpotKey.of(Scenario.RFI, UTG), ctx).raiseFrequency()
        assertTrue(utg in 0.15..0.20, "UTG RFI $utg")
        val utg200 = engine.analyze(SpotKey.of(Scenario.RFI, UTG), PreflopContext(Stake.NL200)).raiseFrequency()
        assertTrue(utg200 in 0.15..0.20, "UTG RFI NL200 $utg200")
    }
}
