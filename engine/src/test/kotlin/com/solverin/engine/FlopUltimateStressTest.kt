package com.solverin.engine

import com.solverin.engine.FlopBoards.BB_DEFEND
import com.solverin.engine.FlopBoards.BTN_FLAT
import com.solverin.engine.FlopBoards.CO_OPEN
import com.solverin.engine.FlopBoards.board
import com.solverin.engine.FlopBoards.range
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Multiway sandwiches, donk defense at medium stacks, multi-raise stack-off plans and hostile node locks. */
class FlopUltimateStressTest {
    private val engine = FlopTest.flop
    private val nl50 = FlopContext(Stake.NL50)

    private fun weighted(a: FlopAnalysis, act: FlopAction) = a.frequency(act)

    /** Spot 1: 3-way pots — sandwich pressure, OOP realization and pot control with vulnerable hands. */
    @Test fun spot1_multiwaySandwich() {
        for (bs in listOf("9h8h7c", "Ks9d4c", "Jd7d5s")) {
            val b = board(bs)
            val bb = range(BB_DEFEND, b); val co = range(CO_OPEN, b); val btn = range(BTN_FLAT, b)
            // BB first to act vs CO (PFR) with BTN behind.
            val hu = engine.analyze(FlopSpot(b, bb, co, false, false, 8.0, 97.5), nl50)
            val mw = engine.analyze(FlopSpot(b, bb, co, false, false, 8.0, 97.5, extraRange = btn), nl50)
            assertTrue(mw.spot.multiway)
            assertTrue(weighted(mw, FlopAction.DONK) <= weighted(hu, FlopAction.DONK) + 0.02, "$bs leads less into two players")
            var lower = 0; var total = 0
            for (k in 0 until Combos.COUNT) {
                if (bb[k] <= 0f) continue
                total++
                if (mw.terms(FlopAction.CHECK, k)!!.realization <= hu.terms(FlopAction.CHECK, k)!!.realization + 1e-6) lower++
            }
            assertEquals(total, lower, "$bs 3-way realization never exceeds heads-up")
            // Vulnerable one-pair hands control the pot 3-way.
            val tt = Combos.parse("TsTd")
            if (bs == "9h8h7c" && bb[tt] > 0f) assertTrue(mw.frequency(FlopAction.CHECK, tt) >= 0.99f, "TT checks 3-way on 987hh")
            // BB facing CO's c-bet with BTN still to act: squeeze risk on every call, more folds than heads-up.
            val huBet = engine.analyze(FlopSpot(b, bb, co, false, false, 8.0, 97.5, facingBetBb = 4.0), nl50)
            val mwBet = engine.analyze(FlopSpot(b, bb, co, false, false, 8.0, 97.5, facingBetBb = 4.0, extraRange = btn), nl50)
            for (k in 0 until Combos.COUNT) if (bb[k] > 0f) assertTrue(mwBet.terms(FlopAction.CALL, k)!!.pRaise > 0.0)
            assertTrue(weighted(mwBet, FlopAction.FOLD) >= weighted(huBet, FlopAction.FOLD) - 0.02, "$bs sandwich folds more")
            // BTN in position after both opponents checked: bluffing must get through two ranges.
            val huIp = engine.analyze(FlopSpot(b, btn, bb, true, false, 8.0, 97.5), nl50)
            val mwIp = engine.analyze(FlopSpot(b, btn, bb, true, false, 8.0, 97.5, extraRange = co), nl50)
            assertTrue(mwIp.terms(FlopAction.BET, Combos.parse("AhKc"))?.let { m ->
                huIp.terms(FlopAction.BET, Combos.parse("AhKc"))!!.pFold >= m.pFold
            } ?: true, "$bs fold equity shrinks 3-way")
            for (a in listOf(hu, mw, huBet, mwBet, huIp, mwIp)) FlopInvariants.all(a)
        }
    }

