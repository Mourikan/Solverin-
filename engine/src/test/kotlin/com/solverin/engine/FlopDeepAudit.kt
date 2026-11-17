package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.*
import com.solverin.engine.range.RangeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deep postflop audit: canonical textures (suits × connectivity) × SPR 1..40 × node types × node-locked
 * archetypes, every solve checked with [FlopInvariants] (frequency sums, exact EV identities, stack bounds,
 * min-bet no-fold rule, max-EV consistency), plus targeted calibration checks.
 */
class FlopDeepAudit {
    private val engine = FlopTest.flop
    private val nl50 = FlopContext(Stake.NL50)

    private fun board(s: String) = s.chunked(2).map { Card.parse(it) }.toIntArray()
    private fun range(notation: String, b: IntArray) = Combos.fromClassWeights(RangeParser.parse(notation), Combos.mask(b))

    private val pfr = "22+,A2s+,A8o+,K7s+,KTo+,Q9s+,QTo+,J9s+,JTo,T8s+,97s+,86s+,76s,65s"
    private val caller = "22-JJ,A9s-AQs,KTs-KQs,QTs+,J9s+,T8s+,97s+,86s+,75s+,65s,54s,AJo-AQo,KQo"

    private val station = FlopNodeLocks.NONE
        .with(FlopVillainNode.VS_BET, FlopNodeLock(fold = 0.15, raise = 0.05))
        .with(FlopVillainNode.VS_DONK, FlopNodeLock(fold = 0.15, raise = 0.05))
        .with(FlopVillainNode.VS_RAISE, FlopNodeLock(fold = 0.25, raise = 0.05))
        .with(FlopVillainNode.VS_JAM, FlopNodeLock(fold = 0.3))
        .with(FlopVillainNode.CBET, FlopNodeLock(frequency = 0.35, valueShare = 0.8))
        .with(FlopVillainNode.STAB, FlopNodeLock(frequency = 0.30, valueShare = 0.8))
        .with(FlopVillainNode.DONK, FlopNodeLock(frequency = 0.05, valueShare = 0.8))
    private val nit = FlopNodeLocks.NONE
        .with(FlopVillainNode.VS_BET, FlopNodeLock(fold = 0.70, raise = 0.04))
        .with(FlopVillainNode.VS_DONK, FlopNodeLock(fold = 0.65, raise = 0.04))
        .with(FlopVillainNode.VS_RAISE, FlopNodeLock(fold = 0.75, raise = 0.06))
        .with(FlopVillainNode.VS_JAM, FlopNodeLock(fold = 0.8))
        .with(FlopVillainNode.CBET, FlopNodeLock(frequency = 0.45, valueShare = 0.9))
        .with(FlopVillainNode.STAB, FlopNodeLock(frequency = 0.25, valueShare = 0.9))
        .with(FlopVillainNode.DONK, FlopNodeLock(frequency = 0.03, valueShare = 0.95))
    private val profiles = listOf(FlopNodeLocks.NONE, station, nit)

    /** One representative per (suit texture × connectivity) bucket and pairing, deterministic. */
    private fun textureBoards(perBucket: Int): List<IntArray> {
        val buckets = HashMap<Triple<SuitTexture, Connectivity, Pairing>, MutableList<Int>>()
        for (id in 0 until FlopIso.COUNT) {
            val t = Textures.of(id)
            buckets.getOrPut(Triple(t.suits, t.connectivity, t.pairing)) { ArrayList() } += id
        }
        return buckets.toSortedMap(compareBy({ it.first }, { it.second }, { it.third })).values.flatMap { ids ->
            val step = maxOf(1, ids.size / perBucket)
            ids.filterIndexed { i, _ -> i % step == 0 }.take(perBucket).map { FlopIso.representative(it) }
        }
    }

    @Test fun textureSprProfileSweep() {
        val boards = textureBoards(perBucket = 3)
        assertTrue(boards.size >= 30, "texture boards ${boards.size}")
        val sprs = listOf(1.0, 4.0, 12.0, 40.0)
        var solves = 0
        for (b in boards) {
            val h = range(pfr, b); val v = range(caller, b)
            for (spr in sprs) {
                val pot = 6.0; val stack = spr * pot
                val sessions = listOf(
                    engine.session(FlopSpot(b, h, v, heroInPosition = true, heroIsAggressor = true, potBb = pot, effectiveStackBb = stack)),
                    engine.session(FlopSpot(b, v, h, heroInPosition = false, heroIsAggressor = false, potBb = pot, effectiveStackBb = stack)),
                    engine.session(FlopSpot(b, v, h, false, false, pot, stack, facingBetBb = minOf(stack, 0.5 * pot))),
                )
                for (s in sessions) for (l in profiles) {
                    val a = s.analyze(nl50.copy(locks = l))
                    FlopInvariants.all(a)
                    solves++
                }
            }
        }
        assertTrue(solves >= 1000, "solves $solves")
    }

