package com.solverin.engine

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.core.Position.*
import com.solverin.engine.core.Stake
import com.solverin.engine.math.Blockers
import com.solverin.engine.math.Domination
import com.solverin.engine.math.FoldModel
import com.solverin.engine.math.RakeAttribution
import com.solverin.engine.math.RakeModel
import com.solverin.engine.math.Realization
import com.solverin.engine.preflop.ColdCallVerdict
import com.solverin.engine.preflop.PreflopContext
import com.solverin.engine.preflop.PreflopEngine
import com.solverin.engine.range.RangeRepository
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineTest {
    companion object { val engine = PreflopEngine() }
    private val nl50 = PreflopContext(Stake.NL50)
    /** Exact rake attribution: range-width sanity bounds are checked under it. */
    private val nl50Share = nl50.copy(rakeAttribution = RakeAttribution.WINNER_SHARE)
    private fun h(s: String) = HandClass.parse(s)

    private fun allKeys(): List<SpotKey> {
        val out = ArrayList<SpotKey>()
        val callers = listOf<Position?>(null) + Position.entries
        for (s in Scenario.entries) for (hero in Position.entries) for (v in Position.entries) for (c in callers) {
            if (hero == v || c == hero || c == v) continue
            if (s == Scenario.RFI && v != BB) continue
            val k = SpotKey.of(s, hero, v, c)
            if (engine.isValid(k, nl50)) out += k
        }
        return out
    }

    @Test fun allSpotsSolveValid() {
        val keys = allKeys()
        // RFI 5, VS_RFI 15, VS_3BET 15, VS_4BET 15+20, SQUEEZE 20, VS_SQUEEZE_OPENER 20, VS_SQUEEZE_CALLER 20, VS_5BET 15
        assertEquals(5 + 15 + 15 + 35 + 20 + 20 + 20 + 15, keys.size)
        for (stake in listOf(Stake.NL2, Stake.NL200)) for (k in keys) {
            val s = engine.strategy(k, PreflopContext(stake))
            for (x in 0 until HandClass.COUNT) assertTrue(s.raise(x) >= 0 && s.call(x) >= 0 && s.raise(x) + s.call(x) <= 1.0001f)
            if (k.scenario == Scenario.VS_5BET) assertEquals(1f, s.call(h("AA")), "AA must call in $k")
            else assertEquals(1f, s.raise(h("AA")), "AA must raise in $k")
        }
    }

    @Test fun rfiWidensByPosition() {
        val w = listOf(UTG, HJ, CO, BTN).map { engine.strategy(SpotKey.of(Scenario.RFI, it), nl50Share).raiseFrequency() }
        assertEquals(w, w.sorted(), "RFI $w")
        assertTrue(w[0] in 0.08..0.30, "UTG ${w[0]}")
        assertTrue(w[3] in 0.25..0.75, "BTN ${w[3]}")
        assertEquals(1f, engine.strategy(SpotKey.of(Scenario.RFI, UTG), nl50).fold(h("72o")))
    }

    @Test fun microRakeDropsMarginalBlindFlats() {
        val k = SpotKey.of(Scenario.VS_RFI, BB, BTN)
        val micro = engine.strategy(k, PreflopContext(Stake.NL2))
        val high = engine.strategy(k, PreflopContext(Stake.NL200, villain = PreflopContext(Stake.NL2).villain))
        assertTrue(micro.callFrequency() < high.callFrequency(), "${micro.callFrequency()} vs ${high.callFrequency()}")
    }

    @Test fun realizationAndImpliedOdds() {
        assertTrue(Realization.factor(false, false, 15.0) < Realization.factor(true, false, 15.0))
        assertTrue(Realization.factor(false, true, 15.0) < Realization.factor(true, true, 15.0))
        assertTrue(Realization.factor(false, false, 15.0) < Realization.factor(false, true, 15.0))
        assertEquals(1f, Realization.factor(false, false, 0.0))
        val imp = engine.implied
        assertEquals(0.1176f, imp.hit[h("22")], 0.002f) // set or better on the flop: 1 - C(48,3)/C(50,3)
        assertTrue(imp.hit[h("JTs")] > imp.hit[h("JTo")])
        assertTrue(imp.hit[h("JTo")] > imp.hit[h("J2o")])
        val open = engine.villainModel(nl50.villain).open(UTG)
        val ip = imp.ev(h("77"), open, 6.0, 97.0, true)
        val oop = imp.ev(h("77"), open, 6.0, 97.0, false)
        assertTrue(ip > oop && ip > 0)
        assertTrue(Domination.share(h("KTo"), open) > Domination.share(h("KQo"), open))
        val kto = Realization.compute(h("KTo"), open, false, false, 15.0)
        assertTrue(kto.domination > 0 && kto.value < kto.valueWithoutDomination)
        val sc = Realization.compute(h("JTs"), open, true, false, 15.0)
        val trash = Realization.compute(h("J2o"), open, true, false, 15.0)
        assertTrue(sc.playability > 0 && trash.playability < 0 && sc.value > trash.value)
        assertEquals(sc.basePos * sc.initiative * (1 + sc.playability - sc.domination), sc.value.toDouble(), 1e-5)
        assertEquals(0.0, imp.ev(h("77"), open, 6.0, 0.0, true))
    }

    @Test fun coldCallModule() {
        val ipKey = SpotKey.of(Scenario.VS_RFI, BTN, HJ)
        val ip = assertNotNull(engine.analyze(ipKey, nl50).coldCall)
        assertTrue(ip.inPosition)
        assertTrue(ip.frequency > 0.0, "BTN vs MP flats something")
        val coUtg = assertNotNull(engine.analyze(SpotKey.of(Scenario.VS_RFI, CO, UTG), nl50).coldCall)
        assertTrue(coUtg.inPosition)
        val bb = assertNotNull(engine.analyze(SpotKey.of(Scenario.VS_RFI, BB, CO), nl50).coldCall)
        assertTrue(!bb.inPosition)
        assertTrue(bb.verdict[h("72o")] in setOf(ColdCallVerdict.REJECT_REALIZATION, ColdCallVerdict.REJECT_NEGATIVE_EV))
        assertEquals(ColdCallVerdict.PREFER_RAISE, bb.verdict[h("AA")])
        for (x in 0 until HandClass.COUNT) {
            val t = bb.terms[x]
            assertEquals(t.ev, (1 - t.pReraise) * t.calledBranch - t.pReraise * t.investment, 1e-9)
        }
        val micro = assertNotNull(engine.analyze(SpotKey.of(Scenario.VS_RFI, BB, BTN), PreflopContext(Stake.NL2)).coldCall)
        val noRake = assertNotNull(engine.analyze(SpotKey.of(Scenario.VS_RFI, BB, BTN),
            PreflopContext(Stake.NL2, rake = com.solverin.engine.core.RakeStructure(0.0, 0.0))).coldCall)
        assertTrue(micro.frequency < noRake.frequency, "${micro.frequency} vs ${noRake.frequency}")
        assertTrue(micro.rakeRejected > 0.0)
        assertEquals(0.0, noRake.rakeRejected)
        assertNotNull(engine.analyze(SpotKey.of(Scenario.SQUEEZE, BB, CO, BTN), nl50).coldCall)
    }

    @Test fun foldModelSubFunction() {
        val vm = engine.villainModel(nl50.villain).openerVs3Bet(CO, BTN)
        assertEquals(4.0 / (4.0 + 7.5), FoldModel.mdf(4.0, 7.5), 1e-12)
        for (x in listOf("A5s", "72o", "KK", "T9s")) {
            val f = FoldModel.evaluate(h(x), 4.0, 7.5, vm.fold, vm.call, vm.raise)
            assertEquals(1 - f.mdf * f.blockerFactor * f.tendency, f.pFold, 1e-9)
        }
        assertTrue(FoldModel.evaluate(h("A5s"), 4.0, 7.5, vm.fold, vm.call, vm.raise).blockerFactor < 1.0)
        assertTrue(FoldModel.evaluate(h("65s"), 4.0, 7.5, vm.fold, vm.call, vm.raise).blockerFactor > 1.0)
    }

    @Test fun rakeSubFunction() {
        val nl2 = Stake.NL2.defaultRake
        assertEquals(minOf(RakeModel.postflopAvgPot(5.5, 97.5) * nl2.rate, nl2.capBb), RakeModel.rakeModel(nl2, 5.5, 97.5), 1e-12)
        assertEquals(nl2.capBb, RakeModel.rakeModel(nl2, 150.0, 25.0), 1e-12)
        val k = SpotKey.of(Scenario.VS_RFI, BB, BTN)
        val full = engine.strategy(k, nl50.copy(rakeAttribution = RakeAttribution.FULL))
        val share = engine.strategy(k, nl50.copy(rakeAttribution = RakeAttribution.WINNER_SHARE))
        assertTrue(full.callFrequency() < share.callFrequency())
    }

    @Test fun callModuleCoversEveryCallNode() {
        val keys = listOf(
            SpotKey.of(Scenario.VS_3BET, CO, BTN), SpotKey.of(Scenario.VS_4BET, BB, CO),
            SpotKey.of(Scenario.VS_SQUEEZE_OPENER, HJ, BTN, CO), SpotKey.of(Scenario.VS_SQUEEZE_CALLER, CO, BB, HJ),
            SpotKey.of(Scenario.VS_5BET, CO, BTN),
        )
        for (k in keys) {
            val cc = assertNotNull(engine.analyze(k, nl50).coldCall, "$k")
            assertTrue(!cc.cold)
        }
        assertTrue(assertNotNull(engine.analyze(SpotKey.of(Scenario.VS_RFI, SB, UTG), nl50).coldCall).cold)
        assertNull(engine.analyze(SpotKey.of(Scenario.RFI, UTG), nl50).coldCall)
    }

    @Test fun masterEquationConsistency() {
        val a = engine.analyze(SpotKey.of(Scenario.VS_RFI, BTN, CO), nl50)
        for (x in 0 until HandClass.COUNT) {
            val r = a.raiseTerms!![x]
            assertEquals(1.0, r.pFold + r.pCall + r.pReraise, 1e-6)
            assertEquals(r.pFold * r.foldWin + r.pCall * r.calledBranch + r.pReraise * r.evVsReraise, r.ev, 1e-6)
        }
    }

    @Test fun blindDefenseReport() {
        val a = engine.analyze(SpotKey.of(Scenario.VS_RFI, BB, BTN), nl50Share)
        val r = assertNotNull(a.blindDefense)
        assertEquals(1.5 / 4.0, r.mdf, 1e-9)
        assertEquals(1.5 / 5.5, r.potOdds, 1e-9)
        assertTrue(r.defend > 0.25, "defend ${r.defend}")
        assertTrue(r.requiredEquityRealized > r.requiredEquity)
        assertEquals(1f, a.strategy.fold(h("72o")))
    }

    @Test fun blockersRaiseFoldEquity() {
        // Opener continues vs 3bet with the top of his range: an ace blocks it.
        val a = engine.analyze(SpotKey.of(Scenario.VS_RFI, BTN, CO), nl50)
        assertTrue(a.foldEquity[h("A5s")] > a.foldEquity[h("65s")], "${a.foldEquity[h("A5s")]} vs ${a.foldEquity[h("65s")]}")
        // 3bettor bluffs with suited aces: holding an ace blocks his FOLDS -> less fold equity for a 4bet bluff.
        val b = engine.analyze(SpotKey.of(Scenario.VS_3BET, BTN, BB), nl50)
        val vm = engine.villainModel(nl50.villain).threeBettorVs4Bet(BB, BTN)
        assertTrue(Blockers.removal(h("A5s"), vm.fold) > Blockers.removal(h("T9s"), vm.fold))
        assertTrue(b.foldEquity[h("T9s")] > b.foldEquity[h("A5s")])
    }

    @Test fun premiumsGetItIn() {
        val k = SpotKey.of(Scenario.VS_4BET, BTN, CO)
        assertEquals(1f, engine.strategy(k, nl50).raise(h("AA")))
        assertEquals(0f, engine.strategy(k, nl50).fold(h("KK")))
        assertEquals(1f, engine.strategy(k, nl50).fold(h("72o")))
    }

    @Test fun snapshotRoundTrip() {
        engine.strategy(SpotKey.of(Scenario.RFI, CO), nl50)
        val repo = engine.snapshot(nl50)
        val out = ByteArrayOutputStream(); repo.write(out)
        val back = RangeRepository.read(ByteArrayInputStream(out.toByteArray()))
        for (k in repo.keys()) assertEquals(repo[k], back[k])
    }

    @Test fun squeezeResponsesAndFiveBet() {
        val opener = engine.analyze(SpotKey.of(Scenario.VS_SQUEEZE_OPENER, CO, BB, BTN), nl50)
        assertEquals(1f, opener.strategy.raise(h("AA")))
        assertEquals(1f, opener.strategy.fold(h("72o")))
        assertTrue(opener.sprCall > opener.sprRaise)
        val caller = engine.analyze(SpotKey.of(Scenario.VS_SQUEEZE_CALLER, BTN, SB, CO), nl50)
        assertEquals(1f, caller.strategy.fold(h("72o")))
        val five = engine.analyze(SpotKey.of(Scenario.VS_5BET, CO, BTN), nl50)
        assertEquals(1f, five.strategy.call(h("AA")))
        assertEquals(1f, five.strategy.fold(h("72o")))
        assertTrue(five.evRaise(h("AA")).isNaN())
        assertEquals(0.0, five.sprCall, 1e-9)
        val afterSqueeze = engine.analyze(SpotKey.of(Scenario.VS_4BET, BB, CO, BTN), nl50)
        assertEquals(1f, afterSqueeze.strategy.raise(h("AA")))
    }

    @Test fun blindDefenseVsEveryOpener() {
        for (blind in listOf(SB, BB)) {
            val openers = Position.entries.filter { it.ordinal < blind.ordinal }
            val defend = openers.map { o ->
                val r = assertNotNull(engine.analyze(SpotKey.of(Scenario.VS_RFI, blind, o), nl50).blindDefense, "$blind vs $o")
                assertTrue(r.potOdds in 0.0..0.5)
                r.defend
            }
            assertTrue(defend.first() < defend.last(), "$blind defend $defend")
        }
    }

    @Test fun projectedSpr() {
        val a = engine.analyze(SpotKey.of(Scenario.VS_RFI, BB, BTN), nl50)
        assertEquals(97.5 / 5.5, a.sprCall, 1e-9)
        assertEquals(90.0 / 20.5, a.sprRaise, 1e-9)
        val deep = engine.analyze(SpotKey.of(Scenario.VS_RFI, BB, BTN), nl50.copy(effectiveStackBb = 200.0))
        assertTrue(deep.sprCall > a.sprCall)
    }
}
