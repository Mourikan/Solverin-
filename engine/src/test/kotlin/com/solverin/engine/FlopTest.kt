package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.*
import com.solverin.engine.range.RangeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlopTest {
    companion object {
        val flop = FlopEngine()
        /** Standard BTN open (~45%) and BB flat (~40%) ranges, single-raised pot. */
        val btnOpen: FloatArray = Combos.fromClassWeights(RangeParser.parse(
            "22+,A2s+,A2o+,K2s+,K7o+,Q4s+,Q8o+,J6s+,J8o+,T6s+,T8o+,95s+,98o,85s+,87o,74s+,76o,63s+,53s+,43s"))
        val bbFlat: FloatArray = Combos.fromClassWeights(RangeParser.parse(
            "22-99,A2s-AJs,A2o-AJo,K2s-KJs,K5o-KJo,Q2s-QJs,Q8o-QJo,J4s-JTs,J8o-JTo,T6s-T9s,T8o-T9o,96s+,97o-98o,85s+,86o-87o,74s+,75o-76o,63s+,65o,52s+,54o,42s+,32s"))
    }

    private fun board(s: String) = s.chunked(2).map { Card.parse(it) }.toIntArray()

    private fun btnVsBb(b: String, facingBet: Double = 0.0, heroIp: Boolean = true, stack: Double = 97.5, pot: Double = 5.5) =
        FlopSpot(board(b), if (heroIp) btnOpen else bbFlat, if (heroIp) bbFlat else btnOpen,
            heroInPosition = heroIp, heroIsAggressor = heroIp, potBb = pot, effectiveStackBb = stack, facingBetBb = facingBet)

    private fun identities(a: FlopAnalysis) = FlopInvariants.all(a)

    @Test fun isomorphism() {
        var total = 0
        for (id in 0 until FlopIso.COUNT) total += FlopIso.weight(id)
        assertEquals(FlopIso.RAW_FLOPS, total)
        val seen = HashSet<Int>()
        for (a in 0 until 52) for (b in a + 1 until 52) for (c in b + 1 until 52) seen += FlopIso.id(a, b, c)
        assertEquals(FlopIso.COUNT, seen.size)
        // Suit permutations map to the same class; rank changes do not.
        val perm = intArrayOf(2, 0, 3, 1)
        for (s in listOf("AsKd2c", "9h8h7c", "QsQh5d", "Th6h2h", "7c7d7h", "5s4s3d")) {
            val b = board(s)
            val p = IntArray(3) { Card.id(Card.rank(b[it]), perm[Card.suit(b[it])]) }
            assertEquals(FlopIso.id(b), FlopIso.id(p), s)
            assertEquals(FlopIso.id(b), FlopIso.id(b[2], b[0], b[1]), s)
        }
        assertTrue(FlopIso.id(board("AsKd2c")) != FlopIso.id(board("AsKs2c")))
        assertEquals(FlopIso.Pattern.MONOTONE, FlopIso.pattern(FlopIso.id(board("Th6h2h"))))
        assertEquals(FlopIso.Pattern.PAIRED_TWO_TONE, FlopIso.pattern(FlopIso.id(board("QsQh5h"))))
        assertEquals(4, FlopIso.weight(FlopIso.id(board("Th6h2h"))))
        val rep = FlopIso.representative(FlopIso.id(board("9h8h7c")))
        assertEquals(FlopIso.id(board("9h8h7c")), FlopIso.id(rep))
    }

    @Test fun textures() {
        val t1 = Textures.of(board("9h8h7c"))
        assertEquals(SuitTexture.TWO_TONE, t1.suits); assertEquals(Connectivity.HIGH, t1.connectivity)
        val t2 = Textures.of(board("Ad7c2s"))
        assertEquals(SuitTexture.RAINBOW, t2.suits); assertEquals(Connectivity.LOW, t2.connectivity); assertEquals(Height.HIGH, t2.height)
        val t3 = Textures.of(board("KsKd5h"))
        assertEquals(Pairing.PAIRED, t3.pairing)
        assertTrue(t1.volatility > t2.volatility && t2.volatility > Textures.of(board("7c7d7h")).volatility)
    }

    @Test fun exactFlopEquity() {
        val eq = FlopEquity(FlopEquity.EXACT)
        val b = board("2h7c9d")
        val aa = Combos.parse("AhAd"); val kk = Combos.parse("KcKs")
        val v = FloatArray(Combos.COUNT).also { it[kk] = 1f }
        val h = FloatArray(Combos.COUNT).also { it[aa] = 1f }
        val e = eq.handsVsRange(b, v, h).equity(aa)
        assertEquals(0.912, e.toDouble(), 0.01)
        val back = eq.handsVsRange(b, h, v).equity(kk)
        assertEquals(1.0, (e + back).toDouble(), 1e-6)
        assertEquals(9, Draws.outs(board("Qh7h2c"), Combos.parse("AhKh")))
        assertEquals(8, Draws.outs(board("9s8d2c"), Combos.parse("JhTh")).coerceAtMost(8))
        assertEquals(4, Draws.outs(board("Ks9d2c"), Combos.parse("QhJc")))
        assertEquals(0, Draws.outs(board("9s8d7c"), Combos.parse("JhTh")))
    }

    @Test fun rangeAndNutAdvantageDriveCbets() {
        val dry = flop.analyze(btnVsBb("AsKd2c"), FlopContext(Stake.NL50))
        val wet = flop.analyze(btnVsBb("7h6h5c"), FlopContext(Stake.NL50))
        assertEquals(FlopNodeType.VS_CHECK, dry.nodeType)
        assertTrue(dry.rangeAdvantage > 0.0, "BTN range advantage on AK2: ${dry.rangeAdvantage}")
        assertTrue(dry.nutAdvantage > wet.nutAdvantage, "nut adv AK2 ${dry.nutAdvantage} vs 765 ${wet.nutAdvantage}")
        assertTrue(dry.frequency(FlopAction.BET) > wet.frequency(FlopAction.BET),
            "c-bet AK2 ${dry.frequency(FlopAction.BET)} vs 765 ${wet.frequency(FlopAction.BET)}")
        assertTrue(dry.frequency(FlopAction.BET) > 0.4)
        assertNotNull(dry.betSizeFraction)
        identities(dry); identities(wet)
    }

    @Test fun donkReadsByTexture() {
        val low = flop.analyze(btnVsBb("7h6h5c", facingBet = 2.0), FlopContext(Stake.NL50))
        assertTrue(low.spot.isDonk)
        assertEquals(DonkType.STRENGTH, assertNotNull(low.donkRead).type)
        val high = flop.analyze(btnVsBb("AsKd2c", facingBet = 2.0), FlopContext(Stake.NL50))
        assertTrue(assertNotNull(high.donkRead).type != DonkType.STRENGTH)
        // Vs a strength-read donk hero folds more than vs a weakness-read donk.
        assertTrue(low.frequency(FlopAction.FOLD) > high.frequency(FlopAction.FOLD))
        val locked = flop.analyze(btnVsBb("AsKd2c", facingBet = 2.0),
            FlopContext(Stake.NL50, locks = FlopNodeLocks.donkFrequency(0.3, valueShare = 0.9)))
        assertEquals(DonkType.STRENGTH, locked.donkRead!!.type)
        identities(low); identities(high)
    }

    @Test fun minBetsNeverFoldPotOddsEquity() {
        val a = flop.analyze(btnVsBb("Jd8s3c", facingBet = 1.0, heroIp = false, pot = 20.0), FlopContext(Stake.NL50))
        assertTrue(a.minBet)
        val odds = assertNotNull(a.potOdds)
        assertEquals(1.0 / 22.0, odds, 1e-12)
        assertEquals(20.0 / 21.0, a.mdf!!, 1e-12)
        for (k in 0 until Combos.COUNT) {
            if (a.heroRange[k] <= 0f) continue
            val e = a.terms(FlopAction.CALL, k)!!.equity
            if (e >= odds) assertEquals(0f, a.frequency(FlopAction.FOLD, k), "${Combos.name(k)} folds to a min-bet with ${e} equity")
        }
        assertTrue(a.frequency(FlopAction.FOLD) < 0.1)
    }

    @Test fun lowSprCommitsInsteadOfCallingOff() {
        val a = flop.analyze(btnVsBb("Qs9h4d", facingBet = 6.0, heroIp = false, stack = 10.0, pot = 20.0), FlopContext(Stake.NL50))
        for (k in 0 until Combos.COUNT) {
            if (a.heroRange[k] <= 0f) continue
            assertTrue(a.terms(FlopAction.CALL, k)!!.allIn, "calling 6 of 10 behind into 26 is a commitment")
        }
        identities(a)
    }

    @Test fun nodeLockedFoldToCbet() {
        val ctx = FlopContext(Stake.NL50)
        val folder = flop.analyze(btnVsBb("Td7c3s"), ctx.copy(locks = FlopNodeLocks.foldToCbet(0.8)))
        val station = flop.analyze(btnVsBb("Td7c3s"), ctx.copy(locks = FlopNodeLocks.foldToCbet(0.2)))
        assertTrue(folder.frequency(FlopAction.BET) > station.frequency(FlopAction.BET),
            "bet vs folder ${folder.frequency(FlopAction.BET)} vs station ${station.frequency(FlopAction.BET)}")
        identities(folder); identities(station)
    }

    @Test fun oopFirstToActAndCheckRaise() {
        val first = flop.analyze(btnVsBb("Kc8h4h", heroIp = false), FlopContext(Stake.NL50))
        assertEquals(FlopNodeType.FIRST_TO_ACT, first.nodeType)
        assertTrue(FlopAction.DONK in first.actions && FlopAction.BET !in first.actions, "BB leads are donk bets")
        identities(first)
        val vsCbet = flop.analyze(btnVsBb("Kc8h4h", facingBet = 2.5, heroIp = false), FlopContext(Stake.NL50))
        assertTrue(!vsCbet.spot.isDonk)
        assertTrue(vsCbet.frequency(FlopAction.RAISE) > 0.0 && vsCbet.frequency(FlopAction.CALL) > 0.0)
        // Sets check-raise more than they fold.
        val set = Combos.parse("8s8d")
        if (vsCbet.heroRange[set] > 0f) assertEquals(0f, vsCbet.frequency(FlopAction.FOLD, set))
        identities(vsCbet)
    }

    @Test fun canonicalFlopSweep() {
        // Every 60th canonical flop, all three node types: identities + max-EV consistency on each.
        val ctx = FlopContext(Stake.NL50)
        var n = 0
        for (id in 0 until FlopIso.COUNT step 60) {
            val b = FlopIso.representative(id)
            val s = Card.toString(b[0]) + Card.toString(b[1]) + Card.toString(b[2])
            identities(flop.analyze(btnVsBb(s), ctx))
            identities(flop.analyze(btnVsBb(s, heroIp = false), ctx))
            identities(flop.analyze(btnVsBb(s, facingBet = 3.0, heroIp = false), ctx))
            n++
        }
        assertEquals(30, n)
    }

    @Test fun bitPackedIsomorphismEngine() {
        val packed = BitPackedArray(1000, 11)
        for (i in 0 until 1000) packed[i] = (i * 7919) % 2048
        for (i in 0 until 1000) assertEquals((i * 7919) % 2048, packed[i])
        assertTrue(FlopIsomorphismEngine.memoryBytes < 45_000, "iso tables ${FlopIsomorphismEngine.memoryBytes} B")
        val seen = HashSet<Int>()
        for (a in 0 until 52) for (b in a + 1 until 52) for (c in b + 1 until 52) seen += FlopIsomorphismEngine.rawIndex(a, b, c)
        assertEquals(FlopIsomorphismEngine.RAW, seen.size)
        assertEquals(0, seen.min()); assertEquals(FlopIsomorphismEngine.RAW - 1, seen.max())
        val rnd = java.util.Random(7)
        val flops = Array(4096) { val d = (0 until 52).shuffled(rnd).take(3); intArrayOf(d[0], d[1], d[2]) }
        var sink = 0L
        repeat(20_000) { for (f in flops) sink += FlopIsomorphismEngine.canonicalId(f[0], f[1], f[2]) } // warm-up
        val t0 = System.nanoTime()
        repeat(250) { for (f in flops) sink += FlopIsomorphismEngine.canonicalId(f[0], f[1], f[2]) }
        val ns = (System.nanoTime() - t0).toDouble() / (250 * flops.size)
        assertTrue(ns < 2_000, "canonical lookup $ns ns")
        assertTrue(sink != 0L)
        val t = Textures.of(board("Th6h2h"))
        assertEquals(SuitTexture.MONOTONE, t.suits)
        assertEquals(FlopIsomorphismEngine.straightCombos(t.flopId), t.straightCombos)
    }

    @Test fun equityMatrixMatchesRunoutEnumeration() {
        val b = board("Jh9h4c")
        val hero = Combos.fromClassWeights(RangeParser.parse("TT+,AJs+,KQs,QJs,JTs,T9s,98s,AQo+"), Combos.mask(b))
        val vil = Combos.fromClassWeights(RangeParser.parse("22-99,A2s-ATs,KTs+,QTs+,J9s+,T8s+,97s+,86s+,KJo+,QJo"), Combos.mask(b))
        val matrix = FlopEquityMatrix(b, hero, vil, stride = 4)
        val direct = FlopEquity(4)
        val m = matrix.heroVs(vil); val d = direct.handsVsRange(b, vil, hero)
        val sub = FloatArray(Combos.COUNT) { if (it % 3 == 0) vil[it] else 0f }
        val ms = matrix.heroVs(sub); val ds = direct.handsVsRange(b, sub, hero)
        for (k in 0 until Combos.COUNT) if (hero[k] > 0f) {
            // Matrix weights each compatible pair equally; direct enumeration weights pairs by sampled runouts.
            assertEquals(d.equity(k), m.equity(k), 2e-3f, Combos.name(k))
            assertEquals(ds.equity(k), ms.equity(k), 2e-3f, Combos.name(k))
        }
        val mv = matrix.villainVs(hero); val dv = direct.handsVsRange(b, hero, vil)
        for (k in 0 until Combos.COUNT) if (vil[k] > 0f) assertEquals(dv.equity(k), mv.equity(k), 2e-3f, Combos.name(k))
        assertTrue(matrix.bytes < 2_000_000)
    }

    @Test fun geometricAndElasticSizing() {
        assertEquals((Math.pow(21.0, 1.0 / 3) - 1) / 2, BetSizingOptimizer.geometric(10.0, 3), 1e-12)
        assertEquals(1.0, BetSizingOptimizer.geometric(1.0, 1), 1e-12) // SPR 1, one street: pot-size bet
        val p = FlopPolicy()
        val dryNuts = BetSizingOptimizer.candidates(17.7, 0.08, 0.06, -0.6, p)
        assertTrue(p.overbet in dryNuts && p.smallBet in dryNuts, "$dryNuts")
        val noNuts = BetSizingOptimizer.candidates(17.7, -0.02, 0.0, -0.6, p)
        assertTrue(p.overbet !in noNuts && p.mediumBet in noNuts)
        val inelastic = BetSizingOptimizer.candidates(17.7, 0.02, 0.0, 0.0, p)
        assertTrue(inelastic.size > BetSizingOptimizer.candidates(17.7, 0.02, 0.0, -0.6, p).size)
        assertTrue(BetSizingOptimizer.candidates(0.4, 0.05, 0.05, -0.6, p).all { it <= 0.4 + 1e-9 }, "capped by stack")
        val locked = flop.analyze(btnVsBb("Td7c3s"), FlopContext(Stake.NL50, locks = FlopNodeLocks.foldToCbet(0.5)))
        assertEquals(0.0, locked.elasticity, 1e-12)
        val pop = flop.analyze(btnVsBb("Td7c3s"), FlopContext(Stake.NL50))
        assertTrue(pop.elasticity < -0.3, "MDF-scaled villain is elastic: ${pop.elasticity}")
        assertTrue(pop.betSizeFraction!! in pop.sizeCandidates)
    }

    @Test fun realizationPenalizedByVillainOuts() {
        val b = board("Th9h3c")
        val hero = Combos.fromClassWeights(RangeParser.parse("ATs,ATo,KTs,KTo,QTs,QTo"), Combos.mask(b))
        val drawy = Combos.fromClassWeights(RangeParser.parse("QJs,J8s,87s,KJs,AKs,A5s,QJo,J8o"), Combos.mask(b))
        val dry = Combos.fromClassWeights(RangeParser.parse("A3s,K3s,Q3s,33,44,55,66,A4o,K4o"), Combos.mask(b))
        val k = Combos.parse("KdTd")
        fun r(v: FloatArray) = flop.analyze(FlopSpot(b, hero, v, true, true, 6.0, 97.0), FlopContext(Stake.NL50)).terms(FlopAction.CHECK, k)!!.realization
        assertTrue(r(drawy) < r(dry), "top pair realizes less vs a draw-heavy range: ${r(drawy)} vs ${r(dry)}")
    }

    @Test fun nodeLockRecalculationReusesEquityMatrix() {
        val session = flop.session(btnVsBb("Qd8c5s"))
        val t0 = System.nanoTime()
        val a = session.analyze(FlopContext(Stake.NL50))
        val first = System.nanoTime() - t0
        val matrix = session.matrix
        val t1 = System.nanoTime()
        val b = session.analyze(FlopContext(Stake.NL50, locks = FlopNodeLocks.foldToCbet(0.75)))
        val second = System.nanoTime() - t1
        assertTrue(matrix === session.matrix)
        assertTrue(second * 2 < first, "re-analysis ${second / 1e6} ms vs first ${first / 1e6} ms")
        assertTrue(b.frequency(FlopAction.BET) >= a.frequency(FlopAction.BET))
        identities(b)
    }
}
