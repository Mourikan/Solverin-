package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopContext
import com.solverin.engine.flop.FlopSpot
import com.solverin.engine.turn.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

object TurnInvariants {
    fun all(a: TurnAnalysis, tag: String = "") {
        val spot = a.spot
        val stack = spot.effectiveStackBb
        var total = 0.0; var folded = 0.0; var raised = 0.0
        for (k in 0 until Combos.COUNT) {
            val w = a.heroRange[k]
            if (w <= 0f) continue
            assertTrue(w.isFinite() && w in 0f..1f, "$tag weight ${Combos.name(k)}")
            val sum = a.actions.sumOf { a.frequency(it, k).toDouble() }
            assertEquals(1.0, sum, 1e-4, "$tag freq sum ${Combos.name(k)}")
            total += w; folded += w * a.frequency(TurnAction.FOLD, k); raised += w * a.frequency(TurnAction.RAISE, k)
            for (act in a.actions) {
                val t = a.terms(act, k) ?: continue
                assertTrue(t.ev.isFinite(), "$tag ev finite ${Combos.name(k)} $act")
                assertEquals(1.0, t.pFold + t.pCall + t.pRaise, 1e-6, "$tag prob sum ${Combos.name(k)} $act")
                val id = t.pFold * t.foldWin + t.pCall * t.calledEv + t.pRaise * t.evVsRaise
                assertEquals(id, t.ev, 1e-6, "$tag EV identity ${Combos.name(k)} $act")
                assertTrue(t.investment <= stack + 1e-6, "$tag investment ≤ stack ${Combos.name(k)} $act")
                if (t.allIn) {
                    assertEquals(1f, t.realization, 1e-6f, "$tag all-in R_turn = 1 ${Combos.name(k)} $act")
                    assertEquals(0.0, t.impliedRiver, 0.0, "$tag all-in implied = 0")
                    assertEquals(0.0, t.rioPenalty, 0.0, "$tag all-in RIO = 0")
                }
                assertTrue(t.impliedRiver >= 0.0 && t.rioPenalty >= 0.0, "$tag implied / RIO ≥ 0")
                if (act == TurnAction.CALL || act == TurnAction.RAISE) assertTrue(t.evVsRaise >= -stack - 1e-6)
            }
            if (a.minBet) {
                val c = a.terms(TurnAction.CALL, k)!!
                if (c.equity >= a.potOdds!!) assertEquals(0f, a.frequency(TurnAction.FOLD, k), 1e-6f, "$tag min-bet no-fold ${Combos.name(k)}")
            }
        }
        a.betSizeBb?.let { assertTrue(it <= stack + 1e-9 && it > 0.0, "$tag bet ≤ stack") }
        a.maxFold?.let { assertTrue(folded / total <= it + 1e-6, "$tag MDF floor") }
        if (a.nodeType == TurnNodeType.VS_BET && !spot.heroInPosition) {
            assertTrue(raised / total <= TurnPolicy().checkRaiseCap + 1e-6, "$tag OOP check-raise cap")
        }
        assertTrue(a.rangeEv().isFinite(), "$tag range EV")
    }
}

class TurnEngineTest {
    private val utg = "66+,A2s+,ATo+,K9s+,KQo,QTs+,JTs,T9s,98s,87s"
    private val bbVsUtg = "22-QQ,A2s-AQs,K9s-KQs,Q9s+,J9s+,T8s+,97s+,86s+,75s+,65s,54s,AJo-AQo,KJo-KQo,QJo"
    private val ctx = TurnContext(Stake.NL50)
    private val flopCtx = FlopContext(Stake.NL50)

    private fun c(s: String) = Card.parse(s)
    private fun k(s: String) = Combos.parse(s)

    private fun bridge(flop: String, hero: String, villain: String, ip: Boolean, agg: Boolean, line: FlopLine, turn: String, pot: Double = 5.5, stack: Double = 97.5): Pair<TurnSession, FlopToTurnRangeFilter.TurnRanges> {
        val f = FlopBoards.board(flop)
        val fs = FlopSpot(f, FlopBoards.range(hero, f), FlopBoards.range(villain, f), ip, agg, pot, stack)
        val tr = FlopToTurnRangeFilter.fromFlop(fs, flopCtx, line, c(turn))
        return TurnSession(tr.spot(f, c(turn))) to tr
    }

