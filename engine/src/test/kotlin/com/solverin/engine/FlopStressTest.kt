package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.*
import com.solverin.engine.range.RangeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Postflop boundary spots + sweeps: every solve is checked against [FlopInvariants]. */
class FlopStressTest {
    private val engine = FlopTest.flop
    private val nl50 = FlopContext(Stake.NL50)

    private fun board(s: String) = s.chunked(2).map { Card.parse(it) }.toIntArray()
    private fun range(notation: String, b: IntArray) = Combos.fromClassWeights(RangeParser.parse(notation), Combos.mask(b))
    private fun c(s: String) = Combos.parse(s)

    private val utgOpen = "22+,A2s+,K9s+,Q9s+,J9s+,T9s,98s,87s,76s,ATo+,KJo+,QJo"
    private val bbVsUtg = "22-JJ,A2s-AQs,K9s-KQs,Q9s+,J9s+,T8s+,97s+,86s+,75s+,64s+,54s,AJo-AQo,KJo-KQo,QJo"
    private val coOpen = "22+,A2s+,A8o+,K7s+,KTo+,Q9s+,QTo+,J9s+,JTo,T8s+,97s+,86s+,76s,65s"
    private val btnVsCo = "22-JJ,A9s-AQs,KTs-KQs,QTs+,J9s+,T8s+,97s+,86s+,75s+,65s,54s,AJo-AQo,KQo"

    /** Spot 1: UTG opens, BB calls, A♠K♥7♦ — UTG's nut advantage drives a high, optimized c-bet. */
    @Test fun spot1_massiveCbetByRangeAndNutAdvantage() {
        val b = board("AsKh7d")
        val spot = FlopSpot(b, range(utgOpen, b), range(bbVsUtg, b), heroInPosition = true, heroIsAggressor = true,
            potBb = 5.5, effectiveStackBb = 97.5)
        val a = engine.analyze(spot, nl50)
        assertTrue(a.rangeAdvantage > 0.05, "ΔRange ${a.rangeAdvantage}")
        assertTrue(a.nutAdvantage >= nl50.policy.overbetNutEdge, "ΔNuts ${a.nutAdvantage}")
        assertTrue(nl50.policy.overbet in a.sizeCandidates, "overbet candidate ${a.sizeCandidates}")
        assertTrue(a.frequency(FlopAction.BET) >= 0.6, "c-bet ${a.frequency(FlopAction.BET)}")
        for (x in listOf("AcAd", "KcKd", "AdKc", "7c7h")) assertTrue(a.frequency(FlopAction.BET, c(x)) > 0f, "$x bets")
        // The chosen size is the range-EV maximum among candidates.
        val bySize = a.sizeCandidates.associateWith { size ->
            engine.analyze(spot, nl50.copy(policy = nl50.policy.copy(smallBet = size, mediumBet = size, overbet = size,
                overbetNutEdge = 9.0, inelasticThreshold = 9.0, elasticThreshold = -9.0))).rangeEv()
        }
        assertTrue(a.rangeEv() >= bySize.values.max() - 1e-6, "chosen size not EV-max: ${a.rangeEv()} vs $bySize")
        FlopInvariants.all(a)
    }

