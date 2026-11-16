package com.solverin.engine

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.core.Position.*
import com.solverin.engine.core.Stake
import com.solverin.engine.model.NodeLock
import com.solverin.engine.model.NodeLocks
import com.solverin.engine.model.VillainNode
import com.solverin.engine.preflop.PreflopContext
import com.solverin.engine.preflop.Sizing
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stress battery: every 6-max node × stakes × extreme villain archetypes (node-locked at every node)
 * × stack depths and sizings, checked against the master-equation and max-EV invariants.
 */
class StressTest {
    private val engine = EngineTest.engine
    private fun h(s: String) = HandClass.parse(s)

    private fun allKeys(ctx: PreflopContext): List<SpotKey> {
        val out = ArrayList<SpotKey>()
        val callers = listOf<Position?>(null) + Position.entries
        for (s in Scenario.entries) for (hero in Position.entries) for (v in Position.entries) for (c in callers) {
            if (hero == v || c == hero || c == v) continue
            val k = SpotKey.of(s, hero, v, c)
            if (engine.isValid(k, ctx)) out += k
        }
        return out
    }

    /** Locks every villain node for every position pair with one archetype. */
    private fun archetype(raise: Double, call: Double, valueShare: Double, open: Double?): NodeLocks {
        var l = NodeLocks.NONE
        for (v in Position.entries) {
            if (open != null && v != BB) l = l.with(VillainNode.OPEN, v, NodeLock(raise = open))
            for (node in VillainNode.entries) {
                if (node == VillainNode.OPEN) continue
                val lock = when (node) {
                    VillainNode.VS_JAM -> NodeLock(call = call)
                    VillainNode.SQUEEZE -> NodeLock(raise = raise, valueShare = valueShare)
                    else -> NodeLock(raise = raise, call = call, valueShare = valueShare)
                }
                l = l.with(node, v, lock)
            }
        }
        return l
    }

    private val station = archetype(raise = 0.03, call = 0.70, valueShare = 1.0, open = 0.40)
    private val nit = archetype(raise = 0.02, call = 0.10, valueShare = 1.0, open = 0.10)
    private val maniac = archetype(raise = 0.25, call = 0.30, valueShare = 0.5, open = 0.55)
    private val overfolder = archetype(raise = 0.04, call = 0.18, valueShare = 0.9, open = null)

    private fun sweep(ctx: PreflopContext, label: String): Int {
        var n = 0
        for (k in allKeys(ctx)) {
            val a = engine.analyze(k, ctx)
            Invariants.all(a, ctx)
            val aa = a.strategy.mix(h("AA"))
            assertTrue(aa.fold == 0f, "$label $k AA folds")
            n++
        }
        engine.clearCache()
        return n
    }

    @Test fun populationEveryNodeEveryStake() {
        var n = 0
        for (st in Stake.entries) n += sweep(PreflopContext(st), "pop $st")
        assertEquals(165 * Stake.entries.size, n)
    }

    @Test fun extremeArchetypesNodeLockedEverywhere() {
        for ((name, locks) in listOf("station" to station, "nit" to nit, "maniac" to maniac, "overfolder" to overfolder)) {
            for (st in listOf(Stake.NL2, Stake.NL50, Stake.NL200)) sweep(PreflopContext(st, locks = locks), "$name $st")
        }
    }

    @Test fun stackDepthsAndSizings() {
        for (stack in listOf(40.0, 60.0, 150.0, 250.0)) sweep(PreflopContext(Stake.NL50, effectiveStackBb = stack), "stack $stack")
        sweep(PreflopContext(Stake.NL50, sizing = Sizing(openBb = 2.0, sbOpenBb = 3.5, threeBetIpMult = 3.5, threeBetOopMult = 4.5)), "min-open")
        sweep(PreflopContext(Stake.NL200, sizing = Sizing(openBb = 3.0, fourBetMult = 2.0, coldFourBetMult = 2.2)), "big-open")
    }

    @Test fun archetypeOrderingSanity() {
        // Bluff 3bets: more vs over-folders than vs stations; opens: wider vs nits than vs stations.
        fun bluffCombos(ctx: PreflopContext, k: SpotKey): Double {
            val a = engine.analyze(k, ctx)
            return (0 until HandClass.COUNT).sumOf { x -> if (a.strategy.isValue(x)) 0.0 else (a.strategy.raise(x) * HandClass.combos(x)).toDouble() }
        }
        for (k in listOf(SpotKey.of(Scenario.VS_RFI, BTN, CO), SpotKey.of(Scenario.VS_RFI, CO, UTG), SpotKey.of(Scenario.VS_RFI, BB, BTN))) {
            val vsFold = bluffCombos(PreflopContext(Stake.NL50, locks = overfolder), k)
            val vsStation = bluffCombos(PreflopContext(Stake.NL50, locks = station), k)
            assertTrue(vsFold >= vsStation, "$k bluffs vs over-folder $vsFold < vs station $vsStation")
        }
        for (p in listOf(UTG, CO, BTN, SB)) {
            val k = SpotKey.of(Scenario.RFI, p)
            val vsNit = engine.analyze(k, PreflopContext(Stake.NL50, locks = nit)).raiseFrequency()
            val vsStation = engine.analyze(k, PreflopContext(Stake.NL50, locks = station)).raiseFrequency()
            assertTrue(vsNit > vsStation, "$p opens vs nits $vsNit <= vs stations $vsStation")
        }
    }