    // ---- pillar 1: Bayesian bridge -----------------------------------------------------------------

    @Test fun rangeFilterIsBayesianAndRemovesTurnCard() {
        val f = FlopBoards.board("AsKh7d")
        val fs = FlopSpot(f, FlopBoards.range(utg, f), FlopBoards.range(bbVsUtg, f), true, true, 5.5, 97.5)
        for (line in listOf(FlopLine.BET_CALL, FlopLine.CHECK_CHECK, FlopLine.RAISE_CALL)) {
            val tr = FlopToTurnRangeFilter.fromFlop(fs, flopCtx, line, c("2c"))
            for (r in listOf(tr.hero, tr.villain)) {
                assertTrue(r.all { it.isFinite() && it in 0f..1f }, "$line weights")
                for (x in Combos.BY_CARD[c("2c")]) assertEquals(0f, r[x], "$line turn card removed")
            }
            for (x in 0 until Combos.COUNT) {
                assertTrue(tr.hero[x] <= fs.heroRange[x] + 1e-6f, "$line hero ⊆ flop")
                assertTrue(tr.villain[x] <= fs.villainRange[x] + 1e-6f, "$line villain ⊆ flop")
            }
            assertTrue(Combos.total(tr.hero) > 0 && Combos.total(tr.villain) > 0, "$line non-empty")
            assertTrue(tr.effectiveStackBb <= 97.5 && tr.potBb >= 5.5, "$line pot / stack")
            TurnInvariants.all(TurnSession(tr.spot(f, c("2c"))).analyze(ctx), "bridge $line")
        }
        val bc = FlopToTurnRangeFilter.fromFlop(fs, flopCtx, FlopLine.BET_CALL, c("2c"))
        val cc = FlopToTurnRangeFilter.fromFlop(fs, flopCtx, FlopLine.CHECK_CHECK, c("2c"))
        // Sets bet the flop: after check / check hero's set weight is (almost) gone, after bet / call it stays.
        assertTrue(bc.hero[k("7c7s")] > 0.5f && cc.hero[k("7c7s")] < 0.5f, "AK7: sets c-bet")
        // Villain: pure air folds to the c-bet, so it is absent from the bet / call turn range.
        assertTrue(bc.villain[k("6s5s")] < fs.villainRange[k("6s5s")], "air folds to c-bet")
        val w = FloatArray(Combos.COUNT) { 1f }; val p = FloatArray(Combos.COUNT) { 0.5f }
        val out = FlopToTurnRangeFilter.filter(w, p, c("2c"))
        assertEquals(0.5f, out[k("AsAh")]); assertEquals(0f, out[k("2c3d")])
        assertFailsWith<IllegalArgumentException> { FlopToTurnRangeFilter.filter(w, FloatArray(Combos.COUNT) { Float.NaN }, c("2c")) }
        assertFailsWith<IllegalArgumentException> { FlopToTurnRangeFilter.filter(FloatArray(Combos.COUNT) { 1.5f }, p, c("2c")) }
        assertFailsWith<IllegalArgumentException> { FlopToTurnRangeFilter.fromFlop(fs, flopCtx, FlopLine.BET_CALL, c("As")) }
    }

    @Test fun multiwayBridgeAndTurn() {
        val f = FlopBoards.board("Qh8d3c")
        val fs = FlopSpot(f, FlopBoards.range(FlopBoards.CO_OPEN, f), FlopBoards.range(FlopBoards.BB_DEFEND, f), true, true, 8.0, 97.5,
            extraRange = FlopBoards.range(FlopBoards.BTN_FLAT, f))
        val tr = FlopToTurnRangeFilter.fromFlop(fs, flopCtx, FlopLine.MULTIWAY_3WAY, c("5s"))
        assertTrue(tr.extra != null && Combos.total(tr.extra!!) > 0)
        val a = TurnSession(tr.spot(f, c("5s"))).analyze(ctx)
        TurnInvariants.all(a, "3-way")
        val hu = TurnSession(TurnSpot(f + c("5s"), tr.hero, tr.villain, true, true, FlopLine.BET_CALL, tr.potBb, tr.effectiveStackBb)).analyze(ctx)
        val qq = k("QsQd")
        assertTrue(a.terms(TurnAction.CHECK, qq)!!.realization < hu.terms(TurnAction.CHECK, qq)!!.realization, "3-way realization × 0.88")
        // Sandwiched call: the third player's squeeze risk shows as pRaise.
        val oop = TurnSpot(f + c("5s"), tr.hero, tr.villain, false, false, FlopLine.MULTIWAY_3WAY, tr.potBb, tr.effectiveStackBb, 0.66 * tr.potBb, tr.extra)
        val s = TurnSession(oop).analyze(ctx)
        TurnInvariants.all(s, "3-way sandwich")
        assertTrue((0 until Combos.COUNT).any { s.heroRange[it] > 0f && s.terms(TurnAction.CALL, it)!!.pRaise > 0.0 }, "squeeze risk")
    }

