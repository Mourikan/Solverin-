package com.solverin.app.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.math.PipelineBufferPool
import com.solverin.engine.pipeline.HandPipelineEngine
import com.solverin.engine.pipeline.HandScript
import com.solverin.engine.pipeline.PreflopAction
import com.solverin.engine.pipeline.PreflopLine
import com.solverin.engine.pipeline.Seat
import com.solverin.engine.pipeline.Street
import com.solverin.engine.pipeline.StreetKind
import com.solverin.engine.pipeline.StreetScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    private val engine = SHARED

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SolverViewModel(engine, dispatcher)

    private val utgVsBb = PreflopAction(PreflopLine.RFI_CALL, Position.UTG, Position.BB)

    private fun assertWellFormed(s: HandViewState) {
        assertTrue("actions", s.actions.isNotEmpty())
        assertEquals("one Max-EV action", 1, s.actions.count { it.isMaxEv })
        assertTrue("Max-EV first", s.actions.first().isMaxEv)
        val best = s.actions.filter { !it.evBb.isNaN() }.maxOf { it.evBb }
        assertEquals(best, s.maxEvAction!!.evBb, 1e-12)
        assertEquals(1.0, s.actions.sumOf { it.frequency.toDouble() }, 1e-4)
        assertTrue(s.potBb > 0 && s.effectiveStackBb >= 0 && s.heroStackBb >= 0)
    }

    @Test fun preflopIntentEmitsExactEngineEvs() = runTest(dispatcher) {
        val vm = vm()
        vm.configure(Stake.NL100, utgVsBb, Seat.OPENER)
        vm.setHoleCards("AsKh")
        advanceUntilIdle()
        val s = vm.state.value
        assertNull(s.error)
        assertFalse(s.isComputing)
        assertEquals(Street.PREFLOP, s.street)
        assertEquals(listOf("As", "Kh"), s.heroCards)
        assertWellFormed(s)
        val direct = engine.play(HandScript(Stake.NL100, utgVsBb, Seat.OPENER, "AsKh", null)).reports.last()
        for (a in s.actions) if (!a.evBb.isNaN()) assertEquals(direct.heroEv.getValue(a.action), a.evBb, 1e-9)
        assertEquals("RAISE", s.maxEvAction!!.action)
    }

    @Test fun fullHandStreetByStreetMatchesThePipeline() = runTest(dispatcher) {
        val vm = vm()
        val emitted = mutableListOf<HandViewState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.toList(emitted) }
        vm.configure(Stake.NL100, utgVsBb, Seat.OPENER)
        vm.setHoleCards("AsQd")
        vm.setBoard("KsTs6h")
        advanceUntilIdle()
        assertEquals(Street.FLOP, vm.state.value.street)
        vm.registerAction(Street.FLOP, StreetScript(StreetKind.BET_CALL, Seat.OPENER))
        vm.setBoard("KsTs6h", "2d")
        advanceUntilIdle()
        assertEquals(Street.TURN, vm.state.value.street)
        vm.registerAction(Street.TURN, StreetScript(StreetKind.BET_CALL, Seat.OPENER))
        vm.setBoard("KsTs6h", "2d", "3s")
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(Street.RIVER, s.street)
        assertEquals(listOf("Ks", "Ts", "6h", "2d", "3s"), s.board)
        assertWellFormed(s)
        assertEquals(4, s.history.size)
        val direct = engine.play(HandScript(Stake.NL100, utgVsBb, Seat.OPENER, "AsQd", "KsTs6h", "2d", "3s",
            StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER))).reports.last()
        assertEquals(direct.potBb, s.potBb, 1e-9)
        for (a in s.actions) if (!a.evBb.isNaN()) assertEquals(direct.heroEv.getValue(a.action), a.evBb, 1e-9)
        assertEquals("TRIPLE_BARREL", s.maxEvAction!!.action)
        assertTrue("computing flag raised", emitted.any { it.isComputing })
        assertFalse(emitted.last().isComputing)
    }

    @Test fun riverFacingBetAndReset() = runTest(dispatcher) {
        val vm = vm()
        vm.configure(Stake.NL50, PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.BB), Seat.RESPONDER)
        vm.setHoleCards("Qc9d")
        vm.setBoard("Kh9h4c")
        vm.registerAction(Street.FLOP, StreetScript(StreetKind.BET_CALL, Seat.OPENER))
        vm.setBoard("Kh9h4c", "2d")
        vm.registerAction(Street.TURN, StreetScript(StreetKind.BET_CALL, Seat.OPENER))
        vm.setBoard("Kh9h4c", "2d", "7s")
        vm.registerRiverDecision(0.75)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(Street.RIVER, s.street)
        assertWellFormed(s)
        assertNotNull(s.mdf)
        assertTrue(s.actions.map { it.action }.containsAll(listOf("CALL", "FOLD")))
        vm.reset()
        assertTrue(vm.state.value.actions.isEmpty())
        assertEquals(Stake.NL50, vm.state.value.stake)
    }

    @Test fun invalidIntentsReportErrorsWithoutCrashing() = runTest(dispatcher) {
        val vm = vm()
        vm.configure(Stake.NL50, utgVsBb, Seat.OPENER)
        vm.setHoleCards("AsAs")
        assertNotNull(vm.state.value.error)
        vm.setHoleCards("AsKh")
        vm.setBoard("AsTd2c")
        advanceUntilIdle()
        assertNotNull("board card duplicates a hole card", vm.state.value.error)
        vm.setBoard("Xx7c2d")
        assertNotNull(vm.state.value.error)
        vm.registerAction(Street.RIVER, StreetScript(StreetKind.CHECK_CHECK))
        assertNotNull(vm.state.value.error)
    }

    @Test fun latestIntentWins() = runTest(dispatcher) {
        val vm = vm()
        vm.configure(Stake.NL50, utgVsBb, Seat.OPENER)
        vm.setHoleCards("7c2d")
        vm.setHoleCards("AhAd")
        advanceUntilIdle()
        assertEquals(listOf("Ah", "Ad"), vm.state.value.heroCards)
        assertEquals("RAISE", vm.state.value.maxEvAction!!.action)
    }

    @Test fun onClearedReleasesPooledBuffers() = runTest(dispatcher) {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = vm() as T
        }
        val vm = ViewModelProvider(store, factory)[SolverViewModel::class.java]
        vm.configure(Stake.NL50, utgVsBb, Seat.OPENER)
        vm.setHoleCards("AsQd")
        vm.setBoard("KsTs6h")
        vm.registerAction(Street.FLOP, StreetScript(StreetKind.BET_CALL, Seat.OPENER))
        vm.setBoard("KsTs6h", "2d")
        advanceUntilIdle()
        assertTrue("pool holds buffers after a turn solve", PipelineBufferPool.retainedAll() > 0)
        store.clear()
        assertEquals(0, PipelineBufferPool.retainedAll())
    }

    private companion object {
        val SHARED = HandPipelineEngine()
    }
}