    /** Spot 2: polarized vs weak donks at medium stacks — MDF-anchored defense, no stack-offs with weak draws. */
    @Test fun spot2_donkDefenseMediumStacks() {
        val utg = "22+,A2s+,K9s+,Q9s+,J9s+,T9s,98s,87s,76s,ATo+,KJo+,QJo"
        for (bs in listOf("6h5h4c", "8d7d3s", "Tc9c2h")) {
            val b = board(bs)
            val h = range(utg, b); val v = range(BB_DEFEND, b)
            val spot = FlopSpot(b, h, v, true, true, 5.5, 33.0, facingBetBb = 4.0)
            val weak = engine.analyze(spot, nl50.copy(locks = FlopNodeLocks.donkFrequency(0.2, valueShare = 0.30)))
            val polar = engine.analyze(spot, nl50.copy(locks = FlopNodeLocks.donkFrequency(0.2, valueShare = 0.85)))
            val alpha = 1 - weak.mdf!!
            assertEquals(DonkType.WEAKNESS, weak.donkRead!!.type)
            assertTrue(weak.frequency(FlopAction.FOLD) <= alpha + nl50.policy.weakDonkMdfSlack + 1e-6, "$bs overfolds a weak donk: ${weak.frequency(FlopAction.FOLD)} vs ${alpha}")
            assertTrue(polar.frequency(FlopAction.FOLD) >= weak.frequency(FlopAction.FOLD) - 0.02)
            val ctx = nl50.copy(locks = FlopNodeLocks.donkFrequency(0.2, valueShare = 0.30))
            val m = FlopMathEngine(spot, ctx, FlopEquityMatrix(b, h, v))
            val betRange = m.villainModel.firstAction(m.villain, FlopVillainNode.DONK, m.donkRead?.valueShare).raise
            val resp = m.villainModel.respondToRaise(betRange)
            for (k in 0 until Combos.COUNT) {
                if (h[k] <= 0f) continue
                val weakDraw = m.outs[k] in 1..4 && m.strength.category[k] == 0
                if (!weakDraw) continue
                val rr = m.reraiseResponse(k, 9.5, m.raiseTo(4.0, 9.5), 0.0, 4.0, resp, level = 2)
                assertTrue(!rr.stacksOff, "$bs ${Combos.name(k)} weak draw calls off vs a 3-bet")
            }
            FlopInvariants.all(weak); FlopInvariants.all(polar)
        }
    }

    /** Spot 3: postflop 3-bets / 4-bets — commit with nuts and sets, strict retreat with marginal hands. */
    @Test fun spot3_multiRaiseStackOffPlan() {
        val b = board("Ks9d4c")
        val bb = range(BB_DEFEND, b); val co = range(CO_OPEN, b)
        // Line A: CO bets, BB raises, CO jams (4-bet) -> BB call / fold. Hero = CO (level 1 bet, may jam).
        val spotA = FlopSpot(b, co, bb, true, true, 5.5, 97.5)
        val mA = FlopMathEngine(spotA, nl50, FlopEquityMatrix(b, co, bb))
        val respA = mA.villainModel.respondToBet(mA.villainFirstAction.fold, 5.5, 3.0)
        // Line B: BB check-raises CO's bet, CO 3-bets -> BB call / fold. Hero = BB (level 2 raise).
        val spotB = FlopSpot(b, bb, co, false, false, 5.5, 97.5, facingBetBb = 3.0)
        val mB = FlopMathEngine(spotB, nl50, FlopEquityMatrix(b, bb, co))
        val respB = mB.villainModel.respondToRaise(mB.villainFirstAction.raise)
        for ((m, hero, rr) in listOf(
            Triple(mA, co) { k: Int -> mA.reraiseResponse(k, 5.5, 3.0, 0.0, 0.0, respA, 1) },
            Triple(mB, bb) { k: Int -> mB.reraiseResponse(k, 8.5, mB.raiseTo(3.0, 8.5), 0.0, 3.0, respB, 2) },
        )) {
            var nutW = 0.0; var nutOff = 0.0
            for (k in 0 until Combos.COUNT) {
                if (hero[k] <= 0f) continue
                val r = rr(k)
                val marginal = m.strength.category[k] >= 1 && !m.isStrong(k) && m.outs[k] == 0
                if (marginal) assertTrue(!r.stacksOff, "${Combos.name(k)} marginal stacks off")
                if (m.isNut(k)) { nutW += hero[k]; if (r.best != FlopAction.FOLD) nutOff += hero[k] }
                if (m.strength.category[k] >= 3) assertTrue(r.best != FlopAction.FOLD, "${Combos.name(k)} sets or better never fold to a 3-bet / 4-bet")
                assertTrue(r.value >= -r.invested - 1e-9)
                if (r.call.allIn) assertEquals(1f, r.call.realization, 1e-6f)
            }
            assertTrue(nutW == 0.0 || nutOff / nutW >= 0.6, "nut tier continues vs multi-raises: ${nutOff / nutW}")
        }
    }