    // ---- pillar 2: turn card impact ------------------------------------------------------------------

    @Test fun turnCardClassification() {
        fun cat(flop: String, turn: String) = TurnCardImpactEngine.classify(FlopBoards.board(flop), c(turn))
        assertEquals(TurnCardCategory.BRICK_BLANK, cat("AsKh7d", "2c").category)
        val fh = cat("JhTh4c", "8h")
        assertEquals(TurnCardCategory.FLUSH_COMPLETING, fh.category)
        assertTrue(fh.has(TurnCardCategory.STRAIGHT_COMPLETING) && fh.newStraightPairs > 0)
        assertEquals(TurnCardCategory.BOARD_PAIRING, cat("9s8s4d", "9c").category)
        assertEquals(TurnCardCategory.OVERCARD_SCARE, cat("9s8s4d", "Ac").category)
        assertEquals(TurnCardCategory.STRAIGHT_COMPLETING, cat("9s8s4d", "7c").category)
        assertEquals(TurnCardCategory.FLUSH_COMPLETING, cat("9s8s4d", "2s").category)
        assertFailsWith<IllegalArgumentException> { cat("9s8s4d", "9s") }
        val t0 = System.nanoTime()
        repeat(10_000) { TurnCardImpactEngine.classify(intArrayOf(0, 17, 42), 51) }
        assertTrue((System.nanoTime() - t0) / 10_000 < 200_000, "classification in microseconds")
    }

    // ---- pillar 3: master equation / sizing ---------------------------------------------------------

    @Test fun twoStreetGeometricSizingIsExact() {
        for ((pot, stack) in listOf(10.0 to 90.0, 20.0 to 80.0, 13.1 to 93.7, 50.0 to 25.0)) {
            val b = TurnSizingOptimizer.geometricBet(pot, stack)
            val f = b / pot
            val river = f * (pot + 2 * b)
            assertEquals(stack - b, river, 1e-9, "turn + river geometric ends all-in")
        }
        assertEquals(0.0, TurnSizingOptimizer.geometricFraction(0.0))
        val p = TurnPolicy()
        assertTrue(TurnSizingOptimizer.candidates(9.0, 0.10, true, p).containsAll(listOf(1.25, 1.5)))
        assertTrue(TurnSizingOptimizer.candidates(9.0, 0.10, false, p).none { it > 1.2 && it != TurnSizingOptimizer.geometricFraction(9.0).let { g -> Math.round(g * 1000) / 1000.0 } })
        assertTrue(TurnSizingOptimizer.candidates(9.0, 0.03, true, p).none { it == 1.25 || it == 1.5 })
        assertTrue(TurnSizingOptimizer.candidates(0.0, 0.5, true, p).isEmpty())
    }

    @Test fun zeroStackChecksAndInvalidSpotsFail() {
        val b = FlopBoards.board("AsKh7d2c")
        val h = FlopBoards.range(utg, b); val v = FlopBoards.range(bbVsUtg, b)
        val a = TurnSession(TurnSpot(b, h, v, true, true, FlopLine.BET_CALL, 20.0, 0.0)).analyze(ctx)
        assertEquals(listOf(TurnAction.CHECK), a.actions)
        TurnInvariants.all(a, "stack 0")
        assertFailsWith<IllegalArgumentException> { TurnSpot(b, h.copyOf().also { it[0] = Float.NaN }, v, true, true, FlopLine.BET_CALL, 20.0, 80.0) }
        assertFailsWith<IllegalArgumentException> { TurnSpot(b, h, v.copyOf().also { it[5] = 2f }, true, true, FlopLine.BET_CALL, 20.0, 80.0) }
        assertFailsWith<IllegalArgumentException> { TurnSpot(b.copyOf(3), h, v, true, true, FlopLine.BET_CALL, 20.0, 80.0) }
        assertFailsWith<IllegalArgumentException> { TurnSpot(b, h, v, true, true, FlopLine.MULTIWAY_3WAY, 20.0, 80.0) }
        assertFailsWith<IllegalArgumentException> { TurnSession(TurnSpot(b, FloatArray(Combos.COUNT), v, true, true, FlopLine.BET_CALL, 20.0, 80.0)) }
    }

