package com.solverin.engine

import com.solverin.engine.FlopBoards.BB_DEFEND
import com.solverin.engine.FlopBoards.CO_OPEN
import com.solverin.engine.FlopBoards.board
import com.solverin.engine.FlopBoards.range
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stack-off discipline under re-raises: commit with nuts / sets / top two, brake with marginal and vulnerable hands. */
class FlopCommitmentEngineTest {
    private val nl50 = FlopContext(Stake.NL50)

    private class Fixture(val m: FlopMathEngine, val hero: FloatArray, val spot: FlopSpot)

    private fun fixture(b: String, stack: Double = 97.5, pot: Double = 5.5, ctx: FlopContext = nl50): Fixture {
        val bd = board(b)
        val h = range(CO_OPEN, bd); val v = range(BB_DEFEND, bd)
        val spot = FlopSpot(bd, h, v, heroInPosition = true, heroIsAggressor = true, potBb = pot, effectiveStackBb = stack)
        return Fixture(FlopMathEngine(spot, ctx, FlopEquityMatrix(bd, h, v)), h, spot)
    }

    private fun responses(f: Fixture, size: Double, level: Int = 1): Map<Int, FlopMathEngine.ReraiseResponse> {
        val pot = f.spot.potBb
        val bet = minOf(size * pot, f.spot.effectiveStackBb)
        val resp = f.m.villainModel.respondToBet(f.m.villainFirstAction.fold, pot, bet)
        return (0 until Combos.COUNT).filter { f.hero[it] > 0f }.associateWith { f.m.reraiseResponse(it, pot, bet, 0.0, 0.0, resp, level) }
    }

    private fun share(hands: List<Int>, w: FloatArray, pred: (Int) -> Boolean): Double {
        var t = 0.0; var s = 0.0
        for (h in hands) { t += w[h]; if (pred(h)) s += w[h] }
        return if (t > 0) s / t else 1.0
    }

    @Test fun stacksOffWithNutsSetsTopTwoOnDryBoard() {
        val f = fixture("Ks9d4c")
        for (size in listOf(0.33, 0.75)) {
            val r = responses(f, size)
            for (c in listOf("KdKh", "9s9h", "4d4h", "Kh9h", "Ks9s".takeIf { false } ?: "Kd9c")) {
                val k = Combos.parse(c)
                if (f.hero[k] <= 0f) continue
                assertTrue(r.getValue(k).stacksOff, "$c must stack off vs a re-raise (size $size)")
            }
            val nuts = r.keys.filter { f.m.isNut(it) }
            assertTrue(share(nuts, f.hero) { r.getValue(it).stacksOff } >= 0.9, "nut tier stacks off")
        }
    }

    @Test fun brakesWithMarginalVulnerableAndWeakDraws() {
        for (b in listOf("Ks9d4c", "Jh9h8c", "Qd7c2h", "7s6s5d")) {
            val f = fixture(b)
            for (size in listOf(0.33, 0.75)) {
                val r = responses(f, size)
                val marginal = r.keys.filter { f.m.strength.category[it] >= 1 && !f.m.isStrong(it) && f.m.outs[it] == 0 }
                for (h in marginal) assertTrue(!r.getValue(h).stacksOff, "$b ${Combos.name(h)} marginal hand stacks off")
                val weakDraws = r.keys.filter { f.m.outs[it] in 1..4 && f.m.strength.category[it] == 0 }
                for (h in weakDraws) assertTrue(!r.getValue(h).stacksOff, "$b ${Combos.name(h)} weak draw stacks off")
            }
        }
        // Overpairs on a wet straight-heavy board never commit vs a re-raise.
        val wet = fixture("Jh9h8c")
        val r = responses(wet, 0.75)
        for (c in listOf("KdKh", "AsAc", "QcQd")) {
            val k = Combos.parse(c)
            if (wet.hero[k] > 0f) assertTrue(!r.getValue(k).stacksOff, "$c on J98hh")
        }
    }

    @Test fun projectedAllInRespectsRealizationOneAndStack() {
        for (b in listOf("Ks9d4c", "Jh9h8c", "7s6s5d")) {
            val f = fixture(b, stack = 6.0, pot = 6.0)
            val pot = 6.0
            val resp = f.m.villainModel.respondToBet(f.m.villainFirstAction.fold, pot, 0.33 * pot)
            for (h in 0 until Combos.COUNT) {
                if (f.hero[h] <= 0f) continue
                val t = f.m.aggressive(h, pot, 0.33 * pot, 0.0, 0.0, resp, level = 1)
                // ~2bb bet leaves ~4bb behind into a ~10bb pot: SPR < 0.5 → the called branch is a projected all-in.
                assertTrue(t.allIn, "${Combos.name(h)} projected all-in")
                assertEquals(1f, t.realization, 1e-6f)
                assertEquals(6.0, t.investment, 1e-9)
                assertTrue(t.investment <= f.spot.effectiveStackBb + 1e-9)
                val rr = f.m.reraiseResponse(h, pot, 0.33 * pot, 0.0, 0.0, resp, 1)
                assertTrue(rr.reraiseTo <= 6.0 + 1e-9 && (rr.call.allIn || rr.toCall == 0.0))
                if (rr.call.allIn) assertEquals(1f, rr.call.realization, 1e-6f)
            }
        }
    }