    /** Spot 2: CO opens, BTN calls, 9♥8♥7♣ with T♠T♦ OOP — vulnerability forces pot control. */
    @Test fun spot2_potControlByVulnerability() {
        val b = board("9h8h7c")
        val spot = FlopSpot(b, range(coOpen, b), range(btnVsCo, b), heroInPosition = false, heroIsAggressor = true,
            potBb = 5.5, effectiveStackBb = 97.5)
        val a = engine.analyze(spot, nl50)
        val tt = c("TsTd")
        val check = assertNotNull(a.terms(FlopAction.CHECK, tt))
        val bet = assertNotNull(a.terms(FlopAction.BET, tt))
        assertTrue(check.ev >= bet.ev, "TT check ${check.ev} < bet ${bet.ev}")
        assertTrue(a.frequency(FlopAction.CHECK, tt) >= 0.5f, "TT checks")
        // Same hand on a dry board is far less vulnerable: higher realization.
        val dry = board("9c3d2s")
        val dryA = engine.analyze(FlopSpot(dry, range(coOpen, dry), range(btnVsCo, dry), false, true, 5.5, 97.5), nl50)
        assertTrue(check.realization < dryA.terms(FlopAction.CHECK, tt)!!.realization,
            "R(TT on 987hh) ${check.realization} vs R(TT on 932r) ${dryA.terms(FlopAction.CHECK, tt)!!.realization}")
        // Never stacks off as a bluff-catcher: facing a pot-size bet + raise war, TT's raise EV is below its best option.
        val vsBet = engine.analyze(FlopSpot(b, range(coOpen, b), range(btnVsCo, b), false, true, 5.5, 97.5, facingBetBb = 5.5), nl50)
        assertTrue(vsBet.frequency(FlopAction.RAISE, tt) == 0f, "TT does not raise into 987hh")
        FlopInvariants.all(a); FlopInvariants.all(dryA); FlopInvariants.all(vsBet)
    }

    /** Spot 3: 10bb pot, villain bets 1bb — strict MDF, no absurd folds. */
    @Test fun spot3_microBetMdf() {
        val b = board("Kd9s4c")
        val spot = FlopSpot(b, range(bbVsUtg, b), range(utgOpen, b), heroInPosition = false, heroIsAggressor = false,
            potBb = 10.0, effectiveStackBb = 90.0, facingBetBb = 1.0)
        val a = engine.analyze(spot, nl50)
        assertTrue(a.minBet)
        assertEquals(10.0 / 11.0, a.mdf!!, 1e-12)
        assertEquals(1.0 / 12.0, a.potOdds!!, 1e-12)
        assertTrue(a.frequency(FlopAction.FOLD) <= 1 - a.mdf!! + 1e-9, "fold ${a.frequency(FlopAction.FOLD)} above 1 - MDF")
        for (k in 0 until Combos.COUNT) {
            if (a.heroRange[k] <= 0f) continue
            val call = a.terms(FlopAction.CALL, k)!!
            if (call.equity >= a.potOdds!!) {
                assertEquals(0f, a.frequency(FlopAction.FOLD, k), "${Combos.name(k)} folds to 1bb")
                val played = maxOf(call.ev, a.ev(FlopAction.RAISE, k).let { if (it.isNaN()) Double.NEGATIVE_INFINITY else it })
                assertTrue(played >= -call.investment, "${Combos.name(k)} continuation bounded by the 1bb call")
            }
        }
        // Same spot with a normal size: folds are allowed again.
        val normal = engine.analyze(FlopSpot(b, range(bbVsUtg, b), range(utgOpen, b), false, false, 10.0, 90.0, facingBetBb = 7.5), nl50)
        assertTrue(!normal.minBet && normal.frequency(FlopAction.FOLD) > a.frequency(FlopAction.FOLD))
        FlopInvariants.all(a); FlopInvariants.all(normal)
    }

