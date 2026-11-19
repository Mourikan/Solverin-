package com.solverin.engine

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.ComboPartition
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.Draws
import com.solverin.engine.flop.FlopAction
import com.solverin.engine.math.JamGuard
import com.solverin.engine.math.MdfFloor
import com.solverin.engine.pipeline.BayesianFloor
import com.solverin.engine.math.Realization
import com.solverin.engine.model.NodeLock
import com.solverin.engine.model.NodeLocks
import com.solverin.engine.model.VillainNode
import com.solverin.engine.pipeline.HandPipelineEngine
import com.solverin.engine.pipeline.HandScript
import com.solverin.engine.pipeline.PreflopAction
import com.solverin.engine.pipeline.PreflopLine
import com.solverin.engine.pipeline.RiverScript
import com.solverin.engine.pipeline.Seat
import com.solverin.engine.pipeline.Street
import com.solverin.engine.pipeline.StreetKind
import com.solverin.engine.pipeline.StreetScript
import com.solverin.engine.preflop.BluffBudget
import com.solverin.engine.preflop.PreflopContext
import com.solverin.engine.preflop.PreflopEngine
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.river.RiverAction
import com.solverin.engine.river.RiverPolicy
import com.solverin.engine.turn.TurnAction
import com.solverin.engine.turn.TurnBluffCap
import com.solverin.engine.turn.TurnPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regressions of the decision audit (C1–C4 critical, A1–A3 bluff caps / guards, M1 fabricated ranges, M3 monotonicity). */
class DecisionAuditFixesTest {
    private val engine = PreflopEngine()
    private val pipeline = HandPipelineEngine(engine)
    private fun h(s: String) = HandClass.parse(s)

    // ---- C3: premiums vs a 4-bet ---------------------------------------------------------------------------------------

    @Test fun premiumsNeverFoldToAFourBetFromUtgOrMp() {
        val spots = listOf(
            SpotKey.of(Scenario.VS_4BET, Position.HJ, Position.UTG), SpotKey.of(Scenario.VS_4BET, Position.CO, Position.UTG),
            SpotKey.of(Scenario.VS_4BET, Position.BTN, Position.UTG), SpotKey.of(Scenario.VS_4BET, Position.CO, Position.HJ),
            SpotKey.of(Scenario.VS_4BET, Position.BTN, Position.HJ),
        )
        for (stake in Stake.entries) {
            val ctx = PreflopContext(stake)
            for (k in spots) {
                val a = engine.analyze(k, ctx)
                assertEquals(1f, a.strategy.raise(h("AA")), 1e-6f, "$stake $k AA jams")
                for (x in listOf("KK", "QQ", "AKs")) assertTrue(a.strategy.fold(h(x)) < 0.5f, "$stake $k $x continues vs the 4-bet")
                for (x in listOf("AQo", "AJo", "ATo", "KQo")) assertEquals(1f, a.strategy.fold(h(x)), 1e-6f, "$stake $k $x folds")
            }
            // Facing a 5-bet jam: KK always calls; offsuit / suited connectors and underpairs never split.
            for (k in listOf(SpotKey.of(Scenario.VS_5BET, Position.UTG, Position.HJ), SpotKey.of(Scenario.VS_5BET, Position.HJ, Position.CO),
                             SpotKey.of(Scenario.VS_5BET, Position.CO, Position.BTN), SpotKey.of(Scenario.VS_5BET, Position.BTN, Position.BB)))
                assertTrue(engine.analyze(k, ctx).strategy.fold(h("KK")) < 0.5f, "$stake $k KK calls the 5-bet")
            val vs4 = engine.analyze(SpotKey.of(Scenario.VS_4BET, Position.BTN, Position.CO), ctx)
            assertTrue(vs4.strategy.fold(h("T9s")) <= vs4.strategy.fold(h("T9o")) + 1e-6f, "$stake T9s continues at least as often as T9o")
        }
    }

    // ---- C1: no fictitious EV from an empty conditional range ----------------------------------------------------------

