package com.solverin.engine

import com.solverin.engine.FlopBoards.BB_DEFEND
import com.solverin.engine.FlopBoards.CO_OPEN
import com.solverin.engine.FlopBoards.range
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.*
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Extreme sweep: canonical texture boards × SPR 0.5..40 × 3 node types × hostile node-locked archetypes
 * (station / maniac / over-folder / nit) and stakes, every solve checked with [FlopInvariants].
 */
class FlopDeepStressTest {
    private val engine = FlopTest.flop
    private val profiles = listOf(FlopNodeLocks.NONE, FlopBoards.STATION, FlopBoards.MANIAC, FlopBoards.OVERFOLDER, FlopBoards.NIT)
    private val sprs = listOf(0.5, 1.0, 2.0, 4.0, 12.0, 40.0)

    @Test fun texturesSprsArchetypes() {
        val boards = FlopBoards.textureBoards(perBucket = 2)
        var solves = 0
        for (b in boards) {
            val pfr = range(CO_OPEN, b); val def = range(BB_DEFEND, b)
            for (spr in sprs) {
                val pot = 6.0; val stack = spr * pot
                val sessions = listOf(
                    engine.session(FlopSpot(b, pfr, def, heroInPosition = true, heroIsAggressor = true, potBb = pot, effectiveStackBb = stack)),
                    engine.session(FlopSpot(b, def, pfr, heroInPosition = false, heroIsAggressor = false, potBb = pot, effectiveStackBb = stack)),
                    engine.session(FlopSpot(b, def, pfr, false, false, pot, stack, facingBetBb = minOf(stack, 0.66 * pot))),
                )
                for (s in sessions) for (l in profiles) {
                    FlopInvariants.all(s.analyze(FlopContext(Stake.NL50, locks = l)))
                    solves++
                }
            }
        }
        assertTrue(solves >= 1500, "solves $solves")
    }

    @Test fun stakesAndDonkFacingNodes() {
        val boards = FlopBoards.textureBoards(perBucket = 1)
        for (b in boards) {
            val pfr = range(CO_OPEN, b); val def = range(BB_DEFEND, b)
            for (spr in listOf(0.5, 4.0, 40.0)) {
                val pot = 6.0; val stack = spr * pot
                val donk = engine.session(FlopSpot(b, pfr, def, true, true, pot, stack, facingBetBb = minOf(stack, pot)))
                for (st in listOf(Stake.NL2, Stake.NL200)) for (l in profiles) FlopInvariants.all(donk.analyze(FlopContext(st, locks = l)))
            }
        }
    }

    @Test fun maniacsAreCalledDownWiderThanNits() {
        for (b in FlopBoards.textureBoards(perBucket = 1).take(12)) {
            val pfr = range(CO_OPEN, b); val def = range(BB_DEFEND, b)
            val s = engine.session(FlopSpot(b, def, pfr, false, false, 6.0, 72.0, facingBetBb = 4.0))
            val vsManiac = s.analyze(FlopContext(Stake.NL50, locks = FlopBoards.MANIAC))
            val vsNit = s.analyze(FlopContext(Stake.NL50, locks = FlopBoards.NIT))
            assertTrue(vsManiac.frequency(FlopAction.FOLD) <= vsNit.frequency(FlopAction.FOLD) + 1e-9,
                "${FlopIso.name(FlopIso.id(b))} folds ${vsManiac.frequency(FlopAction.FOLD)} vs maniac > ${vsNit.frequency(FlopAction.FOLD)} vs nit")
        }
    }
}