    @Test fun microBetsAcrossTextures() {
        for (b in textureBoards(perBucket = 1)) {
            val h = range(caller, b); val v = range(pfr, b)
            for (stack in listOf(10.0, 90.0)) {
                val s = engine.session(FlopSpot(b, h, v, false, false, potBb = 10.0, effectiveStackBb = stack, facingBetBb = 1.0))
                for (l in profiles) {
                    val a = s.analyze(nl50.copy(locks = l))
                    assertTrue(a.minBet)
                    assertTrue(a.frequency(FlopAction.FOLD) <= 1 - a.mdf!! + 1e-9, "${FlopIso.name(FlopIso.id(b))} folds ${a.frequency(FlopAction.FOLD)} to 1bb")
                    FlopInvariants.all(a)
                }
            }
        }
    }

    @Test fun microBetsAreNotFoldedToByVillain() {
        val b = board("Qd7c2h")
        val m = FlopMathEngine(FlopSpot(b, range(pfr, b), range(caller, b), true, true, 10.0, 90.0), nl50,
            FlopEquityMatrix(b, range(pfr, b), range(caller, b)))
        val cont = m.villainModel.continueShare(10.0, 1.0)
        assertTrue(cont >= 1 - nl50.profile.microBetMaxFold - 1e-12, "villain continue vs 1bb $cont")
        // Raises over a min-bet are pot-weighted, not 3 × 1bb.
        val r = m.raiseTo(1.0, 11.0)
        assertEquals(1.0 + nl50.policy.raisePotFraction * (11.0 + 1.0), r, 1e-12)
        assertEquals(3.0 * 5.0, m.raiseTo(5.0, 15.0), 1e-12)
    }

    @Test fun checkRaiseCapOnConnectedBoards() {
        for (bs in listOf("6h5h4c", "9h8h7c", "8c7d6s", "Tc9c8d", "7s6d5d")) {
            val b = board(bs)
            for (l in listOf(FlopNodeLocks.NONE, nit)) {
                val a = engine.analyze(FlopSpot(b, range(caller, b), range(pfr, b), false, false, 5.5, 97.5, facingBetBb = 2.5),
                    nl50.copy(locks = l))
                val connected = a.texture.connectivity == Connectivity.HIGH || a.texture.volatility >= nl50.policy.connectedVolatility
                assertTrue(connected, "$bs counts as connected")
                assertTrue(a.frequency(FlopAction.RAISE) <= nl50.policy.checkRaiseCapConnected + 0.005,
                    "$bs check-raise ${a.frequency(FlopAction.RAISE)}")
                FlopInvariants.all(a)
            }
        }
        val dry = engine.analyze(FlopSpot(board("Kd7c2s"), range(caller, board("Kd7c2s")), range(pfr, board("Kd7c2s")),
            false, false, 5.5, 97.5, facingBetBb = 2.5), nl50)
        assertTrue(dry.frequency(FlopAction.RAISE) <= nl50.policy.checkRaiseCap + 0.005)
    }

    @Test fun donkDefenseNearMdf() {
        val utg = "22+,A2s+,K9s+,Q9s+,J9s+,T9s,98s,87s,76s,ATo+,KJo+,QJo"
        val bb = "22-JJ,A2s-AQs,K9s-KQs,Q9s+,J9s+,T8s+,97s+,86s+,75s+,64s+,54s,AJo-AQo,KJo-KQo,QJo"
        val b = board("6h5h4c")
        val a = engine.analyze(FlopSpot(b, range(utg, b), range(bb, b), true, true, 5.5, 97.5, facingBetBb = 4.0),
            nl50.copy(locks = FlopNodeLocks.donkFrequency(0.2, valueShare = 0.30)))
        assertEquals(DonkType.WEAKNESS, a.donkRead!!.type)
        val fold = a.frequency(FlopAction.FOLD)
        assertTrue(fold <= 0.50, "overfold vs weak donk $fold")
        assertTrue(fold in (1 - a.mdf!! - 0.03)..(1 - a.mdf!! + 0.06), "fold $fold vs 1 - MDF ${1 - a.mdf!!}")
        // IP overcards / backdoors realize better than plain air facing the donk.
        val ak = Combos.parse("AdKc"); val ah = Combos.parse("AhKh")
        val callAk = a.terms(FlopAction.CALL, ak)!!; val callAhKh = a.terms(FlopAction.CALL, ah)!!
        assertTrue(callAk.realization > 0f && callAhKh.equity > callAk.equity)
        FlopInvariants.all(a)
    }
}
