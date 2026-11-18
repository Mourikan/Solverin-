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
import kotlin.test.assertTrue

class RiverUltimateTortureTest {
    private val ctx = RiverContext(Stake.NL50)
    private fun k(s: String) = Combos.parse(s)
    private fun c(s: String) = Card.parse(s)

    private fun direct(b: String, hero: String, villain: String, ip: Boolean, agg: Boolean, line: RiverLine, pot: Double, stack: Double, bet: Double = 0.0): RiverSession {
        val x = FlopBoards.board(b)
        return RiverSession(RiverSpot(x, FlopBoards.range(hero, x), FlopBoards.range(villain, x), ip, agg, line, pot, stack, bet))
    }

    private fun turnSpot(flop: String, turn: String, multiway: Boolean = false): TurnSpot {
        val f = FlopBoards.board(flop); val t = c(turn)
        val fs = if (multiway) FlopSpot(f, FlopBoards.range(FlopBoards.CO_OPEN, f), FlopBoards.range(FlopBoards.BB_DEFEND, f), true, true, 8.0, 97.5,
            extraRange = FlopBoards.range(FlopBoards.BTN_FLAT, f))
        else FlopSpot(f, FlopBoards.range(FlopBoards.CO_OPEN, f), FlopBoards.range(FlopBoards.BB_DEFEND, f), true, true, 5.5, 97.5)
        return FlopToTurnRangeFilter.fromFlop(fs, FlopContext(Stake.NL50), if (multiway) FlopLine.MULTIWAY_3WAY else FlopLine.BET_CALL, t).spot(f, t)
    }

    /** Bridged river ranges; [pot] / [stack] override the bridged amounts (deep overbet-capable SPR). */
    private fun bridged(flop: String, turn: String, river: String, line: RiverLine = RiverLine.BARREL_CALL, pot: Double? = null, stack: Double? = null): RiverSession {
        val ts = turnSpot(flop, turn)
        val rr = TurnToRiverRangeFilter.fromTurn(ts, TurnContext(Stake.NL50), line, c(river))
        return RiverSession(RiverSpot(ts.board + c(river), rr.hero, rr.villain, rr.heroInPosition, rr.heroIsAggressor, line,
            pot ?: rr.potBb, stack ?: rr.effectiveStackBb))
    }

    private fun weighted(a: RiverAnalysis, f: (Int) -> Double): Double {
        var n = 0.0; var d = 0.0
        for (h in 0 until Combos.COUNT) { val w = a.heroRange[h]; if (w <= 0f) continue; n += w * f(h); d += w }
        return if (d > 0) n / d else 0.0
    }

    // ---- Spot 1: bluff selection by terminal blockers ------------------------------------------------------

