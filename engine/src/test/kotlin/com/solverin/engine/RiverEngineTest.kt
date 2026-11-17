package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopContext
import com.solverin.engine.flop.FlopSpot
import com.solverin.engine.river.*
import com.solverin.engine.turn.FlopLine
import com.solverin.engine.turn.FlopToTurnRangeFilter
import com.solverin.engine.turn.TurnContext
import com.solverin.engine.turn.TurnSpot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

object RiverInvariants {
    fun all(a: RiverAnalysis, tag: String = "") {
        val spot = a.spot
        val stack = spot.effectiveStackBb
        var total = 0.0; var folded = 0.0; var raised = 0.0
        for (k in 0 until Combos.COUNT) {
            val w = a.heroRange[k]
            if (w <= 0f) continue
            assertTrue(w.isFinite() && w in 0f..1f, "$tag weight")
            assertEquals(1.0, a.actions.sumOf { a.frequency(it, k).toDouble() }, 1e-4, "$tag freq sum ${Combos.name(k)}")
            total += w; folded += w * a.frequency(RiverAction.FOLD, k); raised += w * a.frequency(RiverAction.RAISE, k)
            for (act in a.actions) {
                val t = a.terms(act, k) ?: continue
                assertTrue(t.ev.isFinite(), "$tag ev finite ${Combos.name(k)} $act")
                assertEquals(1.0, t.pFold + t.pCall + t.pRaise, 1e-6, "$tag prob sum ${Combos.name(k)} $act")
                assertEquals(t.pFold * t.foldWin + t.pCall * t.calledEv + t.pRaise * t.evVsRaise, t.ev, 1e-6, "$tag EV identity ${Combos.name(k)} $act")
                assertTrue(t.investment <= stack + 1e-6, "$tag investment ≤ stack ${Combos.name(k)} $act")
                assertEquals(1f, t.realization, 0f); assertEquals(0.0, t.impliedRiver, 0.0); assertEquals(0.0, t.rioPenalty, 0.0)
                assertTrue(t.equity in 0f..1f, "$tag equity range")
                if (a.frequency(act, k) > 0f && act != RiverAction.CHECK && act != RiverAction.CALL) assertTrue(t.sizeBb <= stack + 1e-6)
            }
            if (a.minBet) {
                val c = a.terms(RiverAction.CALL, k)!!
                if (c.equity >= a.potOdds!! || c.ev > 0.0) assertEquals(0f, a.frequency(RiverAction.FOLD, k), 1e-6f, "$tag min-bet no-fold ${Combos.name(k)}")
            }
            val size = a.betSize(k)
            if (!size.isNaN()) assertTrue(size * spot.potBb <= stack + 1e-6, "$tag size ≤ stack")
        }
        a.maxFold?.let { assertTrue(folded / total <= it + 1e-6, "$tag MDF floor") }
        if (a.nodeType == RiverNodeType.VS_BET && !spot.heroInPosition) assertTrue(raised / total <= RiverPolicy().checkRaiseCap + 1e-6, "$tag check-raise cap")
        assertTrue(a.rangeEv().isFinite())
    }
}

class RiverEngineTest {
    private val ctx = RiverContext(Stake.NL50)
    private fun k(s: String) = Combos.parse(s)
    private fun c(s: String) = Card.parse(s)

    private fun direct(b: String, hero: String, villain: String, ip: Boolean, agg: Boolean, line: RiverLine, pot: Double, stack: Double, bet: Double = 0.0): RiverSession {
        val x = FlopBoards.board(b)
        return RiverSession(RiverSpot(x, FlopBoards.range(hero, x), FlopBoards.range(villain, x), ip, agg, line, pot, stack, bet))
    }

    private fun turnSpot(flop: String, turn: String): TurnSpot {
        val f = FlopBoards.board(flop); val t = c(turn)
        val tr = FlopToTurnRangeFilter.fromFlop(FlopSpot(f, FlopBoards.range(FlopBoards.CO_OPEN, f), FlopBoards.range(FlopBoards.BB_DEFEND, f), true, true, 5.5, 97.5),
            FlopContext(Stake.NL50), FlopLine.BET_CALL, t)
        return tr.spot(f, t)
    }