    // ---- spot 1: UTG vs BB, A♠K♥7♦ c-bet / call, turn 2♣ -------------------------------------------

    @Test fun spot1NutAdvantageSecondBarrel() {
        val (s, _) = bridge("AsKh7d", utg, bbVsUtg, true, true, FlopLine.BET_CALL, "2c")
        val a = s.analyze(ctx)
        TurnInvariants.all(a, "spot1")
        println("spot1 dR=%.3f dN=%.3f capped=%s size=%s geo=%.3f barrel=%.3f".format(a.rangeAdvantage, a.nutAdvantage, a.villainCapped, a.betSizeFraction, a.geometricSize, a.frequency(TurnAction.SECOND_BARREL)))
        assertEquals(TurnCardCategory.BRICK_BLANK, a.impact.category)
        assertEquals(TurnAction.SECOND_BARREL, a.betAction)
        assertTrue(a.nutAdvantage > TurnPolicy().overbetNutEdge && a.villainCapped, "nut advantage vs capped caller")
        assertTrue(a.sizeCandidates.containsAll(listOf(1.25, 1.5)), "overbets considered")
        assertTrue(a.betSizeFraction!! >= a.geometricSize - 1e-9, "geometric / overbet sizing")
        for (x in listOf("AdAc", "KdKc", "AhKd", "7c7s")) assertEquals(1f, a.frequency(TurnAction.SECOND_BARREL, k(x)), 1e-6f, "$x barrels")
        assertTrue(a.frequency(TurnAction.SECOND_BARREL) > 0.4, "aggressive barrel frequency")
    }

    // ---- spot 2: CO vs BB, J♥T♥4♣ c-bet / call, turn 8♥ / 9♥ ---------------------------------------

    @Test fun spot2FlushTurnBrakesVulnerableOverpairs() {
        val brick = bridge("JhTh4c", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, FlopLine.BET_CALL, "2c").first.analyze(ctx)
        TurnInvariants.all(brick, "spot2 brick")
        for (turn in listOf("8h", "9h")) {
            val a = bridge("JhTh4c", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, FlopLine.BET_CALL, turn).first.analyze(ctx)
            TurnInvariants.all(a, "spot2 $turn")
            println("spot2 $turn dN=%.3f barrel=%.3f (brick %.3f) size=%s".format(a.nutAdvantage, a.frequency(TurnAction.SECOND_BARREL), brick.frequency(TurnAction.SECOND_BARREL), a.betSizeFraction))
            assertEquals(TurnCardCategory.FLUSH_COMPLETING, a.impact.category)
            assertTrue(a.nutAdvantage < 0 && !a.villainCapped, "$turn: the caller gains the nuts")
            assertTrue(a.sizeCandidates.none { it == 1.25 || it == 1.5 }, "$turn: no overbets")
            assertTrue(a.frequency(TurnAction.SECOND_BARREL) < brick.frequency(TurnAction.SECOND_BARREL) - 0.1, "$turn: barrel brake")
            for (x in listOf("AsAc", "KsKc")) {
                assertEquals(1f, brick.frequency(TurnAction.SECOND_BARREL, k(x)), 1e-6f, "$x barrels the brick")
                assertTrue(a.frequency(TurnAction.CHECK, k(x)) >= 0.5f, "$turn: $x (no heart) slows down")
                assertTrue(a.terms(TurnAction.CHECK, k(x))!!.realization < brick.terms(TurnAction.CHECK, k(x))!!.realization - 0.1f, "$turn: $x R_turn drops")
            }
            assertTrue(a.frequency(TurnAction.SECOND_BARREL, k("AhAc")) > a.frequency(TurnAction.SECOND_BARREL, k("AsAc")), "$turn: heart blocker keeps barrelling")
        }
    }

    // ---- spot 3: 9♠8♠4♦ check / check, turn 2♣ -------------------------------------------------------

