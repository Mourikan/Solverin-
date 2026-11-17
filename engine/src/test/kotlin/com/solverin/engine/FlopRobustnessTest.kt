package com.solverin.engine

import com.solverin.engine.FlopBoards.BB_DEFEND
import com.solverin.engine.FlopBoards.CO_OPEN
import com.solverin.engine.FlopBoards.board
import com.solverin.engine.FlopBoards.range
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Edge inputs, internal consistency of the raise tree and SPR-dependent stack-off discipline. */
class FlopRobustnessTest {
    private val engine = FlopTest.flop
    private val nl50 = FlopContext(Stake.NL50)

    @Test fun invalidInputsAreRejected() {
        val b = board("Ks9d4c")
        val h = range(CO_OPEN, b); val v = range(BB_DEFEND, b)
        assertFailsWith<IllegalArgumentException> { FlopSpot(b, FloatArray(Combos.COUNT) { -0.1f }, v, true, true, 5.5, 97.5) }
        assertFailsWith<IllegalArgumentException> { FlopSpot(b, FloatArray(Combos.COUNT) { 1.5f }, v, true, true, 5.5, 97.5) }
        assertFailsWith<IllegalArgumentException> { FlopSpot(b, h, FloatArray(Combos.COUNT) { Float.NaN }, true, true, 5.5, 97.5) }
        assertFailsWith<IllegalArgumentException> { FlopSpot(b, h, v, true, true, 0.0, 97.5) }
        assertFailsWith<IllegalArgumentException> { FlopSpot(b, h, v, true, true, 5.5, Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { FlopSpot(b, h, v, true, true, 5.5, 97.5, facingBetBb = -1.0) }
        assertFailsWith<IllegalArgumentException> { FlopSpot(intArrayOf(1, 1, 2), h, v, true, true, 5.5, 97.5) }
        assertFailsWith<IllegalArgumentException> { engine.analyze(FlopSpot(b, FloatArray(Combos.COUNT), v, true, true, 5.5, 97.5), nl50) }
        assertFailsWith<IllegalArgumentException> { engine.analyze(FlopSpot(b, h, FloatArray(Combos.COUNT), true, true, 5.5, 97.5), nl50) }
    }

    @Test fun extremeStacksNeverBreakTheSolver() {
        for (bs in listOf("Ks9d4c", "Jh9h8c", "7c7d2h", "Th6h2h")) {
            val b = board(bs)
            val h = range(CO_OPEN, b); val v = range(BB_DEFEND, b)
            for (stack in listOf(0.0, 0.3, 0.5, 1.0, 2.75, 5.5, 22.0, 240.0)) {
                val spots = listOf(
                    FlopSpot(b, h, v, true, true, 5.5, stack),
                    FlopSpot(b, v, h, false, false, 5.5, stack),
                    FlopSpot(b, v, h, false, false, 5.5, stack, facingBetBb = 3.0),
                    FlopSpot(b, v, h, false, false, 5.5, stack, facingBetBb = 50.0),
                )
                for (s in spots) {
                    val a = engine.analyze(s, nl50)
                    FlopInvariants.all(a)
                    if (stack == 0.0 && s.nodeType != FlopNodeType.VS_BET) assertEquals(listOf(FlopAction.CHECK), a.actions)
                    if (s.nodeType == FlopNodeType.VS_BET) assertTrue(a.potOdds!! <= 0.5 + 1e-12)
                }
            }
        }
    }

    @Test fun aggressiveTermsMatchReraiseResponseAcrossSprs() {
        val b = board("Qh8h3c")
        val h = range(CO_OPEN, b); val v = range(BB_DEFEND, b)
        for (spr in listOf(0.5, 1.0, 2.0, 4.0, 12.0, 40.0)) {
            val pot = 6.0; val stack = spr * pot
            val spot = FlopSpot(b, h, v, true, true, pot, stack)
            val m = FlopMathEngine(spot, nl50, FlopEquityMatrix(b, h, v))
            for (size in listOf(0.33, 0.75, 1.25)) {
                val bet = minOf(size * pot, stack)
                val resp = m.villainModel.respondToBet(m.villainFirstAction.fold, pot, bet)
                for (k in 0 until Combos.COUNT) {
                    if (h[k] <= 0f) continue
                    val t = m.aggressive(k, pot, bet, 0.0, 0.0, resp, level = 1)
                    assertTrue(t.investment <= stack + 1e-9)
                    if (t.pRaise > 0) assertEquals(m.reraiseResponse(k, pot, bet, 0.0, 0.0, resp, 1).value, t.evVsRaise, 1e-9)
                    if (bet >= stack) assertEquals(0.0, t.pRaise, 0.0, "all-in bets cannot be re-raised")
                }
            }
        }
    }

    @Test fun stackOffDisciplineBySpr() {
        val b = board("Ks9d4c")
        val h = range(CO_OPEN, b); val v = range(BB_DEFEND, b)
        val sets = listOf("KdKh", "9s9h", "4d4h").map { Combos.parse(it) }.filter { h[it] > 0f }
        for (spr in listOf(4.0, 12.0, 40.0)) {
            val pot = 6.0; val stack = spr * pot
            val spot = FlopSpot(b, h, v, true, true, pot, stack)
            val m = FlopMathEngine(spot, nl50, FlopEquityMatrix(b, h, v))
            val bet = 0.5 * pot
            val resp = m.villainModel.respondToBet(m.villainFirstAction.fold, pot, bet)
            for (k in sets) assertTrue(m.reraiseResponse(k, pot, bet, 0.0, 0.0, resp, 1).best != FlopAction.FOLD, "SPR $spr set folds")
            for (k in 0 until Combos.COUNT) {
                if (h[k] <= 0f) continue
                val marginal = m.strength.category[k] >= 1 && !m.isStrong(k) && m.outs[k] == 0
                val rr = m.reraiseResponse(k, pot, bet, 0.0, 0.0, resp, 1)
                if (marginal) assertTrue(!rr.stacksOff, "SPR $spr ${Combos.name(k)}")
                // Any stack-off bluff clears the risk margin.
                if (rr.best == FlopAction.RAISE && m.eqVs(m.villainModel.respondToJam(resp.raise).call).equity(k) < 0.5f)
                    assertTrue(rr.jamEv >= maxOf(0.0, rr.callEv) + nl50.policy.stackOffBluffMarginBb, "${Combos.name(k)} thin stack-off bluff")
            }
        }
    }

    @Test fun minBetRuleInsideTheCheckSubtree() {
        val b = board("Jd8s3c")
        val h = range(BB_DEFEND, b); val v = range(CO_OPEN, b)
        val ctx = nl50.copy(profile = nl50.profile.copy(betSizeFraction = 0.1, cbet = 0.9))
        val spot = FlopSpot(b, h, v, heroInPosition = false, heroIsAggressor = false, potBb = 10.0, effectiveStackBb = 90.0)
        val m = FlopMathEngine(spot, ctx, FlopEquityMatrix(b, h, v))
        val bet = 1.0
        for (k in 0 until Combos.COUNT) {
            if (h[k] <= 0f) continue
            val t = m.check(k, 10.0, villainActsAfter = true)
            val call = m.call(k, 11.0, bet, m.villainFirstAction.raise)
            val raise = m.raiseVsBet(k, 11.0, bet, m.villainFirstAction.raise)
            val expected = if (call.equity >= bet / 12.0) maxOf(call.ev, raise.ev) else maxOf(0.0, call.ev, raise.ev)
            assertEquals(expected, t.evVsRaise, 1e-9, Combos.name(k))
        }
        FlopInvariants.all(engine.analyze(spot, ctx))
    }

    @Test fun hostileCrossValidation() {
        val boards = FlopBoards.textureBoards(perBucket = 1)
        val profiles = listOf(FlopBoards.STATION, FlopBoards.NIT, FlopBoards.MANIAC, FlopBoards.OVERFOLDER)
        var n = 0
        for (b in boards) {
            val h = range(CO_OPEN, b); val v = range(BB_DEFEND, b)
            for ((stack, bet) in listOf(3.0 to 1.0, 30.0 to 1.0, 30.0 to 6.0, 240.0 to 4.0)) {
                val s = engine.session(FlopSpot(b, v, h, heroInPosition = false, heroIsAggressor = false, potBb = 6.0, effectiveStackBb = stack, facingBetBb = bet))
                for (l in profiles) for (st in listOf(Stake.NL2, Stake.NL200)) {
                    val a = s.analyze(FlopContext(st, locks = l))
                    FlopInvariants.all(a)
                    n++
                }
            }
        }
        assertTrue(n >= 380, "cross-validation solves $n")
    }
}