    @Test fun fifthCardClassification() {
        fun cat(turnBoard: String, river: String) = RiverCardImpactEngine.classify(FlopBoards.board(turnBoard), c(river))
        assertEquals(RiverCardCategory.FRONT_DOOR_FLUSH_COMPLETING, cat("KsTs6h2d", "3s").category)
        assertEquals(RiverCardCategory.BACKDOOR_FLUSH_COMPLETING, cat("Ks7h2d9h", "4h").category)
        val ff = cat("Qs9s4s2c", "7s")
        assertEquals(RiverCardCategory.FOUR_TO_A_FLUSH_OR_STRAIGHT, ff.category); assertEquals(c("2s") and 3, ff.fourFlushSuit)
        val fs = cat("9h8d7c2s", "6h")
        assertTrue(fs.oneCardStraight && fs.category == RiverCardCategory.FOUR_TO_A_FLUSH_OR_STRAIGHT)
        assertEquals(RiverCardCategory.BOARD_PAIRING_RIVER, cat("KsTs6h2d", "6c").category)
        assertEquals(RiverCardCategory.OVERCARD_RIVER, cat("9s8d4c2h", "Ac").category)
        val brick = cat("9s8s4d2c", "Jd")
        assertEquals(RiverCardCategory.MISSED_DRAWS_BRICK, brick.category); assertTrue(brick.drawHeavyTurn)
        assertTrue(cat("KsTs6h2d", "3s").completing && cat("KsTs6h2d", "6c").completing && !brick.completing)
        assertFailsWith<IllegalArgumentException> { cat("KsTs6h2d", "Ks") }
        assertEquals(c("Ts") ushr 2, RiverCardImpactEngine.topStraightCard(FlopBoards.board("9h8d7c2s6h")))
    }

    @Test fun turnToRiverBridgeIsBayesian() {
        val ts = turnSpot("JhTh4c", "2c")
        val river = c("3d")
        for (line in listOf(RiverLine.BARREL_CALL, RiverLine.CHECK_CHECK_TURN, RiverLine.TURN_RAISE_CALL)) {
            val rr = TurnToRiverRangeFilter.fromTurn(ts, TurnContext(Stake.NL50), line, river)
            for (r in listOf(rr.hero, rr.villain)) {
                assertTrue(r.all { it.isFinite() && it in 0f..1f })
                for (x in Combos.BY_CARD[river]) assertEquals(0f, r[x], "$line river card removed")
            }
            for (x in 0 until Combos.COUNT) { assertTrue(rr.hero[x] <= ts.heroRange[x] + 1e-6f); assertTrue(rr.villain[x] <= ts.villainRange[x] + 1e-6f) }
            assertTrue(rr.potBb >= ts.potBb && rr.effectiveStackBb <= ts.effectiveStackBb)
            if (rr.hero.any { it > 0f } && rr.villain.any { it > 0f }) RiverInvariants.all(RiverSession(rr.spot(ts.board, river)).analyze(ctx), "bridge $line")
        }
        val bc = TurnToRiverRangeFilter.fromTurn(ts, TurnContext(Stake.NL50), RiverLine.BARREL_CALL, river)
        assertTrue(bc.heroIsAggressor && bc.potBb > ts.potBb)
        val w = FloatArray(Combos.COUNT) { 1f }
        assertFailsWith<IllegalArgumentException> { TurnToRiverRangeFilter.filter(w, FloatArray(Combos.COUNT) { Float.NaN }, river) }
        assertFailsWith<IllegalArgumentException> { TurnToRiverRangeFilter.fromTurn(ts, TurnContext(Stake.NL50), RiverLine.BARREL_CALL, c("Jh")) }
        assertFailsWith<IllegalArgumentException> { TurnToRiverRangeFilter.fromTurn(ts, TurnContext(Stake.NL50), RiverLine.MULTIWAY_3WAY, river) }
    }