    /** Spot 4: hostile asymmetric node locks — instant recalculation, exact lock fidelity and clamping. */
    @Test fun spot4_hostileNodeLocks() {
        for (bs in listOf("Ad7c2h", "Jh9h8c", "5s5d2c")) {
            val b = board(bs)
            val h = range(CO_OPEN, b); val v = range(BB_DEFEND, b)
            val session = engine.session(FlopSpot(b, h, v, true, true, 5.5, 97.5))
            val t0 = System.nanoTime(); val pop = session.analyze(nl50); val first = System.nanoTime() - t0
            val t1 = System.nanoTime()
            val station = session.analyze(nl50.copy(locks = FlopBoards.STATION))
            val nit = session.analyze(nl50.copy(locks = FlopBoards.NIT))
            val relock = (System.nanoTime() - t1) / 2
            assertTrue(relock * 2 < first, "$bs re-lock ${relock / 1e6} ms vs first ${first / 1e6} ms")
            fun bluffShare(a: FlopAnalysis): Double {
                var t = 0.0; var bl = 0.0
                val m = FlopMathEngine(a.spot, nl50, session.matrix)
                for (k in 0 until Combos.COUNT) {
                    val w = h[k] * a.frequency(FlopAction.BET, k)
                    if (w <= 0f) continue
                    t += w
                    if (a.terms(FlopAction.BET, k)!!.equity < 0.5f && !m.isNut(k)) bl += w
                }
                return if (t > 0) bl / t else 0.0
            }
            assertTrue(bluffShare(nit) >= bluffShare(station) - 1e-9, "$bs bluffs more vs nits")
            for (a in listOf(pop, station, nit)) FlopInvariants.all(a)
            // Lock fidelity: villain's unblocked fold share equals the locked value.
            val m = FlopMathEngine(session.spot, nl50.copy(locks = FlopBoards.NIT), session.matrix)
            val resp = m.villainModel.respondToBet(m.villainFirstAction.fold, 5.5, 3.0)
            assertEquals(0.65, resp.foldTotal / resp.priorTotal, 1e-4)
            val raises = m.villainModel.respondToRaise(m.villainFirstAction.raise)
            assertEquals(0.75, raises.foldTotal / raises.priorTotal, 1e-4)
        }
        // Clamping of the bluff cap ratio under absurd inputs.
        for (fold in listOf(0.0, 0.5, 1.0)) for (mdf in listOf(0.0, 0.5, 0.99)) for (locked in listOf(false, true)) {
            val r = FlopBluffCap.ratio(true, 0.5, fold, mdf, locked)
            assertTrue(r in 0.25..(if (locked) FlopBluffCap.MAX_LOCKED else FlopBluffCap.MAX))
        }
    }

    /** Villain checking ranges keep draws (R_post vulnerability stays live after hero checks). */
    @Test fun checkingRangesKeepDraws() {
        for (bs in listOf("Jh9h8c", "7s6s4d", "Th8c7c")) {
            val b = board(bs)
            val h = range(BB_DEFEND, b); val v = range(CO_OPEN, b)
            val m = FlopMathEngine(FlopSpot(b, h, v, false, false, 5.5, 97.5), nl50, FlopEquityMatrix(b, h, v))
            assertTrue(m.drawDensity(m.villainFirstAction.fold) > 0.0, "$bs checking range has no draws")
            assertTrue(m.drawDensity(m.villainFirstAction.raise) > 0.0)
        }
    }

    /** Wide 3-way sweep: textures × SPR × node types × locks, all invariants. */
    @Test fun multiwaySweep() {
        var n = 0
        for (b in FlopBoards.textureBoards(perBucket = 1)) {
            val bb = range(BB_DEFEND, b); val co = range(CO_OPEN, b); val btn = range(BTN_FLAT, b)
            for (stack in listOf(4.0, 24.0, 97.5)) {
                val sessions = listOf(
                    engine.session(FlopSpot(b, bb, co, false, false, 8.0, stack, extraRange = btn)),
                    engine.session(FlopSpot(b, bb, co, false, false, 8.0, stack, facingBetBb = minOf(stack, 4.0), extraRange = btn)),
                    engine.session(FlopSpot(b, btn, bb, true, false, 8.0, stack, extraRange = co)),
                )
                for (s in sessions) for (l in listOf(FlopNodeLocks.NONE, FlopBoards.STATION, FlopBoards.NIT)) {
                    FlopInvariants.all(s.analyze(nl50.copy(locks = l)))
                    n++
                }
            }
        }
        assertTrue(n >= 100, "multiway solves $n")
    }
}