    @Test fun multiwayAndSandwichSpots() {
        val ctx = PreflopContext(Stake.NL50)
        // 3-way squeeze spots and overcalls (hero vs opener + caller).
        for (k in listOf(SpotKey.of(Scenario.SQUEEZE, BB, CO, BTN), SpotKey.of(Scenario.SQUEEZE, SB, HJ, CO), SpotKey.of(Scenario.SQUEEZE, BTN, UTG, HJ))) {
            val a = engine.analyze(k, ctx)
            assertEquals(1f, a.strategy.raise(h("AA")))
            for (x in 0 until HandClass.COUNT) a.callTerms!![x].let { t -> assertTrue(t.realization > 0f) }
            Invariants.all(a, ctx)
        }
        // 4-way RFI from UTG: every opponent priced, squeeze-behind and overcall risk present for early callers.
        val utg = engine.analyze(SpotKey.of(Scenario.RFI, UTG), ctx)
        assertTrue(utg.raiseTerms!![h("KQo")].pReraise > 0.0)
        // Blind sandwich: BB facing BTN open + SB 3bet, cross-squeezing blinds node-locked aggressive.
        val cross = NodeLocks.NONE
            .with(VillainNode.DEFEND_VS_OPEN, SB, NodeLock(raise = 0.18, call = 0.0, valueShare = 0.55), versus = BTN)
            .with(VillainNode.VS_3BET, BTN, NodeLock(raise = 0.15, call = 0.40, valueShare = 0.6), versus = SB)
            .with(VillainNode.VS_COLD_4BET, BTN, NodeLock(raise = 0.03, call = 0.02), versus = SB)
        val c2 = PreflopContext(Stake.NL50, locks = cross)
        val s = engine.analyze(SpotKey.of(Scenario.VS_3BET_COLD, BB, SB, BTN), c2)
        Invariants.all(s, c2)
        val quiet = engine.analyze(SpotKey.of(Scenario.VS_3BET_COLD, BB, SB, BTN), ctx)
        assertTrue(s.callTerms!![h("KQs")].pReraise > quiet.callTerms!![h("KQs")].pReraise, "sandwich risk grows with an aggressive opener")
        assertTrue(s.callFrequency() <= quiet.callFrequency() + 1e-9)
        // Squeeze war: SB squeezes vs CO open + BTN call while BB behind is a maniac squeezer.
        val war = NodeLocks.NONE.with(VillainNode.SQUEEZE, BB, NodeLock(raise = 0.25), versus = CO)
        val w = engine.analyze(SpotKey.of(Scenario.SQUEEZE, SB, CO, BTN), PreflopContext(Stake.NL50, locks = war))
        val w0 = engine.analyze(SpotKey.of(Scenario.SQUEEZE, SB, CO, BTN), ctx)
        assertTrue(w.callTerms!![h("99")].pReraise > w0.callTerms!![h("99")].pReraise)
        Invariants.all(w, PreflopContext(Stake.NL50, locks = war))
    }

    @Test fun massiveExploitOverrideIgnoresBlockerFactor() {
        val k = SpotKey.of(Scenario.VS_RFI, CO, UTG)
        val locks = NodeLocks.NONE.with(VillainNode.OPEN, UTG, NodeLock(raise = 0.12))
            .with(VillainNode.VS_3BET, UTG, NodeLock(raise = 0.06, call = 0.24), versus = CO)
        val ctx = PreflopContext(Stake.NL200, locks = locks)
        val a = engine.analyze(k, ctx)
        val t = a.raiseTerms!![h("A5s")]
        assertTrue(t.blockerFactor >= 1.0, "A5s unblocks UTG's folds here (BF ${t.blockerFactor})")
        assertTrue(t.ev >= ctx.policy.massiveExploitEvBb && t.overfolds(ctx.policy.massiveOverfoldMargin),
            "A5s EV ${t.ev} mainFold ${t.mainFold} mdf ${t.mdf}")
        assertEquals(1f, a.strategy.raise(h("A5s")), "massive over-fold exploit executes the 3bet")
        assertTrue(a.capExempt!![h("A5s")])
        for (x in 0 until HandClass.COUNT) {
            val tx = a.raiseTerms!![x]
            if (!a.strategy.isValue(x) && tx.ev >= ctx.policy.massiveExploitEvBb && tx.overfolds(ctx.policy.massiveOverfoldMargin))
                assertTrue(a.strategy.raise(x) > 0f, "${HandClass.name(x)} massive exploit suppressed")
        }
        // Never at 4bet nodes: same villain over-folding to 4bets does not bypass the cap there.
        val v4 = engine.analyze(SpotKey.of(Scenario.VS_3BET, UTG, CO),
            PreflopContext(Stake.NL200, locks = NodeLocks.NONE.with(VillainNode.VS_4BET, CO, NodeLock(raise = 0.02, call = 0.10), versus = UTG)))
        assertTrue(v4.capExempt!!.none { it })
        Invariants.bluffCap(v4)
        // Sticky villain: no override.
        val sticky = PreflopContext(Stake.NL2, locks = NodeLocks.NONE.with(VillainNode.VS_3BET, UTG, NodeLock(raise = 0.03, call = 0.62), versus = CO))
        val s = engine.analyze(k, sticky)
        assertTrue(!s.raiseTerms!![h("A5s")].overfolds())
        assertEquals(0f, s.strategy.raise(h("A5s")))
    }
}