    @Test fun blockerDecompositionIsExact() {
        val s = direct("KsTs6h2d3s", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, false, true, RiverLine.BARREL_CALL, 30.0, 80.0)
        val m = s.math(ctx)
        val blk = m.blockers(m.villain)
        assertTrue(blk.nutBlocker(k("AsQd")) < 1.0, "A♠ removes villain nut flushes")
        assertTrue(blk.nutBlocker(k("AsQd")) < blk.nutBlocker(k("AhQd")))
        assertTrue(blk.foldBlocker(k("QhJh")) < blk.foldBlocker(k("AsQd")), "QJ blocks missed straight draws")
        assertTrue(blk.nutShare in 0.0..1.0 && blk.foldShare in 0.0..1.0)
        // Average over hero's range is 1 by construction (weighted by live combos).
        val elastic = m.respond(k("AsQd"), 0.7); val plain = m.respond(k("AhQd"), 0.7)
        assertTrue(elastic.defense < plain.defense, "blocking his calls shrinks DefenseShare")
        assertEquals(1.0, elastic.pFold + elastic.pCall + elastic.pRaise, 1e-9)
    }

    @Test fun sizingAndThinValueRules() {
        val p = RiverPolicy()
        assertEquals(listOf(0.25, 0.33, 0.7), RiverSizingOptimizer.candidates(5.0, 0.0, false, p))
        assertTrue(RiverSizingOptimizer.candidates(5.0, 0.10, false, p).containsAll(listOf(1.25, 1.5, 2.0, 5.0)))
        assertTrue(RiverSizingOptimizer.candidates(5.0, 0.0, true, p).contains(1.25), "capped villain enables overbets")
        assertEquals(listOf(0.25, 0.3), RiverSizingOptimizer.candidates(0.3, 0.0, false, p))
        assertTrue(RiverSizingOptimizer.candidates(0.0, 0.5, true, p).isEmpty())
        assertEquals(0.6, RiverSizingOptimizer.nashBluffRatio(1.5), 1e-12)
        val s = direct("Kd8c3h2s7c", FlopBoards.BB_DEFEND, "TT-22,KQo-K8o,KQs-K5s,QJo,QTo,JTo,A9o-A7o,QJs-Q8s,J9s+,T8s+,97s+,87s,76s",
            false, false, RiverLine.CHECK_CHECK_TURN, 10.0, 90.0)
        val m = s.math(ctx)
        val a = s.analyze(ctx)
        RiverInvariants.all(a, "thin value")
        var protectedPairs = 0
        for (h in 0 until Combos.COUNT) {
            if (a.heroRange[h] <= 0f) continue
            for (size in a.sizeCandidates) if (m.showdownProtected(h, m.bet(h, size).equity)) protectedPairs++
            val chosen = a.betSize(h)
            if (!chosen.isNaN() && a.frequency(a.betAction!!, h) > 0f) {
                val t = m.bet(h, chosen)
                assertTrue(!m.showdownProtected(h, t.equity), "${Combos.name(h)}: medium hand never turned into a thin bet below 52% vs callers")
                assertEquals(t.ev, a.ev(a.betAction!!, h), 1e-9)
            }
        }
        assertTrue(protectedPairs > 0, "showdown-value protection is active somewhere")
    }