    @Test fun spot1BluffSelectionByBlockers() {
        val s = direct("KsTs6h2d3s", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, false, true, RiverLine.BARREL_CALL, 30.0, 80.0)
        val a = s.analyze(ctx); val m = s.math(ctx)
        RiverInvariants.all(a, "KT623 flush river")
        assertEquals(RiverCardCategory.FRONT_DOOR_FLUSH_COMPLETING, a.impact.category)
        val aq = k("AsQd"); val qj = k("QhJh")
        val bAq = m.bet(aq, 0.7); val bQj = m.bet(qj, 0.7)
        println("R1 AsQd ev=%.2f bn=%.2f bf=%.2f | QhJh ev=%.2f bn=%.2f bf=%.2f".format(bAq.ev, bAq.bNut, bAq.bFold, bQj.ev, bQj.bNut, bQj.bFold))
        assertTrue(bAq.bNut < 1.0 && bAq.bNut < bQj.bNut, "A♠ blocks the nut flushes")
        assertTrue(bQj.bFold < bAq.bFold, "QJ blocks villain's missed straight draws")
        assertTrue(bAq.ev - bQj.ev > 3.0, "A♠Q♦ bluff EV significantly higher")
        assertEquals(0f, a.frequency(RiverAction.TRIPLE_BARREL, qj), 1e-6f, "Q♥J♥ gives up")
        for (bigOver in listOf(1.25, 1.5)) assertTrue(m.bet(aq, bigOver).ev > m.bet(qj, bigOver).ev, "overbet EV with the nut blocker")
        // After two streets of calls the override stays shut even with an over-fold read.
        val shut = s.analyze(ctx.copy(locks = RiverNodeLocks.foldToRiverBet(0.55)))
        RiverInvariants.all(shut, "two-street caller")
        assertTrue(shut.frequency(RiverAction.TRIPLE_BARREL, aq) <= a.frequency(RiverAction.TRIPLE_BARREL, aq) + 1e-6f, "no override after two calls")
        // On an uncondensed line, the bluff budget goes to the nut blocker, never to Q♥J♥.
        val s2 = direct("KsTs6h2d3s", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, false, true, RiverLine.CHECK_CHECK_TURN, 30.0, 80.0)
        val over = s2.analyze(ctx.copy(locks = RiverNodeLocks.foldToRiverBet(0.55)))
        RiverInvariants.all(over, "over-folder")
        val act = over.betAction!!
        assertTrue(over.frequency(act, aq) > 0.5f, "A♠Q♦ bluffs")
        assertEquals(0f, over.frequency(act, qj), 1e-6f, "Q♥J♥ still checks")
        assertTrue(over.ev(act, qj) <= over.ev(RiverAction.CHECK, qj) || over.capped(qj), "Q♥J♥ loses the budget to better blockers")
    }

    // ---- Spot 2: bluff-catcher selection by anti-blockers ---------------------------------------------------

    @Test fun spot2BluffCatcherAntiBlockers() {
        val s = direct("Kh9h4c2d7s", "98s,A9o,Q9o,T9s,J9s,KJo,QQ-55,K9o", FlopBoards.CO_OPEN, false, false, RiverLine.BARREL_CALL, 30.0, 80.0, 22.5)
        val a = s.analyze(ctx)
        RiverInvariants.all(a, "K9427 missed hearts")
        assertEquals(RiverCardCategory.MISSED_DRAWS_BRICK, a.impact.category)
        assertTrue(a.impact.drawHeavyTurn)
        for ((clean, blocker) in listOf("Qc9d" to "Qh9d", "Qd9s" to "Qh9s", "Qd9c" to "Qh9c")) {
            val tc = a.terms(RiverAction.CALL, k(clean))!!; val tb = a.terms(RiverAction.CALL, k(blocker))!!
            println("R2 $clean eq=%.3f ev=%.2f bf=%.2f | $blocker eq=%.3f ev=%.2f bf=%.2f".format(tc.equity, tc.ev, tc.bFold, tb.equity, tb.ev, tb.bFold))
            assertEquals(s.math(ctx).showdown.category(k(clean)), s.math(ctx).showdown.category(k(blocker)), "same category")
            assertTrue(tc.equity >= tb.equity && tc.ev > tb.ev, "$clean (no heart) is the better bluff-catcher than $blocker")
            assertTrue(tb.bFold < tc.bFold, "$blocker blocks villain's missed hearts")
            assertTrue(a.frequency(RiverAction.FOLD, k(clean)) <= a.frequency(RiverAction.FOLD, k(blocker)))
        }
    }

    // ---- Spot 3: block bet / thin value OOP ------------------------------------------------------------------

