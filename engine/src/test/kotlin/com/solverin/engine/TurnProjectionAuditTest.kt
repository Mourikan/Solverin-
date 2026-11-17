package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.Draws
import com.solverin.engine.flop.FlopContext
import com.solverin.engine.flop.FlopSpot
import com.solverin.engine.turn.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnProjectionAuditTest {
    private val ctx = TurnContext(Stake.NL50)
    private fun k(s: String) = Combos.parse(s)

    private fun facing(board: String, stack: Double, c: TurnContext = ctx, betFraction: Double = 0.75, pot: Double = 13.0): Pair<TurnAnalysis, TurnMathEngine> {
        val b = FlopBoards.board(board)
        val s = TurnSession(TurnSpot(b, FlopBoards.range(FlopBoards.BTN_FLAT, b), FlopBoards.range(FlopBoards.CO_OPEN, b), true, false,
            FlopLine.BET_CALL, pot, stack, betFraction * pot))
        return s.analyze(c) to s.math(c)
    }

    private fun barrel(board: String, stack: Double = 90.0): Triple<TurnSession, TurnAnalysis, TurnMathEngine> {
        val b = FlopBoards.board(board)
        val s = TurnSession(TurnSpot(b, FlopBoards.range(FlopBoards.CO_OPEN, b), FlopBoards.range(FlopBoards.BB_DEFEND, b), true, true,
            FlopLine.BET_CALL, 13.0, stack))
        return Triple(s, s.analyze(ctx), s.math(ctx))
    }

    // ---- Test 1: clean vs dirty outs -----------------------------------------------------------------

    @Test fun cleanNutFlushDrawBeatsLowFlushDrawOnPairedBoard() {
        val (a, m) = facing("Ks9s4d4c", 90.0)
        TurnInvariants.all(a, "paired board")
        val board = FlopBoards.board("Ks9s4d4c")
        val o = m.outsVs(m.villainFirstAction.raise)
        val nut = k("AsJs")
        for (low in listOf("6s5s", "7s6s")) {
            val l = k(low)
            assertEquals(Draws.outs(board, nut), Draws.outs(board, l), "same raw outs $low")
            val tn = a.terms(TurnAction.CALL, nut)!!; val tl = a.terms(TurnAction.CALL, l)!!
            println("T1 AsJs c%d d%d imp=%.2f rio=%.2f ev=%.2f | %s c%d d%d imp=%.2f rio=%.2f ev=%.2f".format(
                o.clean[nut], o.dirty[nut], tn.impliedRiver, tn.rioPenalty, tn.ev, low, o.clean[l], o.dirty[l], tl.impliedRiver, tl.rioPenalty, tl.ev))
            assertTrue(o.clean[nut] > o.clean[l], "nut draw has more clean outs than $low")
            assertTrue(tn.impliedRiver > tl.impliedRiver, "EV_implied_river nut > $low")
            assertTrue(tn.ev > tl.ev, "call EV nut > $low")
            assertTrue(maxOf(tn.ev, a.ev(TurnAction.RAISE, nut)) > maxOf(tl.ev, a.ev(TurnAction.RAISE, l)), "continue EV nut > $low")
            assertTrue(a.frequency(TurnAction.FOLD, nut) <= a.frequency(TurnAction.FOLD, l), "nut continues at least as often")
        }
        assertEquals(0f, a.frequency(TurnAction.FOLD, nut), 1e-6f)
        // Classes are disjoint and bounded by the 46 unseen rivers.
        for (h in 0 until Combos.COUNT) if (a.heroRange[h] > 0f) {
            assertTrue(o.clean[h] + o.dirty[h] + o.villainClean[h] <= TurnRiverProjectionEngine.UNSEEN_RIVERS)
            assertTrue(o.trapped[h] in 0f..(1f - TurnPolicy().dirtyLow.toFloat() + 1e-6f))
            if (o.dirty[h] == 0) assertEquals(0f, o.trapped[h])
        }
    }

    @Test fun dirtyStraightDrawPaysRioOnFlushingBoard() {
        // T♠9♠4♦2♣: K♣J♣ (gutshot + overcard outs) — same raw equity as T♠8♠ on K♠9♠4♦2♣ but dirty outs → RIO.
        val (clean, cm) = facing("Ks9s4d2c", 90.0)
        val (dirty, dm) = facing("Ts9s4d2c", 90.0)
        val fd = k("Ts8s"); val kj = k("KcJc")
        val tc = clean.terms(TurnAction.CALL, fd)!!; val td = dirty.terms(TurnAction.CALL, kj)!!
        assertTrue(kotlin.math.abs(tc.equity - td.equity) < 0.03f)
        assertTrue(dm.outsVs(dm.villainFirstAction.raise).dirty[kj] > 0 && td.rioPenalty > 0.0)
        assertTrue(cm.outsVs(cm.villainFirstAction.raise).clean[fd] > dm.outsVs(dm.villainFirstAction.raise).clean[kj])
        assertTrue(tc.impliedRiver - tc.rioPenalty > td.impliedRiver - td.rioPenalty)
        assertTrue(tc.ev > 0 && td.ev < 0)
        assertEquals(0f, dirty.frequency(TurnAction.CALL, kj), 1e-6f, "dirty draw never flats")
    }

    // ---- Test 2: implied odds vs instant fold ---------------------------------------------------------

    @Test fun impliedOddsJustifyCallOnlyWithDepthAndPayoff() {
        val fd = k("Ts8s")
        val inelastic = ctx.copy(locks = TurnNodeLocks.riverCallElasticity(1.0))
        val (deep, dm) = facing("Ks9s4d2c", 90.0, inelastic)
        TurnInvariants.all(deep, "deep inelastic")
        val t = deep.terms(TurnAction.CALL, fd)!!
        val direct = t.ev - t.impliedRiver + t.rioPenalty
        println("T2 deep: eq=%.3f R=%.3f potOdds=%.3f direct=%.2f imp=%.2f rio=%.2f ev=%.2f".format(t.equity, t.realization, deep.potOdds, direct, t.impliedRiver, t.rioPenalty, t.ev))
        assertTrue(t.drawNeedsImplied && t.equity * t.realization < deep.potOdds!!, "below direct pot odds")
        assertTrue(direct < 0.0 && direct + t.impliedRiver - t.rioPenalty > 0.0, "implied covers the deficit")
        assertEquals(1.0, dm.payoffProbability, 1e-12)
        assertEquals(0f, deep.frequency(TurnAction.FOLD, fd), 1e-6f, "pays with implied odds")
        val o = dm.outsVs(dm.villainFirstAction.raise)
        val behind = 90.0 - 0.75 * 13.0
        val potAfter = 13.0 + 2 * 0.75 * 13.0
        assertEquals(o.clean[fd] / 46.0 * minOf(behind, potAfter * TurnPolicy().riverValueBet) * 1.0, t.impliedRiver, 1e-9, "exact implied sub-equation")

        for ((tag, pair) in listOf(
            "short SPR" to facing("Ks9s4d2c", 10.0, inelastic),
            "river folder" to facing("Ks9s4d2c", 90.0, ctx.copy(locks = TurnNodeLocks.riverCallElasticity(0.0))),
        )) {
            val a = pair.first
            TurnInvariants.all(a, tag)
            val x = a.terms(TurnAction.CALL, fd)!!
            println("T2 $tag: ev=%.2f imp=%.2f allIn=%s F=%.2f C=%.2f R=%.2f".format(x.ev, x.impliedRiver, x.allIn, a.frequency(TurnAction.FOLD, fd), a.frequency(TurnAction.CALL, fd), a.frequency(TurnAction.RAISE, fd)))
            assertEquals(0.0, x.impliedRiver, 1e-12, "$tag: no implied odds")
            assertTrue(x.ev < 0.0, "$tag: call is −EV")
            assertEquals(0f, a.frequency(TurnAction.CALL, fd), 1e-6f, "$tag: no call")
            val best = if (a.ev(TurnAction.RAISE, fd) > 0.0) TurnAction.RAISE else TurnAction.FOLD
            assertTrue(a.frequency(best, fd) > 0.5f, "$tag: folds or re-raises by EV")
        }
        // Rule: below pot odds a call survives only if direct + implied − RIO > 0.
        for (a in listOf(deep, facing("Ks9s4d2c", 90.0).first, facing("Ts9s4d2c", 90.0).first, facing("Qs9s4d8d", 90.0).first)) {
            for (h in 0 until Combos.COUNT) {
                if (a.heroRange[h] <= 0f) continue
                val c = a.terms(TurnAction.CALL, h)!!
                if (c.drawNeedsImplied && c.ev <= 0.0) assertEquals(0f, a.frequency(TurnAction.CALL, h), 1e-6f, "draw rule ${Combos.name(h)}")
            }
        }
    }

    // ---- Test 3: blocker leverage ----------------------------------------------------------------------

    @Test fun nutBlockerLeverageOnSecondBarrelAndOverbet() {
        for ((board, blocker, plain) in listOf(Triple("Qh8s3s2d", "AsKd", "AhKd"), Triple("Jh8s4s2c", "KsQc", "KhQc"))) {
            val (s, a, m) = barrel(board)
            TurnInvariants.all(a, board)
            val b = k(blocker); val p = k(plain)
            val proj = s.matrix.projection
            assertEquals(0, m.strength.category[b], "$blocker: no made hand")
            assertEquals(0, m.strength.category[p], "$plain: no made hand")
            assertTrue(proj.scare[b] > proj.scare[p], "$board: $blocker has more scare rivers")
            val lb = m.leverage(b, 90.0, 13.0); val lp = m.leverage(p, 90.0, 13.0)
            assertTrue(lb > lp && lb > 1.0, "$board: LeverageFactor %.4f > %.4f".format(lb, lp))
            assertTrue(m.semiBluffPriority(b, m.villainFirstAction.fold), "$blocker: bluff-cap priority")
            val act = a.betAction!!
            println("T3 $board $blocker ev=%.3f L=%.4f | $plain ev=%.3f L=%.4f".format(a.ev(act, b), lb, a.ev(act, p), lp))
            assertTrue(a.ev(act, b) > a.ev(act, p), "$board: second barrel EV with blocker")
            for (size in listOf(1.25, 1.5)) {
                val bet = size * 13.0
                val resp = m.villainModel.respondToBet(m.villainFirstAction.fold, 13.0, bet)
                val eb = m.aggressive(b, 13.0, bet, 0.0, 0.0, resp, 1); val ep = m.aggressive(p, 13.0, bet, 0.0, 0.0, resp, 1)
                assertTrue(eb.ev > ep.ev, "$board overbet $size: blocker EV")
                assertTrue(eb.pFold > ep.pFold, "$board overbet $size: leveraged fold")
                assertEquals(1.0, eb.pFold + eb.pCall + eb.pRaise, 1e-9)
            }
        }
        val (_, _, m) = barrel("Qh8s3s2d")
        val h = k("AsKd")
        assertEquals(1.0, m.leverage(h, 0.0, 13.0), 0.0, "no stack behind → no future threat")
        val expected = 1 + TurnPolicy().leverageAlphaIp * maxOf(0.0, m.nutAdvantage) * (barrel("Qh8s3s2d").first.matrix.projection.scare[h] / 46.0) *
            (2 - m.blockerFactor(h)) * Math.tanh(90.0 / 13.0)
        assertEquals(expected.coerceIn(1.0, TurnPolicy().maxLeverage), m.leverage(h, 90.0, 13.0), 1e-12, "exact LeverageFactor")
    }

    // ---- Test 4: untouchable invariants ----------------------------------------------------------------

    @Test fun invariantsAndZeroStackProjection() {
        val spots = ArrayList<TurnAnalysis>()
        spots += facing("Ks9s4d2c", 90.0).first
        spots += facing("Ks9s4d4c", 90.0).first
        spots += facing("Ks9s4d2c", 90.0, betFraction = 0.15).first
        spots += facing("Ks9s4d2c", 9.0).first
        spots += facing("Ks9s4d2c", 90.0, ctx.copy(locks = TurnNodeLocks.foldToTurnRaise(0.8))).first
        spots += barrel("Qh8s3s2d").second
        spots += barrel("Jh8s4s2c", stack = 6.0).second
        val (_, ab, _) = barrel("AsKh7d2c")
        spots += ab
        // 3-way through the Bayesian bridge.
        val f = FlopBoards.board("Qh8d3c")
        val fs = FlopSpot(f, FlopBoards.range(FlopBoards.CO_OPEN, f), FlopBoards.range(FlopBoards.BB_DEFEND, f), true, true, 8.0, 97.5,
            extraRange = FlopBoards.range(FlopBoards.BTN_FLAT, f))
        val tr = FlopToTurnRangeFilter.fromFlop(fs, FlopContext(Stake.NL50), FlopLine.MULTIWAY_3WAY, Card.parse("5s"))
        spots += TurnSession(tr.spot(f, Card.parse("5s"))).analyze(ctx)
        val minBet = spots[2]
        assertTrue(minBet.minBet && minBet.maxFold != null, "min-bet MDF floor active")
        for ((i, a) in spots.withIndex()) {
            TurnInvariants.all(a, "audit $i")
            for (h in 0 until Combos.COUNT) {
                if (a.heroRange[h] <= 0f) continue
                for (act in a.actions) {
                    val t = a.terms(act, h) ?: continue
                    assertEquals(t.pFold * t.foldWin + t.pCall * t.calledEv + t.pRaise * t.evVsRaise, t.ev, 1e-9, "audit $i identity")
                    if (act == TurnAction.CALL && t.pCall == 1.0 && !a.spot.multiway) {
                        val eqR = minOf(t.equity.toDouble() * t.realization, TurnMathEngine.MAX_EQR)
                        val decomposed = eqR * t.potTotal - t.investment - t.rake + t.impliedRiver - t.rioPenalty
                        assertEquals(decomposed, t.ev, 1e-4, "audit $i ${Combos.name(h)} master equation decomposition")
                    }
                    if (t.allIn) {
                        assertEquals(0.0, t.impliedRiver, 0.0); assertEquals(0.0, t.rioPenalty, 0.0); assertEquals(1f, t.realization, 0f)
                    }
                }
            }
        }
        // Stack_rem = 0 after the turn action: the call puts hero all-in.
        val (shove, sm) = facing("Ks9s4d2c", 9.0, betFraction = 1.0)
        TurnInvariants.all(shove, "all-in call")
        for (h in 0 until Combos.COUNT) {
            if (shove.heroRange[h] <= 0f) continue
            val t = shove.terms(TurnAction.CALL, h)!!
            assertTrue(t.allIn && t.investment <= 9.0 + 1e-9)
            assertEquals(0.0, t.impliedRiver, 0.0); assertEquals(0.0, t.rioPenalty, 0.0); assertEquals(1f, t.realization, 0f)
            val o = sm.outsVs(sm.villainFirstAction.raise)
            assertEquals(0.0, sm.implied(h, o, 30.0, 0.0), 0.0)
            assertEquals(0.0, sm.rio(h, o, 30.0, 0.0), 0.0)
            assertEquals(1.0, sm.realization(h, o, true, 0.0, 30.0), 1e-12)
            assertEquals(1.0, sm.leverage(h, 0.0, 30.0), 0.0)
        }
    }
}
