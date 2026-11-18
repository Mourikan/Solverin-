package com.solverin.app.presentation

import com.solverin.engine.core.Card
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SolverViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SolverViewModel(computeDispatcher = dispatcher)

    private fun TestScope.pickCards(vm: SolverViewModel, vararg cards: String) {
        for (c in cards) {
            val id = Card.parse(c)
            vm.pickRank(Card.rank(id)); vm.pickSuit(Card.suit(id))
            advanceUntilIdle()
        }
    }

    private fun SolverViewModel.villain(prefix: String) {
        val o = state.value.villainOptions.firstOrNull { it.label.startsWith(prefix) }
            ?: error("No option '$prefix' in ${state.value.villainOptions.map { it.label }} (toAct ${state.value.toAct})")
        villainAct(o)
    }

    private fun assertOracle(s: HandViewState) {
        assertTrue("oracle options at ${s.street}/${s.phase}", s.options.isNotEmpty())
        assertTrue("finite EV", s.options.all { it.evBb.isFinite() })
        assertTrue("Nash frequency in [0,1]", s.options.all { it.frequency in 0f..1f })
        assertEquals("exactly one Max-EV option", 1, s.options.count { it.isMaxEv })
        val best = s.maxEvAction!!
        assertTrue(s.options.all { it.evBb <= best.evBb + 1e-9 })
    }

    @Test
    fun fullFlowWithHeroAutoPlayMultiRaisesAndStrictBoardGating() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(StreetFlowPhase.PICK_HERO_CARDS, vm.state.value.phase)
        pickCards(vm, "Ah")
        assertEquals(StreetFlowPhase.PICK_HERO_CARDS, vm.state.value.phase)
        pickCards(vm, "Kh")
        // Auto-advance after the second hole card.
        assertEquals(StreetFlowPhase.PICK_HERO_POSITION, vm.state.value.phase)
        assertEquals(listOf("Ah", "Kh"), vm.state.value.heroCards)

        vm.setHeroPosition(Position.CO); advanceUntilIdle()
        var s = vm.state.value
        assertEquals(StreetFlowPhase.VILLAIN_ACTION_STEPPER, s.phase)
        assertEquals("UTG", s.toAct)
        assertEquals(1.5, s.potBb, 1e-9)
        assertOracle(s) // preview of hero's RFI node

        vm.villain("Fold"); advanceUntilIdle()
        assertEquals("MP", vm.state.value.toAct)
        // Board input is blocked while betting is open.
        pickCards(vm, "2c")
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.boardIds.isEmpty())

        vm.villain("Fold"); advanceUntilIdle()
        // Hero (CO) auto-commits the Max-EV RFI and focus moves to BTN.
        s = vm.state.value
        assertOracle(s)
        assertEquals("BTN", s.toAct)
        assertEquals(s.maxEvAction!!.key, s.heroCommittedKey)
        assertTrue(s.heroOverridable)
        assertEquals("RAISE", s.heroCommittedKey)
        val open = s.seats.first { it.isHero }.investBb
        assertEquals(2.5, open, 1e-9)

        // BTN 3-bets, SB folds, BB folds → hero faces a 3-bet (multi-raise, VS_3BET node).
        vm.villain("3-Bet IP"); advanceUntilIdle()
        assertFalse(vm.state.value.heroOverridable) // hero open implicitly confirmed
        vm.villain("Fold"); advanceUntilIdle()
        vm.villain("Fold"); advanceUntilIdle()
        s = vm.state.value
        assertOracle(s)
        assertTrue(s.nodeLabel, s.nodeLabel.contains("VS 3BET"))
        // Override: hero calls the 3-bet instead of the auto action.
        vm.overrideHeroAction("CALL"); advanceUntilIdle()
        s = vm.state.value
        assertEquals("CALL", s.heroCommittedKey)
        assertEquals(StreetFlowPhase.DEAL_BOARD_CARDS, s.phase)
        assertEquals(TableStreet.PREFLOP, s.street)
        assertEquals(7.5 + 7.5 + 1.5, s.potBb, 1e-9)

        // Flop: exactly 3 cards, then the stepper opens; the turn card is rejected until the flop closes.
        pickCards(vm, "Kd", "8c")
        assertEquals(StreetFlowPhase.DEAL_BOARD_CARDS, vm.state.value.phase)
        assertEquals(1, vm.state.value.cardsNeeded)
        pickCards(vm, "4s")
        s = vm.state.value
        assertEquals(TableStreet.FLOP, s.street)
        // Hero (CO) is OOP vs BTN → hero acts first, auto-commits, then BTN.
        assertOracle(s)
        assertEquals("BTN", s.toAct)
        assertNotNull(s.heroCommittedKey)
        pickCards(vm, "2c")
        assertEquals(3, vm.state.value.boardIds.size)
        assertNotNull(vm.state.value.error)

        // Force a check from hero, BTN bets 50%, hero's response node is solved and auto-committed.
        vm.overrideHeroAction("CHECK"); advanceUntilIdle()
        vm.villain("Bet 50%"); advanceUntilIdle()
        s = vm.state.value
        assertOracle(s)
        assertTrue(s.options.any { it.key == "FOLD" } && s.options.any { it.key == "CALL" })
        vm.overrideHeroAction("CALL"); advanceUntilIdle()
        assertEquals(StreetFlowPhase.DEAL_BOARD_CARDS, vm.state.value.phase)

        pickCards(vm, "7h")
        s = vm.state.value
        assertEquals(TableStreet.TURN, s.street)
        assertOracle(s)
        vm.overrideHeroAction("CHECK"); advanceUntilIdle()
        vm.villain("Check"); advanceUntilIdle()
        assertEquals(StreetFlowPhase.DEAL_BOARD_CARDS, vm.state.value.phase)

        pickCards(vm, "2d")
        s = vm.state.value
        assertEquals(TableStreet.RIVER, s.street)
        assertOracle(s)
        assertEquals(5, s.boardIds.size)
        vm.overrideHeroAction("CHECK"); advanceUntilIdle()
        vm.villain("Check"); advanceUntilIdle()
        s = vm.state.value
        assertEquals(StreetFlowPhase.HAND_COMPLETE, s.phase)
        assertEquals(TableStreet.SHOWDOWN, s.street)
        assertOracle(s)
    }

    @Test
    fun villainMultiRaiseLoopBeforeHeroAndColdFourBet() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "As", "Ad")
        vm.setHeroPosition(Position.BB); advanceUntilIdle()
        vm.villain("Open 2.5"); advanceUntilIdle()   // UTG open
        vm.villain("3-Bet IP"); advanceUntilIdle()   // MP 3-bet 7.5
        vm.villain("4-Bet"); advanceUntilIdle()      // CO 4-bet
        var s = vm.state.value
        assertEquals("BTN", s.toAct)
        vm.villain("Fold"); advanceUntilIdle()
        vm.villain("Fold"); advanceUntilIdle()       // SB
        s = vm.state.value
        // Hero BB facing open + 3-bet + 4-bet: oracle present, AA never folds.
        assertOracle(s)
        assertTrue(s.maxEvAction!!.kind != ActKind.FOLD)
        assertEquals("UTG", s.toAct)
        // Betting continues until every live player matched the max bet.
        vm.villain("Fold"); advanceUntilIdle()       // UTG
        assertEquals("MP", vm.state.value.toAct)
        vm.villain("Fold"); advanceUntilIdle()       // MP
        s = vm.state.value
        assertTrue(s.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER || s.phase == StreetFlowPhase.DEAL_BOARD_CARDS || s.phase == StreetFlowPhase.HAND_COMPLETE)
        assertOracle(s)
    }

    @Test
    fun undoRestoresPreviousInputAndHeroOverrideOnlyBeforeNextInput() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "Qs", "Qd")
        vm.setHeroPosition(Position.UTG); advanceUntilIdle()
        var s = vm.state.value
        // Hero is first to act: auto-committed immediately.
        assertEquals("MP", s.toAct)
        assertNotNull(s.heroCommittedKey)
        assertOracle(s)
        vm.villain("Fold"); advanceUntilIdle()
        assertEquals("CO", vm.state.value.toAct)
        vm.overrideHeroAction("FOLD"); advanceUntilIdle()
        assertNotNull(vm.state.value.error) // no longer overridable
        vm.undo(); advanceUntilIdle()
        s = vm.state.value
        assertEquals("MP", s.toAct)
        assertTrue(s.canUndo)
        vm.undo(); advanceUntilIdle()
        s = vm.state.value
        assertEquals(StreetFlowPhase.PICK_HERO_POSITION, s.phase)
        vm.undo(); advanceUntilIdle()
        assertEquals(1, vm.state.value.heroCardIds.size)
        vm.resetHand(); advanceUntilIdle()
        s = vm.state.value
        assertEquals(StreetFlowPhase.PICK_HERO_CARDS, s.phase)
        assertFalse(s.canUndo)
        assertTrue(s.options.isEmpty())
    }

    @Test
    fun limpersRouteThroughIsoRaiseInterceptor() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "Ac", "Kc")
        vm.setHeroPosition(Position.BTN); advanceUntilIdle()
        vm.villain("Limp"); advanceUntilIdle()   // UTG limp
        vm.villain("Fold"); advanceUntilIdle()
        vm.villain("Limp"); advanceUntilIdle()   // CO limp
        val s = vm.state.value
        assertOracle(s)
        assertTrue(s.nodeLabel, s.nodeLabel.startsWith("ISO"))
        assertEquals("ISO_RAISE", s.heroCommittedKey)
        assertEquals("SB", s.toAct)
        assertEquals(StreetFlowStateMachine.round1(3.5 + 2), s.seats.first { it.isHero }.investBb, 1e-9)
    }

    @Test
    fun postflopDonkBetMinBetAndRaiseLevels() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "9h", "9c")
        vm.setHeroPosition(Position.BTN); advanceUntilIdle()
        repeat(3) { vm.villain("Fold"); advanceUntilIdle() } // UTG, MP, CO
        val heroKey = vm.state.value.heroCommittedKey
        assertNotNull(heroKey)
        if (heroKey != "RAISE") vm.overrideHeroAction("RAISE")
        advanceUntilIdle()
        vm.villain("Fold"); advanceUntilIdle()   // SB
        vm.villain("Call"); advanceUntilIdle()   // BB
        assertEquals(StreetFlowPhase.DEAL_BOARD_CARDS, vm.state.value.phase)
        pickCards(vm, "Ts", "6d", "2h")
        var s = vm.state.value
        assertEquals("BB", s.toAct)
        // BB is the non-aggressor acting first: donk presets + 1bb min-bet are offered.
        assertTrue(s.villainOptions.any { it.label.startsWith("Donk 33%") })
        assertTrue(s.villainOptions.any { it.label == "Min 1bb" && it.toAmount == 1.0 })
        vm.villain("Min 1bb"); advanceUntilIdle()
        s = vm.state.value
        assertOracle(s)
        assertTrue(s.options.any { it.key == "FOLD" })
        vm.overrideHeroAction("RAISE"); advanceUntilIdle()
        s = vm.state.value
        assertEquals("BB", s.toAct)
        // Reraise level offered to BB; reraise → hero faces level 3 and is solved again.
        assertTrue(s.villainOptions.any { it.label.startsWith("3-Bet") })
        vm.villain("3-Bet 3x"); advanceUntilIdle()
        s = vm.state.value
        // Hero faces the 3rd raise level: solved and auto-committed (call closes the flop, fold ends the hand).
        assertOracle(s)
        assertTrue(s.options.any { it.key == "CALL" } && s.options.any { it.key == "FOLD" })
        assertEquals(s.maxEvAction!!.key, s.heroCommittedKey)
        assertTrue(s.heroOverridable)
        assertTrue(s.phase == StreetFlowPhase.DEAL_BOARD_CARDS || s.phase == StreetFlowPhase.HAND_COMPLETE || s.toAct == "BB")
    }

    @Test
    fun profileAndStakeChangesReSolveTheNode() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "Jh", "Th")
        vm.setHeroPosition(Position.BTN); advanceUntilIdle()
        val before = vm.state.value.options.map { it.evBb }
        vm.setStake(Stake.NL2); advanceUntilIdle()
        vm.setProfile(VillainProfilePreset.NIT); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(Stake.NL2, s.stake)
        assertEquals(VillainProfilePreset.NIT, s.profile)
        assertOracle(s)
        assertTrue(before.isNotEmpty())
        assertNull(s.error)
    }

    @Test
    fun heroMaxEvIsAssumedAndManualDeviationRecomputesState() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "Ks", "Qs")
        vm.setHeroPosition(Position.CO); advanceUntilIdle()
        vm.villain("Fold"); advanceUntilIdle()
        vm.villain("Fold"); advanceUntilIdle()
        var s = vm.state.value
        val best = s.maxEvAction!!
        assertEquals(best.key, s.heroCommittedKey)
        assertFalse(s.manualDeviation)
        assertTrue(s.heroOverridable)
        val potAuto = s.potBb
        // Deviation: hero actually folded.
        vm.overrideHeroAction("FOLD"); advanceUntilIdle()
        s = vm.state.value
        assertEquals("FOLD", s.heroCommittedKey)
        assertTrue(s.manualDeviation)
        assertTrue(s.heroFoldPending)
        assertTrue(s.committedAction!!.deltaEvBb <= 0.0)
        assertTrue(s.potBb < potAuto)
        // Anti-lock: a further villain input switches hero back to the best non-fold action.
        assertTrue(s.villainOptions.isNotEmpty())
        vm.villain("Fold"); advanceUntilIdle()   // BTN
        s = vm.state.value
        assertEquals("SB", s.toAct)
        assertTrue(s.seats.first { it.isHero }.inHand)
        assertFalse(s.heroOverridable)
        // Explicit continue path.
        vm.undo(); advanceUntilIdle()
        assertEquals("BTN", vm.state.value.toAct)
    }

    @Test
    fun maxEvFoldStaysRecoverableWithContinueInHand() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "7d", "2c")
        vm.setHeroPosition(Position.UTG); advanceUntilIdle()
        var s = vm.state.value
        assertEquals("FOLD", s.heroCommittedKey)
        assertTrue(s.heroFoldPending)
        assertEquals(StreetFlowPhase.HAND_COMPLETE, s.phase)
        assertTrue(vm.continueInHand())
        advanceUntilIdle()
        s = vm.state.value
        assertFalse(s.heroFoldPending)
        assertEquals(StreetFlowPhase.VILLAIN_ACTION_STEPPER, s.phase)
        assertEquals("MP", s.toAct)
        assertTrue(s.manualDeviation)
    }

    @Test
    fun stakeAndStackChangesUpdateEvSprAndSpanishExplanation() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "Ad", "Qd")
        vm.setHeroPosition(Position.BTN); advanceUntilIdle()
        repeat(3) { vm.villain("Fold"); advanceUntilIdle() }
        vm.overrideHeroAction("RAISE"); advanceUntilIdle()
        vm.villain("Fold"); advanceUntilIdle()
        vm.villain("Call"); advanceUntilIdle()
        pickCards(vm, "Qh", "7c", "3s")
        var s = vm.state.value
        assertEquals(TableStreet.FLOP, s.street)
        assertOracle(s)
        val e0 = s.explanation!!
        assertEquals(4, e0.points.size)
        assertTrue(e0.points[0].startsWith("1. Veredicto EV"))
        assertTrue(e0.points.any { it.contains("SPR") })
        assertTrue(e0.points.last().contains("Rake NL50"))
        val spr100 = s.spr
        val ev100 = s.options.associate { it.key to it.evBb }

        vm.setStake(Stake.NL2); advanceUntilIdle()
        s = vm.state.value
        assertOracle(s)
        assertEquals(Stake.NL2, s.stake)
        assertTrue(s.explanation!!.points.last().contains("Rake NL2"))
        assertTrue(s.options.any { o -> ev100[o.key]?.let { kotlin.math.abs(it - o.evBb) > 1e-6 } ?: true })

        vm.setEffectiveStack(20.0); advanceUntilIdle()
        s = vm.state.value
        assertOracle(s)
        assertEquals(20.0, s.tableStackBb, 1e-9)
        assertTrue(s.spr < spr100)
        assertTrue(s.effectiveStackBb < 20.0)
        assertTrue(s.explanation!!.points.any { it.contains("SPR ${String.format(java.util.Locale.US, "%.1f", s.spr)}") })

        // Single-seat stack: only the selected seat changes.
        vm.selectSeat(Position.UTG); vm.adjustStack(5.0); advanceUntilIdle()
        s = vm.state.value
        assertEquals("UTG", s.selectedSeat)
        assertEquals(25.0, s.seats.first { it.label == "UTG" }.stackBb, 1e-9)
        assertEquals(20.0, s.tableStackBb, 1e-9)
    }

    @Test
    fun multiRaisesKeepTheStreetOpenUntilEveryoneMatches() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "2s", "3d")
        vm.setHeroPosition(Position.BB); advanceUntilIdle()
        vm.villain("Open 2.5"); advanceUntilIdle()      // UTG
        vm.villain("Call"); advanceUntilIdle()          // MP
        vm.villain("3-Bet IP"); advanceUntilIdle()      // CO 3-bet
        vm.villain("4-Bet"); advanceUntilIdle()         // BTN 4-bet
        vm.villain("Fold"); advanceUntilIdle()          // SB
        var s = vm.state.value
        // Hero BB auto-folds 32o; the street is still open for UTG / MP / CO.
        assertEquals(StreetFlowPhase.HAND_COMPLETE, s.phase)
        assertTrue(s.heroFoldPending)
        vm.continueInHand(); advanceUntilIdle()
        s = vm.state.value
        assertEquals(StreetFlowPhase.VILLAIN_ACTION_STEPPER, s.phase)
        assertEquals("UTG", s.toAct)
        vm.villain("Fold"); advanceUntilIdle()          // UTG
        vm.villain("Fold"); advanceUntilIdle()          // MP
        s = vm.state.value
        assertEquals("CO", s.toAct)
        assertEquals(StreetFlowPhase.VILLAIN_ACTION_STEPPER, s.phase)
        vm.villain("5-Bet"); advanceUntilIdle()         // CO 5-bet re-opens the action
        s = vm.state.value
        assertEquals("BTN", s.toAct)
        vm.villain("Call"); advanceUntilIdle()          // BTN calls
        s = vm.state.value
        // Hero (BB) must respond to the 5-bet before the street can close.
        assertTrue(s.heroCommittedKey != null || s.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER)
        val heroIn = s.seats.first { it.isHero }
        if (heroIn.inHand && !s.heroFoldPending) assertEquals(StreetFlowPhase.DEAL_BOARD_CARDS, s.phase)
    }

    private fun SolverViewModel.assign(prefix: String) {
        val o = state.value.villainOptions.firstOrNull { it.label.startsWith(prefix) }
            ?: error("No option '$prefix' for ${state.value.actingSeat} in ${state.value.villainOptions.map { it.label }}")
        assignSeatAction(o)
    }

    @Test
    fun matrixAssignmentFoldsSkippedSeatsByOmissionAndAdvancesToTheFlop() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "Ah", "Kd")
        vm.setHeroPosition(Position.BTN); advanceUntilIdle()
        // CO opens directly: UTG and MP fold implicitly.
        vm.selectMatrixSeat(Position.CO); advanceUntilIdle()
        var s = vm.state.value
        assertEquals("CO", s.actingSeat)
        assertTrue(s.seats.first { it.label == "CO" }.isMatrixSelected)
        vm.assign("Open 2.5"); advanceUntilIdle()
        s = vm.state.value
        assertFalse(s.seats.first { it.label == "UTG" }.inHand)
        assertFalse(s.seats.first { it.label == "MP" }.inHand)
        assertEquals(listOf("P UTG fold", "P MP fold", "P CO raise 2.5bb"), s.history.take(3))
        // Hero (BTN) Max EV auto-assumed right after the open.
        assertOracle(s)
        assertEquals(s.maxEvAction!!.key, s.heroCommittedKey)
        assertEquals("SB", s.toAct)
        // Hero flats in the real game, then the rest fold by omission.
        vm.overrideHeroAction("CALL"); advanceUntilIdle()
        assertTrue(vm.state.value.manualDeviation || vm.state.value.maxEvAction!!.key == "CALL")
        vm.advancePreflopAssumingRemainingFold(); advanceUntilIdle()
        s = vm.state.value
        assertEquals(StreetFlowPhase.DEAL_BOARD_CARDS, s.phase)
        assertEquals(3, s.cardsNeeded)
        assertEquals(2.5 + 2.5 + 0.5 + 1.0, s.potBb, 1e-9)
        assertEquals(listOf("CO", "BTN"), s.seats.filter { it.inHand }.map { it.label })
        assertEquals(97.5, s.effectiveStackBb, 1e-9)
        // Flop unlocks and the pipeline sees the exact RFI_CALL line CO vs BTN.
        pickCards(vm, "Kh", "8c", "4d")
        s = vm.state.value
        assertEquals(TableStreet.FLOP, s.street)
        assertOracle(s)
        assertTrue(s.nodeLabel, s.nodeLabel.contains("RFI CALL"))
        assertFalse(s.approximate)
    }

    @Test
    fun advanceAutoResolvesHeroOnTheWayAndDeviationReRunsTheImplicitFolds() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "Qs", "Qh")
        vm.setHeroPosition(Position.BB); advanceUntilIdle()
        vm.assign("Open 2.5"); advanceUntilIdle()           // UTG (seat to act, no selection needed)
        vm.advancePreflopAssumingRemainingFold(); advanceUntilIdle()
        var s = vm.state.value
        for (p in listOf("MP", "CO", "BTN", "SB")) assertFalse(p, s.seats.first { it.label == p }.inHand)
        assertNotNull(s.heroCommittedKey)
        assertTrue(s.heroOverridable)
        assertTrue(s.phase == StreetFlowPhase.DEAL_BOARD_CARDS || s.phase == StreetFlowPhase.HAND_COMPLETE)
        // Hero actually just called: the street closes heads-up UTG vs BB.
        vm.overrideHeroAction("CALL"); advanceUntilIdle()
        s = vm.state.value
        assertEquals(StreetFlowPhase.DEAL_BOARD_CARDS, s.phase)
        assertEquals(listOf("UTG", "BB"), s.seats.filter { it.inHand }.map { it.label })
        assertEquals(2.5 + 2.5 + 0.5, s.potBb, 1e-9)
    }

    @Test
    fun matrixMultiRaiseOpenThreeBetFourBetCall() = runTest(dispatcher) {
        val vm = vm()
        pickCards(vm, "Ac", "Ad")
        vm.setHeroPosition(Position.BTN); advanceUntilIdle()
        vm.assign("Open 2.5"); advanceUntilIdle()            // UTG open
        vm.selectMatrixSeat(Position.CO); advanceUntilIdle() // MP folds by omission
        vm.assign("3-Bet IP"); advanceUntilIdle()            // CO 3-bet 7.5
        var s = vm.state.value
        assertOracle(s)
        if (s.heroCommittedKey != "RAISE") { vm.overrideHeroAction("RAISE"); advanceUntilIdle() }
        s = vm.state.value
        val heroTo = s.seats.first { it.isHero }.investBb
        assertTrue(heroTo > 7.5)
        // CO calls the 4-bet; SB, BB and UTG fold by omission.
        vm.selectMatrixSeat(Position.CO); advanceUntilIdle()
        assertEquals("CO", vm.state.value.actingSeat)
        vm.assign("Call"); advanceUntilIdle()
        s = vm.state.value
        assertEquals(StreetFlowPhase.DEAL_BOARD_CARDS, s.phase)
        assertEquals(listOf("CO", "BTN"), s.seats.filter { it.inHand }.map { it.label })
        assertEquals(0.5 + 1.0 + 2.5 + 2 * heroTo, s.potBb, 1e-9)
        assertTrue(s.history.any { it == "P UTG fold" } && s.history.any { it == "P SB fold" } && s.history.any { it == "P BB fold" })
    }
}