    @Test fun spot3BlockBetThinValue() {
        val s = direct("Kd8c3h2s7c", FlopBoards.BB_DEFEND, "TT-22,KQo-K8o,KQs-K5s,QJo,QTo,JTo,A9o-A7o,QJs-Q8s,J9s+,T8s+,97s+,87s,76s",
            false, false, RiverLine.CHECK_CHECK_TURN, 10.0, 90.0)
        val a = s.analyze(ctx); val m = s.math(ctx)
        RiverInvariants.all(a, "K8327 OOP")
        assertEquals(RiverAction.PROBE, a.betAction)
        for (x in listOf("Kh9h", "Kh6h", "Kh5h")) {
            val h = k(x)
            val small = m.bet(h, 0.25); val big = m.bet(h, 0.7)
            println("R3 $x eqT=%.2f size=%.2f small ev=%.2f eqC=%.2f | 70%% ev=%.2f eqC=%.2f | check=%.2f".format(m.eqTotal(h), a.betSize(h), small.ev, small.equity, big.ev, big.equity, a.ev(RiverAction.CHECK, h)))
            assertTrue(m.eqTotal(h) in RiverPolicy().blockValueLow..RiverPolicy().blockValueHigh, "$x: medium-thin value")
            assertTrue(a.betSize(h) <= RiverPolicy().thinBet + 1e-9, "$x: block / thin size")
            assertTrue(a.frequency(RiverAction.PROBE, h) > 0.5f)
            assertTrue(small.ev > big.ev && small.ev > a.ev(RiverAction.CHECK, h), "$x: small bet beats 70% and check")
            assertTrue(big.equity < 0.5f && small.equity > 0.5f, "$x: worse pairs call small, fold to 70%")
        }
    }

    // ---- Spot 4: polarized overbet vs capped range, shut down on a pairing river -------------------------------

    @Test fun spot4OverbetAndPairedShutdown() {
        for ((flop, turn, brick, paired) in listOf(listOf("JhTh4c", "2c", "3d", "Ts"), listOf("AsKh7d", "2c", "8h", "Ks"))) {
            val bs = bridged(flop, turn, brick, pot = 20.0, stack = 90.0)
            val a = bs.analyze(ctx); val m = bs.math(ctx)
            RiverInvariants.all(a, "$flop$turn$brick")
            println("R4 $flop$turn$brick dN=%.3f sizes=${a.sizeFrequency()}".format(a.nutAdvantage))
            assertTrue(a.overbetEnabled && a.nutAdvantage > 0.06)
            var overbets = 0
            for (h in 0 until Combos.COUNT) {
                if (a.heroRange[h] <= 0f || a.frequency(a.betAction!!, h) <= 0f) continue
                val size = a.betSize(h)
                if (size < RiverPolicy().overbetLow) continue
                overbets++
                val t = a.terms(a.betAction!!, h)!!
                assertTrue(m.eqTotal(h) >= RiverPolicy().overbetValueEquity || (t.equity < 0.5f && t.bNut < 1.0), "${Combos.name(h)}: overbet = nuts or nut-blocker bluff")
            }
            assertTrue(overbets > 0, "$flop$turn$brick: overbet used")
            val ps = bridged(flop, turn, paired, pot = 20.0, stack = 90.0)
            val p = ps.analyze(ctx)
            RiverInvariants.all(p, "$flop$turn$paired")
            println("R4 $flop$turn$paired dN=%.3f cat=${p.impact.category}".format(p.nutAdvantage))
            assertEquals(RiverCardCategory.BOARD_PAIRING_RIVER, p.impact.category)
            assertTrue(!p.overbetEnabled && p.sizeCandidates.none { it >= RiverPolicy().overbetLow }, "$paired pairs for the defender: overbet off")
        }
    }

    // ---- Spot 5: exploitative over-fold vs an under-bluffer ------------------------------------------------------

