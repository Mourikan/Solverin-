package com.solverin.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solverin.engine.core.Stake
import com.solverin.engine.math.PipelineBufferPool
import com.solverin.engine.pipeline.HandLocks
import com.solverin.engine.pipeline.HandPipelineEngine
import com.solverin.engine.pipeline.HandResult
import com.solverin.engine.pipeline.HandScript
import com.solverin.engine.pipeline.PreflopAction
import com.solverin.engine.pipeline.RiverScript
import com.solverin.engine.pipeline.Seat
import com.solverin.engine.pipeline.Street
import com.solverin.engine.pipeline.StreetScript
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Presentation bridge over [HandPipelineEngine]. Every intent updates the hand description and re-solves the hand
 * off the main thread on [computeDispatcher] (Dispatchers.Default by default); the newest result wins (stale
 * results of superseded intents are dropped). State is exposed as an immutable [StateFlow].
 */
class SolverViewModel(
    private val engine: HandPipelineEngine = HandPipelineEngine(),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private data class Description(
        val stake: Stake = Stake.NL50,
        val preflop: PreflopAction? = null,
        val heroSeat: Seat = Seat.OPENER,
        val stackBb: Double = 100.0,
        val locks: HandLocks = HandLocks(),
        val heroHand: String? = null,
        val flop: String? = null,
        val turn: String? = null,
        val river: String? = null,
        val flopAction: StreetScript? = null,
        val turnAction: StreetScript? = null,
        val riverDecision: RiverScript = RiverScript(),
    )

    private val _state = MutableStateFlow(HandViewState())
    val state: StateFlow<HandViewState> = _state.asStateFlow()

    private var description = Description()
    private val solveLock = Mutex()
    private var generation = 0L
    private var job: Job? = null

    // ---- intents --------------------------------------------------------------------------------------------

    /** Table setup: stake, preflop line, hero's seat, effective stack and optional node locks. Clears the board. */
    fun configure(stake: Stake, preflop: PreflopAction, heroSeat: Seat, stackBb: Double = 100.0, locks: HandLocks = HandLocks()) {
        description = description.copy(stake = stake, preflop = preflop, heroSeat = heroSeat, stackBb = stackBb, locks = locks,
            flop = null, turn = null, river = null, flopAction = null, turnAction = null, riverDecision = RiverScript())
        recompute()
    }

    /** Hero's hole cards, e.g. "AsKd". */
    fun setHoleCards(cards: String) {
        val c = cards.trim()
        if (!validCards(c, 2)) { fail("Invalid hole cards '$cards'"); return }
        description = description.copy(heroHand = c)
        recompute()
    }

    /** Deals the board: flop "Kd8c4h", then turn / river single cards. Later streets are cleared when an earlier one changes. */
    fun setBoard(flop: String?, turn: String? = null, river: String? = null) {
        if ((flop != null && !validCards(flop, 3)) || (turn != null && !validCards(turn, 1)) || (river != null && !validCards(river, 1))) {
            fail("Invalid board"); return
        }
        description = description.copy(flop = flop, turn = turn, river = river,
            flopAction = if (flop == null) null else description.flopAction,
            turnAction = if (turn == null) null else description.turnAction)
        recompute()
    }

    /** Registers the action sequence of [street] (flop / turn); the hand advances to the next dealt street. */
    fun registerAction(street: Street, action: StreetScript) {
        description = when (street) {
            Street.FLOP -> description.copy(flopAction = action, turnAction = null)
            Street.TURN -> description.copy(turnAction = action)
            else -> { fail("Actions are registered on FLOP / TURN; use registerRiverDecision for the river"); return }
        }
        recompute()
    }

    /** River decision: hero acts (null) or faces villain's bet of [villainBetFraction] of the pot. */
    fun registerRiverDecision(villainBetFraction: Double?) {
        description = description.copy(riverDecision = RiverScript(villainBetFraction))
        recompute()
    }

    fun reset() {
        description = Description(stake = description.stake, preflop = description.preflop, heroSeat = description.heroSeat,
            stackBb = description.stackBb, locks = description.locks)
        generation++
        job?.cancel()
        _state.value = HandViewState(stake = description.stake)
    }

    // ---- solving ------------------------------------------------------------------------------------------------

    private fun validCards(s: String, n: Int): Boolean {
        if (s.length != 2 * n) return false
        return s.chunked(2).all { it[0] in RANKS && it[1].lowercaseChar() in SUITS } && s.chunked(2).map { it.lowercase() }.toSet().size == n
    }

    private fun fail(msg: String) = _state.update { it.copy(error = msg, isComputing = false) }

    /** Script of what is known so far: streets beyond the last registered action are not played yet. */
    private fun script(d: Description): HandScript? {
        val preflop = d.preflop ?: return null
        val hand = d.heroHand ?: return null
        val turn = if (d.flop != null && d.flopAction != null) d.turn else null
        val river = if (turn != null && d.turnAction != null) d.river else null
        return HandScript(d.stake, preflop, d.heroSeat, hand, d.flop, turn, river,
            d.flopAction, if (turn != null) d.turnAction else null, d.riverDecision, d.stackBb, d.locks)
    }

    private fun recompute() {
        val d = description
        val s = try { script(d) } catch (e: IllegalArgumentException) { fail(e.message ?: "Invalid hand"); return }
        if (s == null) {
            _state.update { it.copy(stake = d.stake, heroCards = d.heroHand?.chunked(2) ?: emptyList(), board = board(d), error = null) }
            return
        }
        val gen = ++generation
        job?.cancel()
        _state.update { it.copy(isComputing = true, error = null) }
        job = viewModelScope.launch {
            val result = runCatching {
                withContext(computeDispatcher) { solveLock.withLock { engine.play(s) } }
            }
            if (gen != generation) return@launch
            result.fold(
                onSuccess = { r -> _state.value = render(d, r) },
                onFailure = { e -> fail(e.message ?: e.javaClass.simpleName) },
            )
        }
    }

    private fun board(d: Description): List<String> =
        listOfNotNull(d.flop?.chunked(2), d.turn?.let { listOf(it) }, d.river?.let { listOf(it) }).flatten()

    private fun render(d: Description, r: HandResult): HandViewState {
        val last = r.reports.last()
        val names = (last.heroMix.keys + last.heroEv.keys).distinct()
        val best = last.heroEv.filterValues { it.isFinite() }.maxByOrNull { it.value }?.key
        val actions = names.map { n ->
            ActionOption(n, last.heroMix[n] ?: 0f, last.heroEv[n] ?: Double.NaN, n == best)
        }.sortedWith(compareByDescending<ActionOption> { it.isMaxEv }.thenByDescending { if (it.evBb.isNaN()) Double.NEGATIVE_INFINITY else it.evBb })
        return HandViewState(
            stake = d.stake,
            heroCards = d.heroHand!!.chunked(2),
            board = board(script(d)!!.let { s -> d.copy(turn = s.turn, river = s.river) }),
            street = last.street,
            status = r.status,
            potBb = last.potBb,
            heroStackBb = last.stacks.getValue(d.heroSeat),
            effectiveStackBb = last.effectiveStackBb,
            spr = last.spr,
            betSizeFraction = last.heroBetFraction,
            mdf = last.mdf,
            actions = actions,
            history = r.reports.map { rep ->
                val b = rep.heroEv.filterValues { it.isFinite() }.maxByOrNull { it.value }
                StreetSummary(rep.street, rep.line, rep.potBb, b?.key ?: "-", b?.value ?: 0.0)
            },
            heroShowdownEquity = r.heroShowdownEquity,
            rakeAccruedBb = r.rakeAccruedBb,
            latencyMs = r.totalNanos / 1e6,
            isComputing = false,
            error = null,
        )
    }

    override fun onCleared() {
        job?.cancel()
        PipelineBufferPool.clearAll()
        super.onCleared()
    }

    private companion object {
        const val RANKS = "23456789TJQKA"
        const val SUITS = "cdhs"
    }
}