    @Test fun zeroStackValidationAndLocks() {
        val b = FlopBoards.board("KsTs6h2d3s")
        val h = FlopBoards.range(FlopBoards.CO_OPEN, b); val v = FlopBoards.range(FlopBoards.BB_DEFEND, b)
        val zero = RiverSession(RiverSpot(b, h, v, true, true, RiverLine.BARREL_CALL, 30.0, 0.0)).analyze(ctx)
        assertEquals(listOf(RiverAction.CHECK), zero.actions)
        RiverInvariants.all(zero, "stack 0")
        assertFailsWith<IllegalArgumentException> { RiverSpot(b.copyOf(4), h, v, true, true, RiverLine.BARREL_CALL, 30.0, 80.0) }
        assertFailsWith<IllegalArgumentException> { RiverSpot(b, h.copyOf().also { it[3] = Float.NaN }, v, true, true, RiverLine.BARREL_CALL, 30.0, 80.0) }
        assertFailsWith<IllegalArgumentException> { RiverSpot(b, h, v, true, true, RiverLine.MULTIWAY_3WAY, 30.0, 80.0) }
        assertFailsWith<IllegalArgumentException> { RiverSession(RiverSpot(b, FloatArray(Combos.COUNT), v, true, true, RiverLine.BARREL_CALL, 30.0, 80.0)) }
        assertFailsWith<IllegalArgumentException> { RiverNodeLock(fold = 1.5) }
        val s = direct("KsTs6h2d3s", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, false, true, RiverLine.BARREL_CALL, 30.0, 80.0)
        val base = s.analyze(ctx); val over = s.analyze(ctx.copy(locks = RiverNodeLocks.foldToRiverBet(0.6)))
        RiverInvariants.all(base, "base"); RiverInvariants.all(over, "over")
        fun bluffs(a: RiverAnalysis): Double {
            var n = 0.0; var d = 0.0
            for (h in 0 until Combos.COUNT) { val w = a.heroRange[h]; if (w <= 0f) continue; d += w
                if (a.terms(RiverAction.TRIPLE_BARREL, h)!!.equity < 0.5f) n += w * a.frequency(RiverAction.TRIPLE_BARREL, h) }
            return n / d
        }
        println("river lock bluffs base=%.3f over=%.3f".format(bluffs(base), bluffs(over)))
        assertTrue(bluffs(over) > bluffs(base), "foldToRiverBet lock: more bluffs")
        val m = s.math(ctx.copy(locks = RiverNodeLocks.foldToRiverOverbet(0.8) + RiverNodeLocks.riverCallElasticity(0.2)))
        assertEquals(0.8, m.villainFold(1.5), 1e-12)
        assertTrue(m.defenseShare(0.7) < s.math(ctx).defenseShare(0.7), "elasticity lock")
        val f = direct("JhTh4c2c3d", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, RiverLine.BARREL_CALL, 30.0, 80.0, 18.0)
        assertEquals(0.0, f.math(ctx.copy(locks = RiverNodeLocks.riverDonkFreq(0.0))).facingPartition(18.0).betTotal, 1e-9)
        assertEquals(0.4, f.math(ctx.copy(locks = RiverNodeLocks.riverBluffFrequency(0.4))).bluffShare(18.0), 1e-6)
        assertEquals(0.7, f.math(ctx.copy(locks = RiverNodeLocks.foldToRiverRaise(0.7))).raiseFold(18.0), 1e-12)
        // Endogenous raise resistance: big bets / TURN_RAISE_CALL / low SPR contract to the committed band.
        val big = direct("JhTh4c2c3d", FlopBoards.BB_DEFEND, FlopBoards.CO_OPEN, false, false, RiverLine.TURN_RAISE_CALL, 30.0, 40.0, 25.0).math(ctx)
        assertTrue(big.raiseFold(25.0) in RiverPolicy().committedFoldMin..RiverPolicy().committedFoldMax)
        val small = direct("JhTh4c2c3d", FlopBoards.BB_DEFEND, FlopBoards.CO_OPEN, false, false, RiverLine.CHECK_CHECK_TURN, 30.0, 150.0, 8.0).math(ctx)
        assertTrue(small.raiseFold(8.0) > big.raiseFold(25.0))
    }

    @Test fun multiwayRiver() {
        val f = FlopBoards.board("Qh8d3c"); val turn = c("5s"); val river = c("2d")
        val tr = FlopToTurnRangeFilter.fromFlop(FlopSpot(f, FlopBoards.range(FlopBoards.CO_OPEN, f), FlopBoards.range(FlopBoards.BB_DEFEND, f), true, true, 8.0, 97.5,
            extraRange = FlopBoards.range(FlopBoards.BTN_FLAT, f)), FlopContext(Stake.NL50), FlopLine.MULTIWAY_3WAY, turn)
        val rr = TurnToRiverRangeFilter.fromTurn(tr.spot(f, turn), TurnContext(Stake.NL50), RiverLine.MULTIWAY_3WAY, river)
        assertTrue(rr.extra != null)
        val tb = f + turn
        val bet = RiverSession(rr.spot(tb, river)).analyze(ctx)
        RiverInvariants.all(bet, "3-way bet")
        val sandwich = RiverSession(RiverSpot(tb + river, rr.hero, rr.villain, false, false, RiverLine.MULTIWAY_3WAY, rr.potBb, rr.effectiveStackBb, 0.6 * rr.potBb, rr.extra)).analyze(ctx)
        RiverInvariants.all(sandwich, "3-way sandwich")
        assertTrue((0 until Combos.COUNT).any { sandwich.heroRange[it] > 0f && sandwich.terms(RiverAction.CALL, it)!!.pRaise > 0.0 }, "squeeze risk")
        assertEquals(0.0, sandwich.frequency(RiverAction.RAISE), 1e-12, "no raise in the sandwich")
    }
}
