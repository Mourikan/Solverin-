package com.solverin.engine

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopNodeLock
import com.solverin.engine.flop.FlopNodeLocks
import com.solverin.engine.flop.FlopPolicy
import com.solverin.engine.flop.FlopVillainNode
import com.solverin.engine.math.Realization
import com.solverin.engine.pipeline.HandLocks
import com.solverin.engine.pipeline.HandPipelineEngine
import com.solverin.engine.pipeline.HandScript
import com.solverin.engine.pipeline.PreflopAction
import com.solverin.engine.pipeline.PreflopLine
import com.solverin.engine.pipeline.Seat
import com.solverin.engine.pipeline.Street
import com.solverin.engine.pipeline.StreetKind
import com.solverin.engine.pipeline.StreetScript
import com.solverin.engine.preflop.PreflopContext
import com.solverin.engine.preflop.PreflopEngine
import com.solverin.engine.preflop.StakePolicy
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.river.RiverNodeLock
import com.solverin.engine.river.RiverNodeLocks
import com.solverin.engine.river.RiverPolicy
import com.solverin.engine.river.RiverVillainNode
import com.solverin.engine.turn.TurnNodeLock
import com.solverin.engine.turn.TurnNodeLocks
import com.solverin.engine.turn.TurnPolicy
import com.solverin.engine.turn.TurnVillainNode
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

    @Test fun closingReformsOverfoldLockAndPersistence() {
        assertEquals(0.06, FlopPolicy().overrideOverfoldMargin); assertEquals(0.06, TurnPolicy().overrideOverfoldMargin)
        assertEquals(0.08, RiverPolicy().overrideOverfoldMargin)
        assertEquals(0.88, TurnPolicy().persistenceTightening); assertEquals(0.82, RiverPolicy().persistenceTightening)
        assertEquals(0.54f, TurnPolicy().thinValueVsCallers); assertEquals(0.54f, RiverPolicy().thinValueVsCallers)
        for (s in listOf(Stake.NL5, Stake.NL10, Stake.NL25)) assertTrue(StakePolicy.of(s).callEdgeBb >= 0.10, "$s flat edge")
    }

    @Test fun fourBetAndFiveBetDiscipline() {
        val ctx = PreflopContext(Stake.NL100)
        // Facing a population 5-bet jam (range collapsed to ~top 2.2%): AA / KK continue, dominated hands fold.
        val five = engine.analyze(SpotKey.of(Scenario.VS_5BET, Position.BTN, Position.BB), ctx)
        for (x in listOf("AA", "KK")) assertTrue(five.strategy.fold(h(x)) < 0.5f, "$x calls the 5-bet")
        for (x in listOf("AQs", "AJs", "JJ", "TT")) assertEquals(1f, five.strategy.fold(h(x)), 1e-6f, "$x folds to the 5-bet")
        // Facing a 4-bet: easily dominated hands never flat.
        val four = engine.analyze(SpotKey.of(Scenario.VS_4BET, Position.BB, Position.BTN), ctx)
        for (x in listOf("AQo", "AJo", "KQo", "ATs", "KJs", "QJs")) assertEquals(0f, four.strategy.call(h(x)), 1e-6f, "$x does not flat a 4-bet")
        // MP (HJ) open needs +0.08 bb: the open range is never wider than the population solve.
        val tight = engine.analyze(SpotKey.of(Scenario.RFI, Position.HJ), ctx).strategy.raiseFrequency()
        val loose = engine.analyze(SpotKey.of(Scenario.RFI, Position.HJ), ctx.copy(policy = ctx.policy.copy(heroDiscipline = false))).strategy.raiseFrequency()
        assertTrue(tight <= loose + 1e-9, "MP opens $tight vs $loose")
    }

    /** NIT read on every street: over-folds facing bets (0.72 / 0.78 / 0.83) with a value-heavy range (0.93 / 0.90). */
    private fun nitLocks(fold: Double = 0.72, valueShare: Double? = 0.93): HandLocks {
        val flop = FlopNodeLocks.NONE
            .with(FlopVillainNode.VS_BET, FlopNodeLock(fold = fold, raise = 0.04))
            .with(FlopVillainNode.VS_RAISE, FlopNodeLock(fold = 0.78, raise = 0.04))
            .with(FlopVillainNode.CBET, FlopNodeLock(frequency = 0.32, valueShare = valueShare))
        var turn = TurnNodeLocks.NONE
        for (n in listOf(TurnVillainNode.VS_SECOND_BARREL, TurnVillainNode.VS_DELAYED_CBET, TurnVillainNode.VS_PROBE, TurnVillainNode.VS_BET))
            turn = turn.with(n, TurnNodeLock(fold = fold, raise = 0.04))
        turn = turn.with(TurnVillainNode.VS_TURN_RAISE, TurnNodeLock(fold = 0.78, raise = 0.04))
            .with(TurnVillainNode.BARREL, TurnNodeLock(frequency = 0.32, valueShare = valueShare))
        val river = RiverNodeLocks.NONE
            .with(RiverVillainNode.VS_BET, RiverNodeLock(fold = fold, raise = 0.04))
            .with(RiverVillainNode.VS_RAISE, RiverNodeLock(fold = 0.78))
            .with(RiverVillainNode.BET, RiverNodeLock(frequency = 0.32, valueShare = valueShare?.let { minOf(it, 0.90) }))
        return HandLocks(flop = flop, turn = turn, river = river)
    }

    @Test fun nitReadIsDetectedOnEveryStreet() {
        val nit = nitLocks()
        assertTrue(nit.flop.nitRead && nit.turn.nitRead && nit.river.nitRead)
        // A pure fold lock (no value-heavy read), a station and a maniac are not NIT reads.
        val foldOnly = nitLocks(valueShare = null)
        assertTrue(!foldOnly.flop.nitRead && !foldOnly.turn.nitRead && !foldOnly.river.nitRead)
        val station = nitLocks(fold = 0.12, valueShare = 0.85)
        assertTrue(!station.flop.nitRead && !station.turn.nitRead && !station.river.nitRead)
        val maniac = nitLocks(fold = 0.20, valueShare = 0.40)
        assertTrue(!maniac.flop.nitRead && !maniac.turn.nitRead && !maniac.river.nitRead)
        assertTrue(!FlopNodeLocks.NONE.nitRead && !TurnNodeLocks.NONE.nitRead && !RiverNodeLocks.NONE.nitRead)
    }

    @Test fun massiveExploitOverrideNeverFiresVsNit() {
        val pipeline = HandPipelineEngine(engine)
        val scripts = listOf(
            HandScript(Stake.NL100, PreflopAction(PreflopLine.RFI_CALL, Position.UTG, Position.BB), Seat.OPENER, "AsQd",
                "KsTs6h", "2d", "3s", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER)),
            HandScript(Stake.NL25, PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.BTN), Seat.OPENER, "AcQc",
                "Kd8c4h", "2s", "7c", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER)),
            HandScript(Stake.NL50, PreflopAction(PreflopLine.RFI_CALL, Position.BTN, Position.BB), Seat.OPENER, "9h8h",
                "Th7c2h", "3d", "Kc", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER)),
            HandScript(Stake.NL200, PreflopAction(PreflopLine.RFI_CALL, Position.HJ, Position.BB), Seat.RESPONDER, "6s5s",
                "Ah7s4d", "Qs", "2c", StreetScript(StreetKind.CHECK_CHECK), StreetScript(StreetKind.CHECK_CHECK)),
        )
        for (base in scripts) {
            val r = pipeline.play(base.copy(locks = nitLocks()))
            var analysed = 0
            r.report(Street.FLOP)?.flop?.let { a ->
                analysed++
                for (c in 0 until Combos.COUNT) if (a.heroRange[c] > 0f) assertTrue(!a.overrideFired(c), "flop override vs NIT ${base.heroHand} combo $c")
            }
            r.report(Street.TURN)?.turn?.let { a ->
                analysed++
                for (c in 0 until Combos.COUNT) if (a.heroRange[c] > 0f) assertTrue(!a.overrideFired(c), "turn override vs NIT ${base.heroHand} combo $c")
            }
            r.report(Street.RIVER)?.river?.let { a ->
                analysed++
                for (c in 0 until Combos.COUNT) if (a.heroRange[c] > 0f) assertTrue(!a.overrideFired(c), "river override vs NIT ${base.heroHand} combo $c")
            }
            assertEquals(3, analysed, "${base.heroHand}: flop, turn and river solved")
        }
    }

    @Test fun coAndBbFoldDominatedHandsToAFourBet() {
        for (stake in listOf(Stake.NL25, Stake.NL100)) {
            val ctx = PreflopContext(stake)
            val p = ctx.policy
            // The CO / BB margins sit on top of the re-raise margins; other seats and the population solve are untouched.
            for (hero in listOf(Position.CO, Position.BB)) {
                assertEquals(p.callEdge(Scenario.VS_4BET, Position.BTN) + p.fourBetCoBbCallEdgeBb, p.callEdge(Scenario.VS_4BET, hero), 1e-12)
                assertEquals(p.raiseEdge(Scenario.VS_4BET, Position.BTN) + p.fourBetCoBbRaiseEdgeBb, p.raiseEdge(Scenario.VS_4BET, hero), 1e-12)
                assertEquals(p.callEdge(Scenario.VS_4BET, Position.BTN), p.callEdge(Scenario.VS_4BET, hero, h("KK")), 1e-12, "premium exempt")
            }
            val pop = p.copy(heroDiscipline = false)
            assertEquals(pop.callEdge(Scenario.VS_4BET, Position.CO), pop.callEdge(Scenario.VS_4BET, Position.BTN), 1e-12)
            // Same engine without the CO / BB reform: no extra margins, 4-bet range at the generic top 4.5%.
            val neutral = ctx.copy(policy = p.copy(fourBetCoBbCallEdgeBb = 0.0, fourBetCoBbRaiseEdgeBb = 0.0, fourBetCoBbRangeTop = 1.0))
            for ((hero, villain) in listOf(Position.CO to Position.HJ, Position.CO to Position.UTG, Position.BB to Position.BTN, Position.BB to Position.CO)) {
                val key = SpotKey.of(Scenario.VS_4BET, hero, villain)
                val a = engine.analyze(key, ctx)
                val base = engine.analyze(key, neutral)
                for (x in listOf("AJo", "KQo", "KJs", "QJs", "T9s", "77", "88", "AQo", "ATs"))
                    assertEquals(1f, a.strategy.fold(h(x)), 1e-6f, "$stake $hero vs $villain $x folds to the 4-bet")
                assertTrue(a.strategy.fold(h("AA")) < 0.5f, "$stake $hero vs $villain AA continues vs the 4-bet")
                for (x in listOf("KK", "AKs")) {
                    assertEquals(base.strategy.raise(h(x)), a.strategy.raise(h(x)), 1e-6f, "$stake $hero vs $villain $x keeps its 5-bet line")
                    assertEquals(base.strategy.call(h(x)), a.strategy.call(h(x)), 1e-6f, "$stake $hero vs $villain $x keeps its flat line")
                }
                for (x in 0 until HandClass.COUNT) {
                    val d = a.decision(x)
                    if (d.mix.call > 0f) assertTrue(d.evCall >= p.callEdge(Scenario.VS_4BET, hero, x) - p.mixBandBb - 1e-9,
                        "$stake $hero ${HandClass.name(x)} flats a 4-bet below the CO / BB edge")
                    // Never wider than without the reform.
                    assertTrue(a.strategy.fold(x) >= base.strategy.fold(x) - 1e-6f || StakePolicy.premiumVsFourBet(x),
                        "$stake $hero vs $villain ${HandClass.name(x)} continues more than before the reform")
                }
            }
        }
        // BB keeps defending KK / AA vs the late-position 4-bet.
        val bb = engine.analyze(SpotKey.of(Scenario.VS_4BET, Position.BB, Position.BTN), PreflopContext(Stake.NL100))
        for (x in listOf("AA", "KK")) assertTrue(bb.strategy.fold(h(x)) < 0.5f, "BB $x continues vs BTN 4-bet")
    }
}