    @Test fun levelTwoRaisesCannotJamAgain() {
        val f = fixture("Qd7c2h")
        val pot = 5.5; val bet = 2.5
        val betRange = f.m.villainFirstAction.raise
        val resp = f.m.villainModel.respondToRaise(betRange)
        for (h in 0 until Combos.COUNT) {
            if (f.hero[h] <= 0f) continue
            val rr = f.m.reraiseResponse(h, pot + bet, f.m.raiseTo(bet, pot + bet), 0.0, bet, resp, level = 2)
            assertEquals(Double.NEGATIVE_INFINITY, rr.jamEv, "${Combos.name(h)} level 3 is call / fold only")
            assertTrue(rr.best != FlopAction.RAISE)
            assertTrue(rr.value >= -rr.invested - 1e-9)
        }
    }

    @Test fun geometryNeverExceedsStack() {
        for (stack in listOf(3.0, 8.0, 20.0, 60.0, 220.0)) {
            val f = fixture("Th8d3c", stack = stack)
            val a = FlopTest.flop.analyze(f.spot, nl50)
            assertTrue(a.betSizeBb!! <= stack + 1e-9)
            for (size in a.sizeCandidates) assertTrue(size * f.spot.potBb <= stack + 1e-9, "candidate $size at stack $stack")
            for (x in listOf(1.0, 2.5, 6.0, 30.0)) assertTrue(f.m.raiseTo(x, 5.5 + x) <= stack + 1e-9)
            FlopInvariants.all(a)
        }
    }

    @Test fun defenseVsRaisesToOwnBet() {
        val bd = board("Ks9d4c")
        val h = range(CO_OPEN, bd); val v = range(BB_DEFEND, bd)
        fun defense(valueShare: Double): Pair<Double, Double> {
            val ctx = nl50.copy(locks = FlopNodeLocks.NONE.with(FlopVillainNode.VS_BET, FlopNodeLock(raise = 0.12, valueShare = valueShare)))
            val spot = FlopSpot(bd, h, v, true, true, 5.5, 97.5)
            val a = FlopTest.flop.analyze(spot, ctx)
            val m = FlopMathEngine(spot, ctx, FlopEquityMatrix(bd, h, v))
            val bet = a.betSizeBb!!
            val resp = m.villainModel.respondToBet(m.villainFirstAction.fold, 5.5, bet)
            var t = 0.0; var d = 0.0
            var mdf = 0.0
            for (k in 0 until Combos.COUNT) {
                val w = h[k] * a.frequency(FlopAction.BET, k)
                if (w <= 0f) continue
                val rr = m.reraiseResponse(k, 5.5, bet, 0.0, 0.0, resp, 1)
                val potBeforeRaise = 5.5 + 2 * bet
                mdf = potBeforeRaise / (potBeforeRaise + (rr.reraiseTo - bet))
                t += w; if (rr.best != FlopAction.FOLD) d += w
                if (m.outs[k] in 1..4 && m.strength.category[k] == 0) assertTrue(!rr.stacksOff)
            }
            return Pair(d / t, mdf)
        }
        val (balanced, mdf) = defense(0.5)
        val (valueHeavy, _) = defense(0.95)
        assertTrue(balanced >= mdf - 0.15, "defense vs a balanced raise $balanced vs MDF $mdf")
        assertTrue(valueHeavy < balanced, "defends less vs a value-heavy raise: $valueHeavy vs $balanced")
    }

    @Test fun aggressivePolarDonksOnDynamicBoards() {
        for (b in listOf("Jh9h8c", "7d6d5c", "Th9h6h")) {
            val bd = board(b)
            val h = range(CO_OPEN, bd); val v = range(BB_DEFEND, bd)
            val spot = FlopSpot(bd, h, v, heroInPosition = true, heroIsAggressor = true, potBb = 5.5, effectiveStackBb = 97.5, facingBetBb = 5.5)
            val polar = FlopTest.flop.analyze(spot, nl50.copy(locks = FlopNodeLocks.donkFrequency(0.25, valueShare = 0.85)))
            val weak = FlopTest.flop.analyze(spot, nl50.copy(locks = FlopNodeLocks.donkFrequency(0.25, valueShare = 0.30)))
            assertEquals(DonkType.STRENGTH, polar.donkRead!!.type)
            val m = FlopMathEngine(spot, nl50, FlopEquityMatrix(bd, h, v))
            for (k in 0 until Combos.COUNT) if (h[k] > 0f && m.isNut(k)) assertEquals(0f, polar.frequency(FlopAction.FOLD, k), "$b ${Combos.name(k)} folds the nuts")
            assertTrue(polar.frequency(FlopAction.FOLD) >= weak.frequency(FlopAction.FOLD) - 1e-9, "$b folds less vs a polar donk")
            FlopInvariants.all(polar); FlopInvariants.all(weak)
        }
    }
}