    @Test fun spot3ProbeAndDelayedCbet() {
        val (ipS, tr) = bridge("9s8s4d", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, FlopLine.CHECK_CHECK, "2c")
        val board = FlopBoards.board("9s8s4d2c")
        val oopS = TurnSession(TurnSpot(board, tr.villain, tr.hero, false, false, FlopLine.CHECK_CHECK, tr.potBb, tr.effectiveStackBb))
        val probe = oopS.analyze(ctx)
        TurnInvariants.all(probe, "spot3 probe")
        println("spot3 probe dN=%.3f capped=%s freq=%.3f size=%s".format(probe.nutAdvantage, probe.villainCapped, probe.frequency(TurnAction.PROBE), probe.betSizeFraction))
        assertEquals(TurnAction.PROBE, probe.betAction)
        assertTrue(tr.hero[k("9c9d")] < 0.5f && tr.hero[k("8c8d")] < 0.5f, "IP sets bet the flop: check-back range capped")
        assertTrue(probe.nutAdvantage > TurnPolicy().overbetNutEdge && probe.villainCapped, "OOP holds the nut advantage vs the capped check-back")
        assertTrue(probe.frequency(TurnAction.PROBE) > 0.2, "OOP probes")
        assertTrue(probe.betSizeFraction!! >= probe.geometricSize - 1e-9 || probe.betSizeFraction!! >= 1.25, "probe sizing polarised")
        for (x in listOf("9c9d", "8c8d", "4c4h")) if (probe.heroRange[k(x)] > 0f) assertEquals(1f, probe.frequency(TurnAction.PROBE, k(x)), 1e-6f, "$x probes")

        val delayed = ipS.analyze(ctx)
        TurnInvariants.all(delayed, "spot3 delayed")
        val m = ipS.math(ctx)
        val nut = m.villainNutRange
        fun nutShare(r: FloatArray): Double { var n = 0.0; var d = 0.0; for (x in r.indices) { d += r[x]; if (nut[x] > 0f) n += r[x] }; return n / d }
        val checked = m.villainFirstAction.fold
        println("spot3 delayed freq=%.3f size=%s villainNut full=%.3f checked=%.3f".format(delayed.frequency(TurnAction.DELAYED_CBET), delayed.betSizeFraction, nutShare(m.villain), nutShare(checked)))
        assertEquals(TurnAction.DELAYED_CBET, delayed.betAction)
        assertTrue(nutShare(checked) < nutShare(m.villain), "OOP turn check range is capped after not probing")
        assertTrue(delayed.frequency(TurnAction.DELAYED_CBET) > 0.15, "IP delayed c-bet vs capped range")
    }

    // ---- spot 4: draws facing a 75% pot turn bet -----------------------------------------------------

    private fun facing75(board: String, stack: Double): Pair<TurnAnalysis, TurnMathEngine> {
        val b = FlopBoards.board(board)
        val s = TurnSession(TurnSpot(b, FlopBoards.range(FlopBoards.BTN_FLAT, b), FlopBoards.range(FlopBoards.CO_OPEN, b), true, false,
            FlopLine.BET_CALL, 13.0, stack, 0.75 * 13.0))
        return s.analyze(ctx) to s.math(ctx)
    }