    @Test fun blockedRangeNeverAnswersWithAFreeFold() {
        val asKs = Combos.parse("AsKs"); val asQs = Combos.parse("AsQs")
        val raise = FloatArray(Combos.COUNT).also { it[asKs] = 1f }
        val call = FloatArray(Combos.COUNT)
        val fold = FloatArray(Combos.COUNT).also { it[asQs] = 0.5f }
        // Hero holds the A♠: every villain combo is blocked → unconditional split (2/3 raise, 1/3 fold), not fold = 1.
        val p = ComboPartition(raise, call, fold).probabilities(Combos.parse("AsAh"))
        assertEquals(1.0 / 3.0, p[0], 1e-9); assertEquals(0.0, p[1], 1e-12); assertEquals(2.0 / 3.0, p[2], 1e-9)
        val empty = ComboPartition(FloatArray(Combos.COUNT), FloatArray(Combos.COUNT), FloatArray(Combos.COUNT)).probabilities(asKs)
        assertEquals(listOf(0.0, 1.0, 0.0), empty.toList())
    }

    @Test fun aggressionWithoutEquityOrFoldsIsNeverProfitable() {
        val scripts = listOf(
            HandScript(Stake.NL5, PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.BB), Seat.OPENER, "6h4h", "7s8d9h", "9c", "Qs",
                StreetScript(StreetKind.BET_CALL, Seat.RESPONDER), StreetScript(StreetKind.BET_CALL, Seat.OPENER), stackBb = 40.0),
            HandScript(Stake.NL50, PreflopAction(PreflopLine.THREE_BET_CALL, Position.BTN, Position.SB), Seat.OPENER, "QcTs", "5sJd5d", "Kd", "2s",
                StreetScript(StreetKind.BET_CALL, Seat.RESPONDER), StreetScript(StreetKind.BET_CALL, Seat.RESPONDER)),
            HandScript(Stake.NL2, PreflopAction(PreflopLine.THREE_BET_CALL, Position.UTG, Position.HJ), Seat.RESPONDER, "5dQs", "4dTs8d", "2d", "Qd",
                StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.RAISE_CALL, Seat.RESPONDER), RiverScript(1.0)),
        )
        for (s in scripts) {
            val t = pipeline.play(s).report(Street.TURN)?.turn ?: continue
            for (c in 0 until Combos.COUNT) {
                if (t.heroRange[c] <= 0f) continue
                for (a in t.actions) {
                    val x = t.terms(a, c) ?: continue
                    if (a == TurnAction.CHECK || a == TurnAction.CALL || a == TurnAction.FOLD) continue
                    if (x.equity < 0.05f && x.pFold < 0.05) assertTrue(x.ev <= 0.5, "${s.heroHand} ${Combos.name(c)} $a ev=${x.ev} with no equity / folds")
                }
            }
        }
    }

    // ---- C2: a call never costs more than the chips it puts in; the MDF never forces dead hands in --------------------

    @Test fun callLossIsBoundedAndMdfFloorSkipsDeadHands() {
        val s = HandScript(Stake.NL2, PreflopAction(PreflopLine.THREE_BET_CALL, Position.SB, Position.BB), Seat.OPENER, "2cQc", "KhKd7d", "3c", "Tc",
            StreetScript(StreetKind.RAISE_CALL, Seat.RESPONDER), StreetScript(StreetKind.BET_CALL, Seat.RESPONDER, betFraction = 0.05), RiverScript(1.0))
        for (street in listOf(Street.TURN, Street.RIVER)) {
            val rep = pipeline.play(s).report(street) ?: continue
            rep.turn?.let { t ->
                for (c in 0 until Combos.COUNT) if (t.heroRange[c] > 0f) t.terms(TurnAction.CALL, c)?.let { x ->
                    assertTrue(x.ev >= -(x.investment + x.rake) - 1e-6, "turn call ${Combos.name(c)} ev=${x.ev} below −(call + rake)")
                }
            }
            rep.river?.let { r ->
                for (c in 0 until Combos.COUNT) if (r.heroRange[c] > 0f) r.terms(RiverAction.CALL, c)?.let { x ->
                    assertTrue(x.ev >= -(x.investment + x.rake) - 1e-6, "river call ${Combos.name(c)} ev=${x.ev} below −(call + rake)")
                }
            }
        }
        // Floor: the dead hand (equity 0) keeps its fold; the live one is moved to a call.
        val w = FloatArray(4) { 1f }
        val fold = floatArrayOf(1f, 1f, 0f, 0f); val call = floatArrayOf(0f, 0f, 1f, 1f)
        val eq = floatArrayOf(0f, 0.3f, 0.6f, 0.7f); val ev = doubleArrayOf(-50.0, -5.0, 3.0, 4.0)
        val reached = MdfFloor.enforce(listOf(0, 1, 2, 3), w, 0.0, fold, call, 50.0, false, { eq[it] }) { ev[it] }
        assertEquals(1f, fold[0], 1e-6f, "dead hand never forced"); assertEquals(0f, fold[1], 1e-6f, "live hand defends")
        assertEquals(0.25, reached, 1e-9, "the reported fold ceiling is the one actually reached")
        assertTrue(MdfFloor.eligible(-1.2, 1.0, 0.1f, minBet = true) && !MdfFloor.eligible(-0.9, 1.0, 0.01f, minBet = true))
    }

    // ---- C4: 3-way sandwich: a squeeze behind is answered (call the jam with a monster), never a forced give-up -------

    @Test fun sandwichCallsAreMaxEvAndSqueezesAreAnswered() {
        val scripts = listOf(
            HandScript(Stake.NL5, PreflopAction(PreflopLine.RFI_COLD_CALL_BB_CALL, Position.CO, Position.BTN), Seat.OPENER, "3d3c", "8d9dTc", "4s", "2d",
                StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.RESPONDER), StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.THIRD), RiverScript(1.5)),
            HandScript(Stake.NL50, PreflopAction(PreflopLine.RFI_COLD_CALL_BB_CALL, Position.UTG, Position.CO), Seat.RESPONDER, "5d7c", "8sAs3s", "Kd", "Td",
                StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.THIRD), StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.THIRD), RiverScript(1.0)),
            HandScript(Stake.NL2, PreflopAction(PreflopLine.RFI_COLD_CALL_BB_CALL, Position.HJ, Position.BTN), Seat.THIRD, "QsTs", "5h6d7s", "As", "Kd",
                StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.RESPONDER, 0.05), StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.THIRD), RiverScript(0.5), stackBb = 150.0),
        )
        var squeezed = 0
        for (s in scripts) {
            val r = pipeline.play(s).report(Street.RIVER)?.river ?: continue
            if (RiverAction.FOLD !in r.actions) continue
            for (c in 0 until Combos.COUNT) {
                if (r.heroRange[c] <= 0f) continue
                val x = r.terms(RiverAction.CALL, c) ?: continue
                // Facing the squeeze hero never does worse than folding the call, and does better with a hand that beats it.
                assertTrue(x.evVsRaise >= -x.investment - 1e-9, "${Combos.name(c)} squeeze answer ${x.evVsRaise} below giving up")
                if (x.pRaise > 0.0) squeezed++
                if (x.ev > 0.5) assertTrue(r.frequency(RiverAction.FOLD, c) < 0.5f, "${Combos.name(c)} folds a +EV call (${x.ev})")
            }
        }
        assertTrue(squeezed > 0, "the sweep met squeezable sandwich calls")
    }

    // ---- A1 / A3: value is never cut as a bluff ---------------------------------------------------------------------

    @Test fun premiumsAreValueAndTheTotalCapTrimsBluffsFirst() {
        val policy = PreflopContext(Stake.NL50).policy
        for (s in Scenario.entries.filter { it != Scenario.RFI }) for (x in listOf("AA", "KK", "QQ", "AKs", "AKo"))
            assertTrue(BluffBudget.isValue(s, h(x), 0.30f, policy, 100.0), "$s $x is value")
        // QQ facing a cold 3-bet vs a NIT defend read: +EV, never folded by the bluff budget.
        var nit = NodeLocks.NONE
        for (p in Position.entries) nit = nit.with(VillainNode.DEFEND_VS_OPEN, p, NodeLock(raise = 0.03, call = 0.13))
        for (stake in listOf(Stake.NL50, Stake.NL100)) {
            val a = engine.analyze(SpotKey.of(Scenario.VS_3BET_COLD, Position.SB, Position.HJ, Position.UTG), PreflopContext(stake, locks = nit))
            assertTrue(a.strategy.fold(h("QQ")) < 0.5f, "$stake QQ vs cold 3-bet (NIT read) continues")
        }
        // Total cap 25%: the value raise (index 0) survives, the bluff (index 1) is trimmed.
        val w = floatArrayOf(1f, 1f, 1f, 1f)
        val agg = floatArrayOf(1f, 1f, 0f, 0f); val pas = floatArrayOf(0f, 0f, 1f, 1f); val capped = BooleanArray(Combos.COUNT)
        TurnBluffCap.capTotal(listOf(0, 1, 2, 3), w, 0.25, agg, pas, null, capped,
            aggEv = { if (it == 0) 2.0 else 3.0 }, passiveEv = { 0.0 }, foldAllowed = { false }, protect = { it == 0 })
        assertEquals(1f, agg[0], 1e-6f); assertEquals(0f, agg[1], 1e-6f); assertTrue(capped[1] && !capped[0])
        assertEquals(1.5, TurnPolicy().thinValueGuardEvBb); assertEquals(1.5, RiverPolicy().thinValueGuardEvBb)
    }

    // ---- M1: no range fabricated from the deck ------------------------------------------------------------------------

    @Test fun lineAbsentFromTheSolveKeepsTheContinuingRange() {
        val s = HandScript(Stake.NL50, PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.SB), Seat.RESPONDER, "AcKd", "7d8c9s", "6s", "9c",
            StreetScript(StreetKind.CHECK_CHECK), StreetScript(StreetKind.CHECK_CHECK), RiverScript(0.5))
        val r = pipeline.play(s)
        val pre = r.report(Street.PREFLOP)!!.rangeMass.getValue(Seat.RESPONDER)
        val flop = r.report(Street.FLOP)!!
        assertTrue(pre >= 1.0, "SB's line keeps a real range preflop ($pre combos)")
        assertTrue(flop.rangeMass.getValue(Seat.RESPONDER) <= pre + 1e-6, "the flop range never grows from nothing")
        assertTrue(Seat.RESPONDER !in flop.smoothed, "no Bayesian-floor fabrication")
    }

    // ---- M3: monotone dominance ------------------------------------------------------------------------------------------

    @Test fun dominanceIsMonotone() {
        assertEquals(Realization.callPenalty(h("ATs"), 3, 0.0, 10.0) / Realization.DOMINATED_VS_FOUR_BET * Realization.DOMINATED_VS_FOUR_BET,
            Realization.callPenalty(h("ATs"), 3, 0.0, 10.0), 1e-12)
        assertTrue(Realization.callPenalty(h("ATo"), 3, 0.0, 10.0) <= Realization.callPenalty(h("ATs"), 3, 0.0, 10.0), "ATo never realizes more than ATs vs a 4-bet")
        val pairs = listOf("99", "88", "77", "66", "55", "44", "33", "22")
        for (stake in Stake.entries) {
            val a = engine.analyze(SpotKey.of(Scenario.SQUEEZE, Position.BB, Position.UTG, Position.HJ), PreflopContext(stake))
            for (i in 1 until pairs.size) {
                val hi = 1f - a.strategy.fold(h(pairs[i - 1])); val lo = 1f - a.strategy.fold(h(pairs[i]))
                assertTrue(hi >= lo - 1e-6f, "$stake ${pairs[i - 1]} continues ($hi) at least as often as ${pairs[i]} ($lo)")
            }
        }
    }

    // ---- Postflop closing patches: multiway floor, jam guard, no implied odds without equity -------------------------

    @Test fun multiwayFloorBlendsADegenerateThirdRange() {
        val board = intArrayOf(com.solverin.engine.core.Card.parse("8s"), com.solverin.engine.core.Card.parse("As"), com.solverin.engine.core.Card.parse("3s"))
        val prior = FloatArray(Combos.COUNT) { 0.05f }
        val range = FloatArray(Combos.COUNT).also { it[Combos.parse("KsQs")] = 1f; it[Combos.parse("Ks5s")] = 1f }
        assertTrue(BayesianFloor.multiway(range, prior, board))
        assertEquals(BayesianFloor.MULTIWAY_MIN_COMBOS, Combos.total(range), 1e-3, "floor mass reached")
        assertTrue(range[Combos.parse("7d7c")] > 0f, "non-nut hands of his previous range are back")
        val wide = FloatArray(Combos.COUNT) { 1f }
        assertTrue(!BayesianFloor.multiway(wide, prior, board), "a healthy range is untouched")
    }

    @Test fun noSuicidalJamsAndNoImpliedOddsDrawingDead() {
        assertTrue(JamGuard.suicidal(true, 0.10f, 0.20))
        assertTrue(!JamGuard.suicidal(true, 0.20f, 0.20, outs = 9), "a 9-out draw is a semi-bluff")
        assertTrue(!JamGuard.suicidal(false, 0.10f, 0.20) && !JamGuard.suicidal(true, 0.10f, 0.40))
        assertTrue(JamGuard.blockedVsRead(true, 0.20f, true, 1.0) && !JamGuard.blockedVsRead(true, 0.20f, true, 5.0))
        val scripts = listOf(
            HandScript(Stake.NL5, PreflopAction(PreflopLine.RFI_CALL, Position.BTN, Position.SB), Seat.OPENER, "QsAs", "5cKc8c", "Qd", "8s",
                StreetScript(StreetKind.BET_CALL, Seat.RESPONDER, 0.05), StreetScript(StreetKind.BET_CALL, Seat.RESPONDER)),
            HandScript(Stake.NL100, PreflopAction(PreflopLine.RFI_COLD_CALL_BB_CALL, Position.HJ, Position.BTN), Seat.RESPONDER, "JsQd", "AdAs9h", "9s", "7h",
                StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.RESPONDER, 1.25), StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.THIRD), stackBb = 40.0),
            HandScript(Stake.NL200, PreflopAction(PreflopLine.THREE_BET_CALL, Position.SB, Position.BB), Seat.RESPONDER, "KsJh", "Kh8dJs", "8h", "6d",
                StreetScript(StreetKind.BET_CALL, Seat.RESPONDER), StreetScript(StreetKind.BET_CALL, Seat.RESPONDER), RiverScript(0.33)),
        )
        for (s in scripts) {
            val r = pipeline.play(s)
            r.report(Street.FLOP)?.flop?.let { a ->
                for (c in 0 until Combos.COUNT) if (a.heroRange[c] > 0f) {
                    a.terms(FlopAction.CALL, c)?.let { x -> if (x.equity < MdfFloor.MIN_LIVE_EQUITY) assertEquals(0.0, x.implied, 0.0, "${Combos.name(c)} implied odds drawing dead") }
                    for (act in listOf(FlopAction.BET, FlopAction.RAISE, FlopAction.DONK)) a.terms(act, c)?.let { x ->
                        if (a.frequency(act, c) > 0.5f) assertTrue(!JamGuard.suicidal(x.allIn, x.equity, x.pFold, Draws.outs(a.spot.board, c)), "flop ${Combos.name(c)} suicidal $act")
                    }
                }
            }
            r.report(Street.TURN)?.turn?.let { a ->
                for (c in 0 until Combos.COUNT) if (a.heroRange[c] > 0f) {
                    a.terms(TurnAction.CALL, c)?.let { x -> if (x.equity < MdfFloor.MIN_LIVE_EQUITY) assertEquals(0.0, x.impliedRiver, 0.0, "${Combos.name(c)} turn implied drawing dead") }
                }
            }
            r.report(Street.RIVER)?.river?.let { a ->
                for (c in 0 until Combos.COUNT) if (a.heroRange[c] > 0f) for (act in a.actions) {
                    val x = a.terms(act, c) ?: continue
                    if (act != RiverAction.CHECK && act != RiverAction.CALL && act != RiverAction.FOLD && a.frequency(act, c) > 0.5f)
                        assertTrue(!JamGuard.suicidal(x.allIn, x.equity, x.pFold), "river ${Combos.name(c)} suicidal $act eq=${x.equity} pF=${x.pFold}")
                }
            }
        }
    }
}