    @Test fun spot5ExploitativeOverFold() {
        val s = direct("Kh9h4c2d7s", FlopBoards.BB_DEFEND, FlopBoards.CO_OPEN, false, false, RiverLine.BARREL_CALL, 30.0, 80.0, 22.5)
        val pop = s.analyze(ctx)
        val nitCtx = ctx.copy(locks = RiverNodeLocks.riverBluffFrequency(0.05))
        val nit = s.analyze(nitCtx); val m = s.math(nitCtx)
        RiverInvariants.all(pop, "population"); RiverInvariants.all(nit, "nit")
        println("R5 pop fold=%.3f bluff=%.3f | nit fold=%.3f bluff=%.3f alpha=%.3f mdf=%.3f".format(pop.frequency(RiverAction.FOLD), pop.bluffShare, nit.frequency(RiverAction.FOLD), nit.bluffShare, nit.alpha, nit.mdf))
        assertTrue(!pop.exploitOverFold && nit.exploitOverFold)
        assertTrue(nit.frequency(RiverAction.FOLD) > 1 - nit.mdf!! + 0.1, "MDF broken on purpose")
        assertTrue(nit.maxFold == null)
        assertTrue(nit.frequency(RiverAction.FOLD) > pop.frequency(RiverAction.FOLD))
        assertEquals(1f, nit.frequency(RiverAction.FOLD, k("9s8s")), 1e-6f, "pure bluff-catcher folds")
        val value = m.facingPartition(22.5).value
        val vIdx = m.showdown.order(value)
        for (h in 0 until Combos.COUNT) {
            if (nit.heroRange[h] <= 0f || nit.frequency(RiverAction.CALL, h) <= 0f) continue
            assertTrue(m.showdown.equity(h, value, vIdx) > 0f, "${Combos.name(h)}: calls only beating part of the value")
            assertTrue(nit.terms(RiverAction.CALL, h)!!.ev >= -TurnBand)
        }
        // 3-bet pot jam by an under-bluffer.
        val jam = direct("Kh9h4c2d7s", FlopBoards.BB_DEFEND, FlopBoards.CO_OPEN, false, false, RiverLine.TURN_RAISE_CALL, 60.0, 40.0, 40.0)
        val j = jam.analyze(nitCtx)
        RiverInvariants.all(j, "3bet jam")
        assertTrue(j.exploitOverFold && j.frequency(RiverAction.FOLD) > 1 - j.mdf!!)
    }

    private val TurnBand = RiverPolicy().mixBandBb + 1e-9

    // ---- Spot 6: weak block lead vs polarized lead ------------------------------------------------------------

    @Test fun spot6WeakLeadPunishedPolarLeadRespected() {
        val weak = direct("JhTh4c2c3d", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, RiverLine.BARREL_CALL, 30.0, 80.0, 7.5).analyze(ctx)
        val std = direct("JhTh4c2c3d", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, RiverLine.BARREL_CALL, 30.0, 80.0, 18.0).analyze(ctx)
        val polarS = direct("JhTh4c2c8h", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, RiverLine.BARREL_CALL, 30.0, 80.0, 30.0)
        val polar = polarS.analyze(ctx)
        for ((t, x) in listOf("weak" to weak, "std" to std, "polar" to polar)) RiverInvariants.all(x, t)
        println("R6 weak raise=%.3f fold=%.3f | std raise=%.3f | polar raise=%.3f fold=%.3f".format(weak.frequency(RiverAction.RAISE), weak.frequency(RiverAction.FOLD), std.frequency(RiverAction.RAISE), polar.frequency(RiverAction.RAISE), polar.frequency(RiverAction.FOLD)))
        assertEquals(RiverDonkType.WEAK, weak.donkType); assertEquals(RiverDonkType.POLARIZED, polar.donkType)
        assertEquals(1 - weak.mdf!! + RiverPolicy().weakDonkMdfSlack, weak.maxFold!!, 1e-9)
        assertTrue(weak.frequency(RiverAction.RAISE) > 3 * std.frequency(RiverAction.RAISE) && weak.frequency(RiverAction.RAISE) > 0.2, "block lead gets raised")
        assertTrue(polar.frequency(RiverAction.FOLD) > weak.frequency(RiverAction.FOLD) + 0.3, "polarized lead: defense contracts")
        for (h in 0 until Combos.COUNT) {
            if (polar.heroRange[h] <= 0f || polar.frequency(RiverAction.RAISE, h) <= 0f) continue
            val r = polar.terms(RiverAction.RAISE, h)!!
            assertTrue(r.equity >= 0.5f || r.bNut < 1.0, "${Combos.name(h)}: no raise without value or the nut blocker")
        }
        // 1bb micro-bet: no fold with pot odds, raises explode.
        val micro = direct("Kd8c4h2s9c", FlopBoards.BTN_FLAT, FlopBoards.CO_OPEN, true, false, RiverLine.BARREL_CALL, 20.0, 80.0, 1.0).analyze(ctx)
        RiverInvariants.all(micro, "1bb")
        assertTrue(micro.minBet && micro.frequency(RiverAction.FOLD) <= 1 - micro.mdf!! + 1e-6)
        for (h in 0 until Combos.COUNT) if (micro.heroRange[h] > 0f && micro.terms(RiverAction.CALL, h)!!.equity >= 1.0 / 22.0)
            assertEquals(0f, micro.frequency(RiverAction.FOLD, h), 1e-6f)
        assertTrue(micro.frequency(RiverAction.RAISE) > 0.2, "min-bet telegraphs weakness: raise")
    }