    @Test fun spot4CleanVsDirtyOutsAndSpr() {
        // Clean: T♠8♠ flush draw on K♠9♠4♦2♣ (9 clean outs), below direct pot odds → implied odds cover the deficit.
        val (clean, cm) = facing75("Ks9s4d2c", 90.0)
        TurnInvariants.all(clean, "spot4 clean")
        val fd = k("Ts8s")
        val o = cm.outsVs(cm.villainFirstAction.raise)
        val t = clean.terms(TurnAction.CALL, fd)!!
        assertTrue(o.clean[fd] >= 8 && o.dirty[fd] == 0, "clean flush outs")
        assertTrue(t.drawNeedsImplied && t.equity < clean.potOdds!!, "below pot odds")
        val direct = t.ev - t.impliedRiver + t.rioPenalty
        assertTrue(direct < 0 && t.impliedRiver >= -direct && t.ev > 0, "implied covers the deficit: direct %.2f implied %.2f".format(direct, t.impliedRiver))
        assertEquals(0f, clean.frequency(TurnAction.FOLD, fd), 1e-6f, "clean draw continues")

        // Dirty: K♣J♣ on T♠9♠4♦2♣ — same raw equity, outs dirty (flushes / better straights) → RIO → fold.
        val (dirty, dm) = facing75("Ts9s4d2c", 90.0)
        TurnInvariants.all(dirty, "spot4 dirty")
        val kj = k("KcJc")
        val od = dm.outsVs(dm.villainFirstAction.raise)
        val d = dirty.terms(TurnAction.CALL, kj)!!
        println("spot4 clean Ts8s eq=%.3f ev=%.2f imp=%.2f | dirty KcJc eq=%.3f ev=%.2f imp=%.2f rio=%.2f c%d d%d".format(t.equity, t.ev, t.impliedRiver, d.equity, d.ev, d.impliedRiver, d.rioPenalty, od.clean[kj], od.dirty[kj]))
        assertTrue(abs(d.equity - t.equity) < 0.03f, "comparable raw equity")
        assertTrue(od.dirty[kj] > od.clean[kj] && d.rioPenalty > 0.0, "dirty outs carry RIO")
        assertTrue(d.drawNeedsImplied && d.ev < 0, "implied does not cover")
        assertEquals(1f, dirty.frequency(TurnAction.FOLD, kj), 1e-6f, "dirty draw folds")

        // Short SPR: the same clean draw cannot be paid off → calling is −EV (all-in, R = 1, no implied).
        val (short, _) = facing75("Ks9s4d2c", 10.0)
        TurnInvariants.all(short, "spot4 short")
        val s = short.terms(TurnAction.CALL, fd)!!
        assertTrue(s.allIn && s.impliedRiver == 0.0 && s.ev < 0, "SPR does not cover the call")
        assertEquals(0f, short.frequency(TurnAction.CALL, fd), 1e-6f, "no flat call short")

        // riverCallElasticity lock scales implied odds.
        val locked = TurnSession(clean.spot).analyze(ctx.copy(locks = TurnNodeLocks.riverCallElasticity(0.1)))
        assertTrue(locked.terms(TurnAction.CALL, fd)!!.impliedRiver < t.impliedRiver * 0.3, "elasticity lock")
        assertTrue(locked.terms(TurnAction.CALL, fd)!!.ev < 0, "tight river caller: draw call turns −EV")
    }

    // ---- pillar 4: node locks / policy ---------------------------------------------------------------

    @Test fun nodeLocksStationAndOverfold() {
        val (s, _) = bridge("AsKh7d", utg, bbVsUtg, true, true, FlopLine.BET_CALL, "2c")
        val base = s.analyze(ctx)
        val stationCtx = ctx.copy(locks = TurnNodeLocks.foldToSecondBarrel(0.15))
        val station = s.analyze(stationCtx)
        val over = s.analyze(ctx.copy(locks = TurnNodeLocks.foldToSecondBarrel(0.85)))
        TurnInvariants.all(station, "station"); TurnInvariants.all(over, "overfold")
        println("locks barrel base=%.3f station=%.3f overfold=%.3f".format(base.frequency(TurnAction.SECOND_BARREL), station.frequency(TurnAction.SECOND_BARREL), over.frequency(TurnAction.SECOND_BARREL)))
        assertTrue(over.frequency(TurnAction.SECOND_BARREL) > base.frequency(TurnAction.SECOND_BARREL), "overfold → more barrels")
        val m = s.math(stationCtx)
        assertTrue(m.station)
        val p = TurnPolicy()
        val resp = m.villainModel.respondToBet(m.villainFirstAction.fold, station.spot.potBb, station.betSizeBb!!)
        for (x in 0 until Combos.COUNT) {
            if (station.heroRange[x] <= 0f || station.frequency(TurnAction.SECOND_BARREL, x) <= 0f) continue
            val e = m.eqVs(resp.continueRange).equity(x)
            val pure = e < p.thinValueEquity && !m.isNut(x) && !m.semiBluffPriority(x, resp.continueRange)
            assertTrue(!pure, "station: pure bluff ${Combos.name(x)} eq %.2f bets".format(e))
        }
        // foldToTurnRaise lock: raising a turn bet grows vs an over-folder.
        val (fb, _) = facing75("Ks9s4d2c", 90.0)
        val raiseFold = TurnSession(fb.spot).analyze(ctx.copy(locks = TurnNodeLocks.foldToTurnRaise(0.85)))
        TurnInvariants.all(raiseFold, "raise lock")
        println("raise lock base=%.3f locked=%.3f fold base=%.3f locked=%.3f".format(fb.frequency(TurnAction.RAISE), raiseFold.frequency(TurnAction.RAISE), fb.frequency(TurnAction.FOLD), raiseFold.frequency(TurnAction.FOLD)))
        assertTrue(raiseFold.frequency(TurnAction.RAISE) > fb.frequency(TurnAction.RAISE), "foldToTurnRaise lock")
        // turnDonkFrequency lock: no donks → villain's checking range keeps every combo.
        val noDonk = s.math(ctx.copy(locks = TurnNodeLocks.turnDonkFrequency(0.0)))
        assertEquals(0.0, noDonk.villainFirstAction.raiseTotal, 1e-9)
        // foldToProbe lock moves the probe node.
        val (probeS, _) = bridge("9s8s4d", FlopBoards.BB_DEFEND, FlopBoards.CO_OPEN, false, false, FlopLine.CHECK_CHECK, "2c")
        val pBase = probeS.analyze(ctx); val pOver = probeS.analyze(ctx.copy(locks = TurnNodeLocks.foldToProbe(0.8)))
        TurnInvariants.all(pOver, "probe lock")
        assertTrue(pOver.frequency(TurnAction.PROBE) > pBase.frequency(TurnAction.PROBE), "foldToProbe lock")
        assertFailsWith<IllegalArgumentException> { TurnNodeLock(fold = 1.2) }
        assertFailsWith<IllegalArgumentException> { TurnNodeLock(fold = Double.NaN) }
    }

