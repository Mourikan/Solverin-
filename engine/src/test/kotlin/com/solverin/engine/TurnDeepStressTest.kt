package com.solverin.engine

import com.solverin.engine.core.Stake
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopContext
import com.solverin.engine.flop.FlopSpot
import com.solverin.engine.turn.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnDeepStressTest {
    private val ctx = TurnContext(Stake.NL50)
    private val legacy = ctx.copy(policy = TurnPolicy(leverageAlphaIp = 1.0, leverageAlphaOop = 0.7, exploitOverrideBb = Double.MAX_VALUE))
    private fun k(s: String) = Combos.parse(s)

    /** CO RFI vs BB call, c-bet / call on [flop], turn [turn]: hero IP second-barrel node. */
    private fun rfiVsBb(flop: String, turn: String): Pair<TurnSession, FlopToTurnRangeFilter.TurnRanges> {
        val f = FlopBoards.board(flop)
        val t = FlopBoards.board(turn)[0]
        val tr = FlopToTurnRangeFilter.fromFlop(
            FlopSpot(f, FlopBoards.range(FlopBoards.CO_OPEN, f), FlopBoards.range(FlopBoards.BB_DEFEND, f), true, true, 5.5, 97.5),
            FlopContext(Stake.NL50), FlopLine.BET_CALL, t,
        )
        return TurnSession(tr.spot(f, t)) to tr
    }

    /** Max EV: every action played has EV within the mix band of the best (uncapped combos, no enforced MDF floor). */
    private fun maxEv(a: TurnAnalysis, tag: String) {
        if (a.maxFold != null) return
        val band = TurnPolicy().mixBandBb
        for (h in 0 until Combos.COUNT) {
            if (a.heroRange[h] <= 0f || a.capped(h)) continue
            val evs = a.actions.associateWith { a.ev(it, h) }.filterValues { !it.isNaN() }
            val best = evs.values.max()
            for ((act, ev) in evs) if (a.frequency(act, h) > 1e-4f) assertTrue(ev >= best - band - 1e-9, "$tag ${Combos.name(h)} $act ev %.3f best %.3f".format(ev, best))
        }
    }

    // ---- Spot 1: leverage on scare turns ----------------------------------------------------------------

    @Test fun scareCardLeverageRaisesSecondBarrel() {
        var flippedSpots = 0
        for ((flop, turn) in listOf("6h4d2c" to "Ks", "7h5d2c" to "Kd", "9h5c2d" to "Kd", "8d4c3h" to "Ah", "7h5d2c" to "Ah")) {
            val (s, tr) = rfiVsBb(flop, turn)
            val now = s.analyze(ctx); val old = s.analyze(legacy)
            TurnInvariants.all(now, "$flop$turn"); TurnInvariants.all(old, "$flop$turn legacy")
            maxEv(now, "$flop$turn")
            val m = s.math(ctx); val mo = s.math(legacy)
            val proj = s.matrix.projection
            val act = now.betAction!!
            assertEquals(TurnAction.SECOND_BARREL, act)
            val up = (0 until Combos.COUNT).filter { now.heroRange[it] > 0f && now.frequency(act, it) > old.frequency(act, it) + 0.5f }
            println("S1 $flop$turn barrel %.3f → %.3f, flipped ${up.size}: ".format(old.frequency(act), now.frequency(act)) +
                up.take(6).joinToString { "%s L=%.3f ev=%.2f chk=%.2f".format(Combos.name(it), m.leverage(it, tr.effectiveStackBb, tr.potBb), now.ev(act, it), now.ev(TurnAction.CHECK, it)) })
            assertTrue(now.frequency(act) >= old.frequency(act), "$flop$turn: barrel frequency does not drop")
            val levered = up.filter { proj.scare[it] > 0 && m.leverage(it, tr.effectiveStackBb, tr.potBb) > mo.leverage(it, tr.effectiveStackBb, tr.potBb) }
            if (levered.isNotEmpty()) flippedSpots++
            for (h in levered) {
                val l = m.leverage(h, tr.effectiveStackBb, tr.potBb)
                assertTrue(l > 1.0 && l <= TurnPolicy().maxLeverage, "${Combos.name(h)}: L %.3f".format(l))
                assertTrue(now.ev(act, h) > now.ev(TurnAction.CHECK, h), "${Combos.name(h)}: barrel beats check")
                assertTrue(old.ev(act, h) < now.ev(act, h), "${Combos.name(h)}: α_pos raises barrel EV")
            }
        }
        assertTrue(flippedSpots > 0, "α_pos makes blocker hands start barrelling somewhere")
        // High-SPR scare cards reach the ~1.05–1.08 band on at least one scare turn.
        val top = listOf("6h4d2c" to "Ks", "7h5d2c" to "Kd", "9h5c2d" to "Kd", "8d4c3h" to "Ah", "7h5d2c" to "Ah").maxOf { (f, t) ->
            val (s, tr) = rfiVsBb(f, t)
            val m = s.math(ctx)
            (0 until Combos.COUNT).filter { s.matrix.projection.scare[it] > 0 && m.hero[it] > 0f }.maxOfOrNull { m.leverage(it, tr.effectiveStackBb, tr.potBb) } ?: 1.0
        }
        println("S1 max LeverageFactor %.3f".format(top))
        assertTrue(top in 1.05..1.25, "max LeverageFactor %.3f".format(top))
        assertEquals(3.0, TurnPolicy().leverageAlphaIp); assertEquals(1.5, TurnPolicy().leverageAlphaOop)
    }

    // ---- Spot 2: dirty flush outs in 3-bet pots ---------------------------------------------------------

    private val threeBettor = "QQ+,AKs,AKo,AQs,AJs,A5s,A4s"
    private val caller = "KTs,QJs,KJs,QTs,JTs,TT-77,AQs"

    private fun threeBetPot(board: String, stack: Double, c: TurnContext = ctx): Pair<TurnAnalysis, TurnMathEngine> {
        val b = FlopBoards.board(board)
        val s = TurnSession(TurnSpot(b, FlopBoards.range(caller, b), FlopBoards.range(threeBettor, b), true, false, FlopLine.BET_CALL, 40.0, stack, 30.0))
        return s.analyze(c) to s.math(c)
    }

    @Test fun dominatedFlushDrawsFoldInThreeBetPots() {
        val (low, lm) = threeBetPot("9s6s2d3c", 45.0)
        TurnInvariants.all(low, "3bet low SPR"); maxEv(low, "3bet low SPR")
        val o = lm.outsVs(lm.villainFirstAction.raise)
        for (x in listOf("QsJs", "QsTs")) {
            val h = k(x)
            val t = low.terms(TurnAction.CALL, h)!!
            println("S2 low SPR $x c%d d%d eq=%.2f call=%.2f F=%.2f".format(o.clean[h], o.dirty[h], t.equity, t.ev, low.frequency(TurnAction.FOLD, h)))
            assertTrue(o.dirty[h] > 0, "$x: spade rivers give the 3-bettor a higher flush")
            assertTrue(t.allIn && t.ev < 0, "$x: low SPR call is −EV")
            assertEquals(1f, low.frequency(TurnAction.FOLD, h), 1e-6f, "$x: instant fold")
        }

        val (deep, dm) = threeBetPot("9s6s2d3c", 120.0)
        TurnInvariants.all(deep, "3bet deep"); maxEv(deep, "3bet deep")
        val od = dm.outsVs(dm.villainFirstAction.raise)
        val king = k("KsJs"); val queen = k("QsJs")
        val tk = deep.terms(TurnAction.CALL, king)!!; val tq = deep.terms(TurnAction.CALL, queen)!!
        println("S2 deep KsJs c%d d%d call=%.2f rio=%.2f | QsJs c%d d%d call=%.2f rio=%.2f".format(od.clean[king], od.dirty[king], tk.ev, tk.rioPenalty, od.clean[queen], od.dirty[queen], tq.ev, tq.rioPenalty))
        assertTrue(od.dirty[queen] > od.dirty[king] && tq.rioPenalty > tk.rioPenalty, "Q-high flush draw is dominated by A♠X♠ / K♠X♠")
        assertTrue(tk.ev > 0 && tq.ev < 0, "K-high draw calls, Q-high draw does not")
        assertEquals(0f, deep.frequency(TurnAction.CALL, queen), 1e-6f, "Q-high draw never flats")
        assertEquals(0f, deep.frequency(TurnAction.FOLD, king), 1e-6f, "K-high draw continues")
    }

    @Test fun dirtyThresholdIsRelativeToRiverContinuationRange() {
        val (deep, dm) = threeBetPot("9s6s2d3c", 120.0)
        val b = FlopBoards.board("9s6s2d3c")
        val proj = TurnRiverProjectionEngine(b, FlopBoards.range(caller, b), FlopBoards.range(threeBettor, b))
        val range = dm.villainFirstAction.raise
        val wide = proj.project(range, 1.0); val tight = proj.project(range, 0.3)
        var grew = 0
        for (h in 0 until Combos.COUNT) {
            if (deep.heroRange[h] <= 0f) continue
            assertTrue(tight.dirty[h] >= wide.dirty[h], "${Combos.name(h)}: narrower continuation range → more dirty outs")
            assertEquals(wide.clean[h], tight.clean[h], "${Combos.name(h)}: clean outs independent of elasticity")
            if (tight.dirty[h] > wide.dirty[h]) grew++
        }
        assertTrue(grew > 0, "relative threshold reclassifies outs")
        assertTrue(proj.project(range, 0.0) === proj.project(range, TurnPolicy().minRiverContinue), "continuation floor")
        // The math engine wires the threshold to villain's river elasticity.
        val nit = TurnSession(deep.spot).math(ctx.copy(locks = TurnNodeLocks.riverCallElasticity(0.2)))
        assertEquals(0.2, nit.riverContinueFraction, 1e-12)
        val on = nit.outsVs(nit.villainFirstAction.raise)
        for (h in 0 until Combos.COUNT) if (deep.heroRange[h] > 0f) assertTrue(on.dirty[h] >= dm.outsVs(range).dirty[h])
    }

    // ---- Invariants: leverage rescaling ----------------------------------------------------------------

    @Test fun leverageRescalesResponsesExactly() {
        val spots = listOf(rfiVsBb("6h4d2c", "Ks"), rfiVsBb("7h5d2c", "Kd"), rfiVsBb("7h5d2c", "As"), rfiVsBb("9h5c2d", "Kd"))
        var checked = 0
        for ((s, tr) in spots) {
            val a = s.analyze(ctx)
            TurnInvariants.all(a, "rescale"); maxEv(a, "rescale")
            val m = s.math(ctx)
            val pot = tr.potBb; val stack = tr.effectiveStackBb
            for (size in listOf(0.33, 0.7, 1.25)) {
                val bet = size * pot
                val resp = m.villainModel.respondToBet(m.villainFirstAction.fold, pot, bet)
                for (h in 0 until Combos.COUNT) {
                    if (m.hero[h] <= 0f) continue
                    val raw = resp.probabilities(h)
                    val t = m.aggressive(h, pot, bet, 0.0, 0.0, resp, 1)
                    val l = m.leverage(h, stack - bet, pot + 2 * bet)
                    assertEquals(1.0, t.pFold + t.pCall + t.pRaise, 1e-12, "sum = 1")
                    assertEquals(minOf(1.0, raw[0] * l), t.pFold, 1e-12, "P(fold) · LeverageFactor")
                    if (raw[1] > 0 && raw[2] > 0) assertEquals(raw[1] / raw[2], t.pCall / t.pRaise, 1e-9, "call / raise ratio preserved")
                    if (l > 1.0) assertTrue(t.pFold > raw[0] || raw[0] == 0.0 || raw[0] == 1.0)
                    assertEquals(t.pFold * t.foldWin + t.pCall * t.calledEv + t.pRaise * t.evVsRaise, t.ev, 1e-9, "EV identity")
                    val r2 = m.aggressive(h, pot + bet, 3 * bet, 0.0, bet, m.villainModel.respondToRaise(resp.raise), 2)
                    val raw2 = m.villainModel.respondToRaise(resp.raise).probabilities(h)
                    assertEquals(raw2[0], r2.pFold, 1e-12, "raises are not leveraged")
                    checked++
                }
            }
        }
        println("leverage rescale checks: $checked")
        assertTrue(checked > 1000)
    }

    @Test fun stressSweepScareTurns() {
        var n = 0
        val t0 = System.currentTimeMillis()
        for (flop in listOf("7h5d2c", "8h6d3c", "6h4d2c", "7s5s2d", "8d4c3h", "9h5c2d")) for (turn in listOf("As", "Ah", "Ks", "Kd", "Qc")) {
            val (s, _) = rfiVsBb(flop, turn)
            for (c in listOf(ctx, legacy, ctx.copy(locks = TurnNodeLocks.foldToSecondBarrel(0.7)), ctx.copy(locks = TurnNodeLocks.riverCallElasticity(0.1)))) {
                val a = s.analyze(c)
                TurnInvariants.all(a, "$flop$turn"); maxEv(a, "$flop$turn")
                n++
            }
        }
        println("turn deep stress: $n solves in ${System.currentTimeMillis() - t0} ms")
    }
}