    /** Spot 4: hero without the lead facing a polarized donk, plus bounded multi-raise trees OOP. */
    @Test fun spot4_polarizedDonkAndMultiRaise() {
        val b = board("6h5h4c")
        val hero = range(utgOpen, b); val vil = range(bbVsUtg, b)
        fun donk(vs: Double, stack: Double = 97.5) = engine.analyze(
            FlopSpot(b, hero, vil, heroInPosition = true, heroIsAggressor = true, potBb = 5.5, effectiveStackBb = stack, facingBetBb = 4.0),
            nl50.copy(locks = FlopNodeLocks.donkFrequency(0.2, valueShare = vs)))
        val polar = donk(0.85); val weak = donk(0.30)
        assertEquals(DonkType.STRENGTH, polar.donkRead!!.type)
        assertEquals(DonkType.WEAKNESS, weak.donkRead!!.type)
        assertTrue(polar.frequency(FlopAction.FOLD) > weak.frequency(FlopAction.FOLD), "folds more vs a polarized donk")
        assertTrue(polar.frequency(FlopAction.RAISE) < weak.frequency(FlopAction.RAISE) + 1e-9, "raises less vs a polarized donk")
        // Overpairs vs the polarized donk keep calling rather than building a pot they cannot win.
        assertTrue(polar.frequency(FlopAction.RAISE, c("AcAd")) <= weak.frequency(FlopAction.RAISE, c("AcAd")) + 1e-6)
        // Low SPR: a raise is all-in, no re-raise branch exists.
        val shallow = donk(0.85, stack = 12.0)
        for (k in 0 until Combos.COUNT) shallow.terms(FlopAction.RAISE, k)?.let {
            assertTrue(it.allIn && it.pRaise == 0.0, "${Combos.name(k)} shallow raise is a commitment")
        }
        // OOP multi-raise: BB check-raise vs a c-bet, villain re-raise all-in, hero call / fold (3 levels).
        val xr = engine.analyze(FlopSpot(b, vil, hero, heroInPosition = false, heroIsAggressor = false,
            potBb = 5.5, effectiveStackBb = 97.5, facingBetBb = 2.5), nl50)
        assertTrue(xr.frequency(FlopAction.RAISE) > 0.0)
        for (k in 0 until Combos.COUNT) xr.terms(FlopAction.RAISE, k)?.let { t ->
            if (t.pRaise > 0) assertTrue(t.evVsRaise >= -t.investment - 1e-9)
        }
        for (a in listOf(polar, weak, shallow, xr)) FlopInvariants.all(a)
    }

    @Test fun geometricSizingProgression() {
        for (spr in listOf(0.5, 1.0, 2.5, 6.0, 12.0, 17.7, 40.0)) for (streets in 1..3) {
            val f = BetSizingOptimizer.geometric(spr, streets)
            var pot = 1.0; var invested = 0.0
            repeat(streets) { val bet = f * pot; invested += bet; pot += 2 * bet }
            assertEquals(spr, invested, 1e-9, "SPR $spr streets $streets: f=$f does not reach all-in")
        }
        // Elasticity reshapes candidates: an inelastic (locked) villain adds a bigger size, an elastic one a smaller.
        val p = FlopPolicy()
        val base = BetSizingOptimizer.candidates(17.7, 0.02, 0.0, -0.6, p)
        assertTrue(BetSizingOptimizer.candidates(17.7, 0.02, 0.0, 0.0, p).max() > base.max() ||
            BetSizingOptimizer.candidates(17.7, 0.02, 0.0, 0.0, p).size > base.size)
        assertTrue(BetSizingOptimizer.candidates(17.7, 0.02, 0.0, -1.5, p).min() < base.min())
    }

    @Test fun lockedProfileSweep() {
        val boards = listOf("AsKh7d", "9h8h7c", "Kd9s4c", "6h5h4c", "QsQd3c", "Th6h2h", "Jc8d3s", "5s4d2c")
        val profiles = listOf(
            FlopNodeLocks.NONE,
            FlopNodeLocks.foldToCbet(0.75),
            FlopNodeLocks.NONE.with(FlopVillainNode.VS_BET, FlopNodeLock(fold = 0.15, raise = 0.20)),
            FlopNodeLocks.NONE.with(FlopVillainNode.VS_RAISE, FlopNodeLock(fold = 0.2, raise = 0.3))
                .with(FlopVillainNode.CBET, FlopNodeLock(frequency = 0.9, valueShare = 0.4)),
        )
        for (bs in boards) {
            val b = board(bs)
            val hero = range(utgOpen, b); val vil = range(bbVsUtg, b)
            val sessions = listOf(
                engine.session(FlopSpot(b, hero, vil, true, true, 5.5, 97.5)),
                engine.session(FlopSpot(b, vil, hero, false, false, 5.5, 97.5)),
                engine.session(FlopSpot(b, vil, hero, false, false, 5.5, 97.5, facingBetBb = 3.0)),
                engine.session(FlopSpot(b, hero, vil, true, true, 20.0, 15.0)),
            )
            for (s in sessions) for (l in profiles) FlopInvariants.all(s.analyze(nl50.copy(locks = l)))
        }
    }
}
