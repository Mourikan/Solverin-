package com.solverin.engine

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.math.Blockers
import com.solverin.engine.math.Playability
import com.solverin.engine.math.Sizing
import com.solverin.engine.math.SprProjector
import com.solverin.engine.math.VillainProfile
import com.solverin.engine.preflop.PreflopContext
import com.solverin.engine.preflop.PreflopEngine
import com.solverin.engine.range.RangeParser
import com.solverin.engine.range.RangeRepository
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.range.SpotStrategy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineTest {
    private val engine = PreflopEngine()
    private val repo = engine.repository

    private fun valid(s: SpotStrategy) {
        for (h in 0 until HandClass.COUNT) {
            assertTrue(s.raise(h) >= 0f && s.call(h) >= 0f && s.raise(h) + s.call(h) <= 1.0001f, HandClass.name(h))
        }
    }

    @Test fun rfiWidths() {
        val utg = repo.require(SpotKey.of(Scenario.RFI, Position.UTG)).raiseFrequency()
        val btn = repo.require(SpotKey.of(Scenario.RFI, Position.BTN)).raiseFrequency()
        assertTrue(utg in 0.13..0.22, "UTG $utg")
        assertTrue(btn in 0.38..0.55, "BTN $btn")
    }

    @Test fun repositoryCoversAllNodesAndRoundTrips() {
        assertEquals(5 + 15 + 15 + 15 + 20, repo.size)
        assertTrue(repo.distinctStrategies() < repo.size)
        val out = ByteArrayOutputStream()
        repo.write(out)
        assertTrue(out.size() < 16_000, "binary ${out.size()} B")
        val back = RangeRepository.read(ByteArrayInputStream(out.toByteArray()))
        for (k in repo.keys()) assertEquals(repo[k], back[k])
    }

    @Test fun spr() {
        val p = SprProjector(Sizing(), 100.0)
        val rfi = p.afterRaise(SpotKey.of(Scenario.RFI, Position.BTN))
        assertEquals(5.5, rfi.potBb, 1e-9)
        assertEquals(97.5 / 5.5, rfi.spr, 1e-9)
        val flat = p.afterCall(SpotKey.of(Scenario.VS_RFI, Position.BB, Position.BTN))!!
        assertEquals(5.5, flat.potBb, 1e-9)
        val threeBetIp = p.afterRaise(SpotKey.of(Scenario.VS_RFI, Position.BTN, Position.CO))
        assertEquals(16.5, threeBetIp.potBb, 1e-9)
    }

    @Test fun blockers() {
        val cont = RangeParser.parse("QQ+,AK")
        val fold = RangeParser.parse("JJ-22,AQs-A2s,KQs-K9s,QJs,JTs,T9s,98s,87s,76s,65s")
        val ak = HandClass.parse("AKo"); val trash = HandClass.parse("72o")
        assertTrue(Blockers.removal(ak, cont) > Blockers.removal(trash, cont))
        val s = Blockers.bluffScores(cont, fold)
        assertTrue(s[HandClass.parse("A5s")] > s[HandClass.parse("76s")])
        assertTrue(s[HandClass.parse("KK")] > 0f)
    }

    @Test fun playabilityBySpr() {
        val sc = HandClass.parse("76s"); val ako = HandClass.parse("AJo")
        assertTrue(Playability.score(sc, 20.0) > Playability.score(sc, 2.0))
        assertTrue(Playability.score(ako, 2.0) > Playability.score(sc, 2.0))
    }

    @Test fun adjustedStrategiesAreValid() {
        for (stake in Stake.entries) for (k in repo.keys()) valid(engine.strategy(k, PreflopContext(stake)))
    }

    @Test fun lowerRakeWidensFlats() {
        val k = SpotKey.of(Scenario.VS_RFI, Position.BB, Position.BTN)
        val hiRake = engine.strategy(k, PreflopContext(Stake.NL2, rake = com.solverin.engine.core.RakeStructure(0.07, 10.0)))
        val loRake = engine.strategy(k, PreflopContext(Stake.NL200))
        assertTrue(loRake.callFrequency() > hiRake.callFrequency(), "${loRake.callFrequency()} vs ${hiRake.callFrequency()}")
    }

    @Test fun shortStackCutsSpeculativeFlats() {
        val k = SpotKey.of(Scenario.VS_RFI, Position.BB, Position.BTN)
        val h = HandClass.parse("65s")
        val deep = engine.strategy(k, PreflopContext(Stake.NL50, effectiveStackBb = 150.0)).call(h)
        val short = engine.strategy(k, PreflopContext(Stake.NL50, effectiveStackBb = 40.0)).call(h)
        assertTrue(deep > short, "$deep vs $short")
    }

    @Test fun overfoldingVillainGetsMoreThreeBets() {
        val k = SpotKey.of(Scenario.VS_RFI, Position.BTN, Position.CO)
        val nit = engine.strategy(k, PreflopContext(Stake.NL50, villain = VillainProfile.NASH.copy(foldVs3Bet = 0.70)))
        val station = engine.strategy(k, PreflopContext(Stake.NL50, villain = VillainProfile.NASH.copy(foldVs3Bet = 0.40)))
        assertTrue(nit.raiseFrequency() > station.raiseFrequency())
        val a5 = HandClass.parse("A5s")
        assertTrue(nit.raise(a5) > station.raise(a5))
    }

    @Test fun neutralContextReproducesBaseline() {
        val ctx = PreflopContext(Stake.NL50, rake = Stake.REFERENCE_RAKE, villain = VillainProfile.NASH)
        for (k in repo.keys()) {
            val b = repo.require(k); val a = engine.strategy(k, ctx)
            assertEquals(b.raiseFrequency(), a.raiseFrequency(), 0.01, "raise $k")
            assertEquals(b.callFrequency(), a.callFrequency(), 0.01, "call $k")
        }
    }

    @Test fun decideApi() {
        val mix = engine.decide(SpotKey.of(Scenario.RFI, Position.UTG), "AA", PreflopContext(Stake.NL10))
        assertEquals(1f, mix.raise)
        val fold = engine.decide(SpotKey.of(Scenario.RFI, Position.UTG), "72o", PreflopContext(Stake.NL10))
        assertEquals(1f, fold.fold)
    }
}