    // ---- Spot 7: four-flush shield ----------------------------------------------------------------------------

    @Test fun spot7FourFlushShield() {
        val spade = c("2s") and 3
        fun hasSpade(h: Int) = (Combos.C1[h] and 3) == spade || (Combos.C2[h] and 3) == spade
        fun topSpade(h: Int) = (Combos.C1[h] == c("As") || Combos.C2[h] == c("As") || Combos.C1[h] == c("Ks") || Combos.C2[h] == c("Ks"))
        val bs = direct("Qs9s4s2c7s", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, RiverLine.BARREL_CALL, 30.0, 80.0)
        val a = bs.analyze(ctx); val m = bs.math(ctx)
        RiverInvariants.all(a, "4-flush bet")
        assertEquals(RiverCardCategory.FOUR_TO_A_FLUSH_OR_STRAIGHT, a.impact.category)
        val act = a.betAction!!
        for (h in 0 until Combos.COUNT) {
            if (a.heroRange[h] <= 0f || a.frequency(act, h) <= 0f) continue
            val t = a.terms(act, h)!!
            if (!hasSpade(h)) assertTrue(a.betSize(h) <= RiverPolicy().thinBet + 1e-9, "${Combos.name(h)}: no big bet without a spade")
            if (t.equity < 0.5f) assertTrue(topSpade(h), "${Combos.name(h)}: bluffs need A♠ / K♠")
        }
        for (x in listOf("QhQd", "9h9d", "Qh9h")) if (a.heroRange[k(x)] > 0f) {
            val h = k(x)
            assertTrue(a.frequency(act, h) == 0f || a.betSize(h) <= RiverPolicy().thinBet + 1e-9, "$x: never a big bet")
            val r = m.respond(h, 0.7)
            assertTrue(!m.reraiseResponse(h, 0.7 * 30.0, r).stacksOff, "$x: never jams over a raise")
        }
        val fs = direct("Qs9s4s2c7s", FlopBoards.BB_DEFEND, FlopBoards.CO_OPEN, false, false, RiverLine.BARREL_CALL, 30.0, 80.0, 20.0)
        val f = fs.analyze(ctx)
        RiverInvariants.all(f, "4-flush facing")
        for (h in 0 until Combos.COUNT) {
            if (f.heroRange[h] <= 0f || f.frequency(RiverAction.RAISE, h) <= 0f) continue
            assertTrue(hasSpade(h), "${Combos.name(h)}: raise only with a spade")
            val r = f.terms(RiverAction.RAISE, h)!!
            if (r.equity < 0.5f) assertTrue(topSpade(h))
        }
        for (x in listOf("9h9d", "Qh9h", "4h4d")) if (f.heroRange[k(x)] > 0f) assertEquals(0f, f.frequency(RiverAction.RAISE, k(x)), 1e-6f)
    }

    // ---- Spot 8: massive sweep -----------------------------------------------------------------------------

    private fun archetype(fold: Double, over: Double, raise: Double, foldRaise: Double, foldJam: Double, bluff: Double, donk: Double, elasticity: Double): RiverNodeLocks =
        RiverNodeLocks.NONE
            .with(RiverVillainNode.VS_BET, RiverNodeLock(fold = fold, raise = raise))
            .with(RiverVillainNode.VS_OVERBET, RiverNodeLock(fold = over))
            .with(RiverVillainNode.VS_RAISE, RiverNodeLock(fold = foldRaise))
            .with(RiverVillainNode.VS_JAM, RiverNodeLock(fold = foldJam))
            .with(RiverVillainNode.BET, RiverNodeLock(valueShare = 1 - bluff))
            .with(RiverVillainNode.DONK, RiverNodeLock(frequency = donk, valueShare = 1 - bluff))
            .with(RiverVillainNode.RIVER_ELASTICITY, RiverNodeLock(frequency = elasticity))

