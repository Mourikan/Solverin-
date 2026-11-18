package com.solverin.engine

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopPolicy
import com.solverin.engine.math.Realization
import com.solverin.engine.pipeline.HandPipelineEngine
import com.solverin.engine.pipeline.HandScript
import com.solverin.engine.pipeline.PreflopAction
import com.solverin.engine.pipeline.PreflopLine
import com.solverin.engine.pipeline.Seat
import com.solverin.engine.pipeline.Street
import com.solverin.engine.preflop.PreflopContext
import com.solverin.engine.preflop.PreflopEngine
import com.solverin.engine.preflop.StakePolicy
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.river.RiverPolicy
import com.solverin.engine.turn.TurnPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The four profitability reforms (override pruning, condensed ranges, preflop discipline, blind trash filter). */
class ProfitReformsTest {
    private val engine = PreflopEngine()
    private fun h(s: String) = HandClass.parse(s)

    @Test fun overrideThresholdsArePruned() {
        val f = FlopPolicy()
        assertEquals(1.5, f.exploitOverrideBb); assertEquals(0.25, f.exploitOverridePotShare); assertEquals(9, f.exploitOverrideOuts)
        val t = TurnPolicy()
        assertEquals(2.5, t.exploitOverrideBb); assertEquals(0.30, t.exploitOverridePotShare); assertTrue(t.exploitBlocker <= 0.90)
        val r = RiverPolicy()
        assertEquals(4.0, r.exploitOverrideBb); assertEquals(0.25, r.exploitPotShare); assertTrue(r.exploitBlocker <= 0.75)
        val p = StakePolicy.of(Stake.NL100)
        assertEquals(1.60, p.massiveExploitEvBb); assertEquals(0.08, p.massiveOverfoldMargin)
        for (x in listOf(t.condensedBluffScale, r.condensedBluffScale)) assertTrue(x in 0.25..0.5, "bluff cap contracts 50–75%")
        assertTrue(t.stackOffEquity >= 0.62f && r.stackOffEquity >= 0.62f)
    }

    @Test fun callingRealizationPenalties() {
        // Facing a re-raise: dominated offsuit broadways, weak suited aces, small pairs without set-mining odds.
        for (x in listOf("ATo", "KJo", "QJo")) assertTrue(Realization.callPenalty(h(x), 2, 2.5, 20.0) < 1.0, x)
        assertTrue(Realization.callPenalty(h("A5s"), 2, 2.5, 20.0) < 1.0)
        assertTrue(Realization.callPenalty(h("55"), 2, 2.5, 10.0) < 1.0)
        assertEquals(1.0, Realization.callPenalty(h("55"), 2, 2.5, 20.0))
        for (x in listOf("AKs", "AKo", "QQ", "AQo")) assertEquals(1.0, Realization.callPenalty(h(x), 2, 2.5, 20.0), x)
        // Blind defense: disconnected offsuit trash; SB flats are sandwiched.
        for (x in listOf("52o", "84o", "73o", "42o", "86o", "K2o", "K6o", "Q7o", "J7o")) assertTrue(Realization.callPenalty(h(x), 1, 1.0, 40.0) < 1.0, x)
        for (x in listOf("KTo", "QJo", "A2o", "T9o", "65s")) assertEquals(1.0, Realization.callPenalty(h(x), 1, 1.0, 40.0), x)
        assertTrue(Realization.callPenalty(h("QTs"), 1, 0.5, 40.0) < 1.0, "SB flat")
    }

    @Test fun blindDefenseNeverPlaysTrashBelowTheMargin() {
        for (stake in listOf(Stake.NL2, Stake.NL10, Stake.NL50, Stake.NL200)) {
            val ctx = PreflopContext(stake)
            val bb = engine.analyze(SpotKey.of(Scenario.VS_RFI, Position.BB, Position.BTN), ctx)
            for (x in listOf("52o", "73o", "84o", "42o")) assertEquals(1f, bb.strategy.fold(h(x)), 1e-6f, "$stake BB $x folds")
            for (o in listOf(Position.UTG, Position.CO, Position.BTN)) {
                val a = engine.analyze(SpotKey.of(Scenario.VS_RFI, Position.BB, o), ctx)
                for (x in 0 until HandClass.COUNT) {
                    val d = a.decision(x)
                    if (d.mix.call > 0f) assertTrue(d.evCall >= ctx.policy.callEdge(Scenario.VS_RFI, Position.BB) - ctx.policy.mixBandBb - 1e-9,
                        "$stake BB vs $o ${HandClass.name(x)} calls below the +0.08 bb margin")
                }
            }
            val sb = engine.analyze(SpotKey.of(Scenario.VS_RFI, Position.SB, Position.CO), ctx)
            for (x in 0 until HandClass.COUNT) {
                val d = sb.decision(x)
                if (d.mix.call > 0f) assertTrue(d.evCall >= ctx.policy.callEdge(Scenario.VS_RFI, Position.SB) - ctx.policy.mixBandBb - 1e-9,
                    "$stake SB ${HandClass.name(x)} flats below the +0.12 bb margin")
            }
        }
    }

    @Test fun reraiseDisciplineFoldsMoreThanThePopulationSolve() {
        val ctx = PreflopContext(Stake.NL100)
        val loose = ctx.copy(policy = ctx.policy.copy(heroDiscipline = false))
        for (k in listOf(SpotKey.of(Scenario.VS_3BET, Position.CO, Position.BTN), SpotKey.of(Scenario.VS_3BET, Position.BTN, Position.BB))) {
            val tight = engine.analyze(k, ctx).foldFrequency()
            val base = engine.analyze(k, loose).foldFrequency()
            assertTrue(tight >= base - 1e-9, "$k folds $tight vs population $base")
        }
    }

    @Test fun villainRangesComeFromThePopulationSolve() {
        // Hero discipline never leaks into the modelled opponent: BB's defend range in the pipeline is the population one.
        val pipeline = HandPipelineEngine(engine)
        val script = HandScript(Stake.NL50, PreflopAction(PreflopLine.RFI_CALL, Position.BTN, Position.BB), Seat.OPENER, "AsKh", "Kd8c4h")
        val r = pipeline.play(script)
        val ctx = PreflopContext(Stake.NL50)
        val pop = engine.analyze(SpotKey.of(Scenario.VS_RFI, Position.BB, Position.BTN), ctx.copy(policy = ctx.policy.copy(heroDiscipline = false)))
        val expected = (0 until HandClass.COUNT).sumOf { pop.strategy.call(it).toDouble() * HandClass.combos(it) }
        assertEquals(expected, r.report(Street.PREFLOP)!!.rangeMass.getValue(Seat.RESPONDER), 0.5)
        assertTrue(Combos.COUNT == 1326)
    }
}
