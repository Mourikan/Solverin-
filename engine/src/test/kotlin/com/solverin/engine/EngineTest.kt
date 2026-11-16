package com.solverin.engine

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.core.Position.*
import com.solverin.engine.core.Stake
import com.solverin.engine.math.Blockers
import com.solverin.engine.math.Realization
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
import kotlin.test.assertTrue

class EngineTest {
    companion object { val engine = PreflopEngine() }
    private val nl50 = PreflopContext(Stake.NL50)
    private fun h(s: String) = HandClass.parse(s)

    private fun allKeys(): List<SpotKey> {
        val out = ArrayList<SpotKey>()
        for (s in Scenario.entries) for (hero in Position.entries) for (v in Position.entries) {
            if (hero == v) continue
            if (s == Scenario.SQUEEZE) {
                for (c in Position.entries) if (c != hero && c != v) {
                    val k = SpotKey.of(s, hero, v, c); if (engine.isValid(k, nl50)) out += k
                }
            } else {
                val k = SpotKey.of(s, hero, v); if (engine.isValid(k, nl50) && (s != Scenario.RFI || v == BB)) out += k
            }
        }
        return out
    }

    @Test fun allSpotsSolveValid() {
        val keys = allKeys()
        assertEquals(5 + 15 + 15 + 15 + 20, keys.size)
        for (stake in listOf(Stake.NL2, Stake.NL200)) for (k in keys) {
            val s = engine.strategy(k, PreflopContext(stake))
            for (x in 0 until HandClass.COUNT) assertTrue(s.raise(x) >= 0 && s.call(x) >= 0 && s.raise(x) + s.call(x) <= 1.0001f)
            assertEquals(1f, s.raise(h("AA")), "AA must raise in $k")
        }
    }

    @Test fun rfiWidensByPosition() {
        val w = listOf(UTG, HJ, CO, BTN).map { engine.strategy(SpotKey.of(Scenario.RFI, it), nl50).raiseFrequency() }
        assertEquals(w, w.sorted(), "RFI $w")
        assertTrue(w[0] in 0.08..0.30, "UTG ${w[0]}")
        assertTrue(w[3] in 0.30..0.75, "BTN ${w[3]}")
        assertEquals(1f, engine.strategy(SpotKey.of(Scenario.RFI, UTG), nl50).fold(h("72o")))
    }

    @Test fun microRakeDropsMarginalBlindFlats() {
        val k = SpotKey.of(Scenario.VS_RFI, BB, BTN)
        val micro = engine.strategy(k, PreflopContext(Stake.NL2))
        val high = engine.strategy(k, PreflopContext(Stake.NL200, villain = PreflopContext(Stake.NL2).villain))
        assertTrue(micro.callFrequency() < high.callFrequency(), "${micro.callFrequency()} vs ${high.callFrequency()}")
    }

    @Test fun oopRealizationPenalty() {
        for (x in listOf("76s", "K9o", "AQo", "22")) {
            assertTrue(Realization.factor(h(x), 15.0, false, false) < Realization.factor(h(x), 15.0, true, false))
        }
        val lowPlay = Realization.factor(h("K4o"), 15.0, false, false) / Realization.factor(h("K4o"), 15.0, true, false)
        val hiPlay = Realization.factor(h("JTs"), 15.0, false, false) / Realization.factor(h("JTs"), 15.0, true, false)
        assertTrue(lowPlay < hiPlay)
    }

    @Test fun blindDefenseReport() {
        val a = engine.analyze(SpotKey.of(Scenario.VS_RFI, BB, BTN), nl50)
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
}