    @Test fun spot8MassiveSweep() {
        val profiles = listOf(
            "population" to RiverNodeLocks.NONE,
            "station" to archetype(0.10, 0.15, 0.03, 0.20, 0.15, 0.30, 0.10, 0.95),
            "nit" to archetype(0.60, 0.72, 0.03, 0.80, 0.85, 0.05, 0.03, 0.30),
            "maniac" to archetype(0.20, 0.30, 0.25, 0.25, 0.10, 0.60, 0.25, 0.80),
            "overfolder" to archetype(0.75, 0.85, 0.02, 0.85, 0.90, 0.20, 0.05, 0.35),
        )
        val boards = listOf(listOf("JhTh4c", "2c", "3d"), listOf("AsKh7d", "2c", "Ks"), listOf("9s8s4d", "2c", "Tc"), listOf("Qc8d3c", "5s", "2c"))
        val stacks = listOf(0.0, 1.0, 10.0, 200.0)
        var solves = 0; var margins = 0
        val t0 = System.currentTimeMillis()
        for ((flop, turn, river) in boards) {
            for (line in RiverLine.entries) {
                val ts = turnSpot(flop, turn, multiway = line == RiverLine.MULTIWAY_3WAY)
                val rr = TurnToRiverRangeFilter.fromTurn(ts, TurnContext(Stake.NL50), line, c(river))
                if (rr.hero.none { it > 0f } || rr.villain.none { it > 0f } || rr.extra?.none { it > 0f } == true) continue
                val board = ts.board + c(river)
                for (stack in stacks) for (facing in listOf(false, true)) {
                    if (facing && stack == 0.0) continue
                    val spot = RiverSpot(board, rr.hero, rr.villain, rr.heroInPosition, rr.heroIsAggressor, line, rr.potBb, stack,
                        if (facing) minOf(0.6 * rr.potBb, stack) else 0.0, rr.extra)
                    val session = RiverSession(spot)
                    for ((name, locks) in profiles) {
                        val cx = ctx.copy(locks = locks)
                        val a = session.analyze(cx)
                        val tag = "$flop$turn$river $line stack=$stack facing=$facing $name"
                        RiverInvariants.all(a, tag)
                        solves++
                        if (stack == 0.0) assertEquals(listOf(RiverAction.CHECK), a.actions, tag)
                        if (facing || spot.multiway || stack <= 0.0) continue
                        val m = session.math(cx)
                        for (h in 0 until Combos.COUNT step 7) {
                            if (m.hero[h] <= 0f) continue
                            val size = a.betSize(h).takeIf { !it.isNaN() } ?: continue
                            val x = minOf(size * spot.potBb, stack)
                            if (x >= stack) continue
                            val rr2 = m.reraiseResponse(h, x, m.respond(h, size))
                            if (rr2.jamEv.isFinite() && rr2.jamEquity < 0.5) {
                                assertTrue(rr2.jamEv >= maxOf(rr2.foldEv, rr2.callEv) + RiverPolicy().stackOffBluffMarginBb - 1e-9, "$tag margin")
                                margins++
                            }
                            if (rr2.rawJamEv.isFinite() && rr2.jamEquity < 0.5 && rr2.rawJamEv < maxOf(rr2.foldEv, rr2.callEv) + RiverPolicy().stackOffBluffMarginBb)
                                assertTrue(rr2.best != RiverAction.RAISE, "$tag bluff jam below margin removed")
                        }
                    }
                }
            }
        }
        println("R8 sweep: $solves river solves, $margins bluff-jam margin checks, ${System.currentTimeMillis() - t0} ms")
        assertTrue(solves > 250)
    }
}