    @Test fun bluffCapRatios() {
        val p = TurnPolicy()
        assertEquals(p.bluffBaseMin, TurnBluffCap.base(false, 0.33, p), 1e-9)
        assertEquals(p.bluffBaseMax, TurnBluffCap.base(false, 1.5, p), 1e-9)
        assertTrue(TurnBluffCap.base(false, 0.7, p) in p.bluffBaseMin..p.bluffBaseMax)
        val mdf = 1 / 1.7
        val small = 1 / 1.33
        assertEquals(p.maxBluffRatio, TurnBluffCap.ratio(false, 0.33, 0.9, small, false, p), 1e-9)
        assertTrue(TurnBluffCap.ratio(false, 0.33, 0.9, small, true, p) > p.maxBluffRatio, "locked overfold relaxes the cap")
        assertTrue(TurnBluffCap.ratio(false, 0.33, 0.99, small, true, p) <= p.maxBluffRatioLocked)
        assertEquals(TurnBluffCap.MIN_RATIO, TurnBluffCap.ratio(false, 0.7, 0.05, mdf, false, p), 1e-9)
    }

    @Test fun stressTextureSweep() {
        val turns = listOf("2c", "8h", "9c", "Ad", "Qs")
        val flops = listOf("JhTh4c", "AsKh7d", "9s8s4d", "Qc8d3c", "7h7d2s")
        var n = 0
        val t0 = System.currentTimeMillis()
        for (f in flops) for (t in turns) {
            if (Card.parse(t) in FlopBoards.board(f)) continue
            for (line in listOf(FlopLine.BET_CALL, FlopLine.CHECK_CHECK)) {
                val fb = FlopBoards.board(f)
                val fs = FlopSpot(fb, FlopBoards.range(FlopBoards.CO_OPEN, fb), FlopBoards.range(FlopBoards.BB_DEFEND, fb), true, true, 5.5, 97.5)
                val tr = FlopToTurnRangeFilter.fromFlop(fs, flopCtx, line, c(t))
                if (tr.hero.none { it > 0f }) {
                    // The whole flop range took the other line (e.g. 100% c-bet): an empty turn range is rejected.
                    assertFailsWith<IllegalArgumentException> { TurnSession(tr.spot(fb, c(t))) }
                    continue
                }
                TurnInvariants.all(TurnSession(tr.spot(fb, c(t))).analyze(ctx), "$f$t $line IP")
                val b = fb + c(t)
                val oop = TurnSession(TurnSpot(b, tr.villain, tr.hero, false, false, line, tr.potBb, tr.effectiveStackBb, 0.7 * tr.potBb))
                TurnInvariants.all(oop.analyze(ctx), "$f$t $line OOP vs bet")
                n += 2
            }
        }
        println("turn stress: $n spots in ${System.currentTimeMillis() - t0} ms")
    }
}
