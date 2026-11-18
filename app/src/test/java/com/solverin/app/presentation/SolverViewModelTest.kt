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
        vm.selectHeroAction("CALL"); advanceUntilIdle()
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
        vm.selectHeroAction("CHECK"); advanceUntilIdle()
        vm.villain("Bet 50%"); advanceUntilIdle()
        s = vm.state.value
        assertOracle(s)
        assertTrue(s.options.any { it.key == "FOLD" } && s.options.any { it.key == "CALL" })
        vm.selectHeroAction("CALL"); advanceUntilIdle()
        assertEquals(StreetFlowPhase.DEAL_BOARD_CARDS, vm.state.value.phase)

        pickCards(vm, "7h")
        s = vm.state.value
        assertEquals(TableStreet.TURN, s.street)
        assertOracle(s)
        vm.selectHeroAction("CHECK"); advanceUntilIdle()
        vm.villain("Check"); advanceUntilIdle()
        assertEquals(StreetFlowPhase.DEAL_BOARD_CARDS, vm.state.value.phase)

        pickCards(vm, "2d")
        s = vm.state.value
        assertEquals(TableStreet.RIVER, s.street)
        assertOracle(s)
        assertEquals(5, s.boardIds.size)
        vm.selectHeroAction("CHECK"); advanceUntilIdle()
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
        vm.selectHeroAction("FOLD"); advanceUntilIdle()
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
        if (heroKey != "RAISE") vm.selectHeroAction("RAISE")
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
        vm.selectHeroAction("RAISE"); advanceUntilIdle()
        s = vm.state.value
        assertEquals("BB", s.toAct)
        // Reraise level offered to BB; reraise → hero faces level 3 and is solved again.
        assertTrue(s.villainOptions.any { it.label.startsWith("Reraise") })
        vm.villain("Reraise 3x"); advanceUntilIdle()
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
        vm.setProfile(VillainProfilePreset.OVERFOLDER); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(Stake.NL2, s.stake)
        assertEquals(VillainProfilePreset.OVERFOLDER, s.profile)
        assertOracle(s)
        assertTrue(before.isNotEmpty())
        assertNull(s.error)
    }
}
