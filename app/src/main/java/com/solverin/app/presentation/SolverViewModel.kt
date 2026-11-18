package com.solverin.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solverin.engine.core.Card
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.math.PipelineBufferPool
import com.solverin.engine.pipeline.HandPipelineEngine
import com.solverin.engine.preflop.IsoRaiseInterceptor
import com.solverin.engine.preflop.PreflopEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live-hand ViewModel. The user only enters villain actions and board cards; whenever the pointer reaches hero the
 * oracle is solved off the main thread ([computeDispatcher]) and its Max-EV action is committed provisionally. The
 * provisional hero action can be overridden from the oracle chips until the next input is registered, which
 * implicitly confirms it. Every user input is undoable.
 */
class SolverViewModel(
    preflopEngine: PreflopEngine = PreflopEngine(),
    pipeline: HandPipelineEngine = HandPipelineEngine(preflopEngine),
    iso: IsoRaiseInterceptor = IsoRaiseInterceptor(),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val stackBb: Double = 100.0,
) : ViewModel() {

    private val oracle = HeroOracle(preflopEngine, pipeline, iso)

    private var stake = Stake.NL50
    private var profile = VillainProfilePreset.POPULATION
    private var flow = StreetFlowStateMachine.newHand(stackBb)
    private val undoStack = ArrayDeque<FlowState>()
    /** Table state right before hero's provisional action (null = no overridable hero action). */
    private var heroSnapshot: FlowState? = null
    private var committedKey: String? = null
    private var result: OracleResult? = null
    private var pickerRank: Int? = null
    private var customAmount = 2.0
    private var error: String? = null
    private var computing = false
    private var generation = 0L
    private var job: Job? = null

    private val _state = MutableStateFlow(HandViewState())
    val state: StateFlow<HandViewState> = _state.asStateFlow()

    init { publish() }

    // ---- settings ---------------------------------------------------------------------------------------------

    fun setStake(s: Stake) { stake = s; reSolve() }

    fun setProfile(p: VillainProfilePreset) { profile = p; reSolve() }

    fun resetHand() {
        cancelCompute()
        flow = StreetFlowStateMachine.newHand(stackBb)
        undoStack.clear(); heroSnapshot = null; committedKey = null; result = null; pickerRank = null; error = null
        customAmount = 2.0
        publish()
    }

    // ---- card input (two-tap picker) --------------------------------------------------------------------------

    fun pickRank(rank: Int) {
        if (rank !in 0..12) return
        pickerRank = if (pickerRank == rank) null else rank
        publish()
    }

    /** Second tap: suit of the selected rank → hero hole card or board card depending on the phase. */
    fun pickSuit(suit: Int) {
        val r = pickerRank ?: run { fail("Pick a rank first"); return }
        if (suit !in 0..3) return
        pickerRank = null
        pickCard(Card.id(r, suit))
    }

    fun pickCard(card: Int) {
        when (flow.phase) {
            StreetFlowPhase.PICK_HERO_CARDS -> input { StreetFlowStateMachine.pickHeroCard(it, card) }
            StreetFlowPhase.DEAL_BOARD_CARDS -> input { StreetFlowStateMachine.dealCard(it, card) }
            else -> fail(if (flow.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER) "The ${flow.street.name} betting round is not closed yet" else "No card expected now")
        }
    }

    /** Quick edit of hole card [index] (before the hand starts). */
    fun editHeroCard(index: Int) {
        if (flow.phase != StreetFlowPhase.PICK_HERO_POSITION && flow.phase != StreetFlowPhase.PICK_HERO_CARDS) { fail("Hole cards are locked once the hand started"); return }
        if (index !in flow.heroCards.indices) return
        undoStack.addLast(flow)
        flow = flow.copy(heroCards = flow.heroCards.filterIndexed { i, _ -> i != index }, phase = StreetFlowPhase.PICK_HERO_CARDS)
        error = null
        publish()
    }

    /** Quick edit of board card [index]: re-deal it if no action was registered after it. */
    fun editBoardCard(index: Int) {
        if (index !in flow.board.indices) return
        val target = undoStack.lastOrNull { it.board.size == index && it.phase == StreetFlowPhase.DEAL_BOARD_CARDS }
        if (target == null || flow.history.size != target.history.size) { fail("Actions were registered after that card: use Undo"); return }
        cancelCompute()
        while (undoStack.isNotEmpty() && undoStack.last() !== target) undoStack.removeLast()
        flow = undoStack.removeLast()
        heroSnapshot = null; committedKey = null; error = null
        publish()
    }

    // ---- table input ------------------------------------------------------------------------------------------

    fun setHeroPosition(p: Position) = input { StreetFlowStateMachine.setHeroPosition(it, p) }

    /** Villain one-tap action (the hero's provisional action is confirmed by it). */
    fun villainAct(option: VillainOption) {
        if (flow.toAct != null && flow.toAct == flow.heroPosition) { fail("Hero's action is being solved"); return }
        input { StreetFlowStateMachine.act(it, option.kind, option.toAmount) }
    }

    /** Villain bet / raise to the custom amount (all-in when it reaches the stack). */
    fun villainCustom() {
        val p = flow.toAct ?: return
        if (p == flow.heroPosition) { fail("Hero's action is being solved"); return }
        val cap = flow.seat(p).streetInvest + flow.stackLeft(p)
        val kind = when {
            customAmount >= cap - 1e-9 -> ActKind.ALL_IN
            flow.maxBet <= 0.0 -> ActKind.BET
            else -> ActKind.RAISE
        }
        input { StreetFlowStateMachine.act(it, kind, customAmount) }
    }

    fun adjustCustom(steps: Int) {
        val p = flow.toAct ?: return
        val cap = flow.seat(p).streetInvest + flow.stackLeft(p)
        val min = minOf(StreetFlowStateMachine.minRaiseTo(flow), cap)
        customAmount = StreetFlowStateMachine.round1((customAmount + steps * STEP).coerceIn(min, cap))
        publish()
    }

    fun setCustomAmount(bb: Double) {
        val p = flow.toAct ?: return
        val cap = flow.seat(p).streetInvest + flow.stackLeft(p)
        customAmount = StreetFlowStateMachine.round1(bb.coerceIn(minOf(StreetFlowStateMachine.minRaiseTo(flow), cap), cap))
        publish()
    }

    /** Overrides hero's provisional action with oracle option [key] (only before the next input). */
    fun selectHeroAction(key: String) {
        val snap = heroSnapshot ?: run { fail("No pending hero action to override"); return }
        val opt = result?.options?.firstOrNull { it.key == key } ?: run { fail("Unknown option $key"); return }
        runCatching { StreetFlowStateMachine.act(snap, opt.kind, opt.toAmount, isHero = true) }
            .onSuccess { flow = it; committedKey = key; error = null; resetCustom(); publish() }
            .onFailure { fail(it.message ?: "Illegal hero action") }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        cancelCompute()
        flow = undoStack.removeLast()
        heroSnapshot = null; committedKey = null; error = null; pickerRank = null
        resetCustom()
        settle()
    }

    // ---- internals --------------------------------------------------------------------------------------------

    private inline fun input(transition: (FlowState) -> FlowState) {
        if (computing && flow.toAct == flow.heroPosition && flow.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER) { fail("Hero's action is being solved"); return }
        val next = try { transition(flow) } catch (e: IllegalArgumentException) { fail(e.message ?: "Illegal input"); return } catch (e: IllegalStateException) { fail(e.message ?: "Illegal input"); return }
        cancelCompute()
        undoStack.addLast(flow)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        flow = next
        heroSnapshot = null; committedKey = null; error = null
        resetCustom()
        settle()
    }

    private fun resetCustom() {
        val p = flow.toAct
        customAmount = if (p == null) 2.0 else {
            val cap = flow.seat(p).streetInvest + flow.stackLeft(p)
            minOf(StreetFlowStateMachine.minRaiseTo(flow), cap)
        }
    }

    private fun heroNeedsOracle(s: FlowState): Boolean =
        s.heroPosition != null && s.heroCards.size == 2 && !s.heroFolded && s.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER

    /** After every input: solve hero's node; when hero is to act, commit the Max-EV action provisionally. */
    private fun settle() {
        if (!heroNeedsOracle(flow)) { publish(); return }
        val snapshot = flow
        val gen = ++generation
        val st = stake; val pr = profile
        computing = true
        publish()
        job = viewModelScope.launch {
            val r = withContext(computeDispatcher) { oracle.compute(snapshot, st, pr) }
            if (gen != generation) return@launch
            computing = false
            result = r
            if (snapshot.toAct == snapshot.heroPosition) commitBest(snapshot, r)
            publish()
        }
    }

    private fun commitBest(snapshot: FlowState, r: OracleResult) {
        val ordered = r.options.filter { it.evBb.isFinite() }.sortedByDescending { it.evBb } + r.options.filter { !it.evBb.isFinite() }
        for (o in ordered) {
            val applied = runCatching { StreetFlowStateMachine.act(snapshot, o.kind, o.toAmount, isHero = true) }.getOrNull() ?: continue
            heroSnapshot = snapshot; committedKey = o.key; flow = applied
            resetCustom()
            return
        }
        error = "No legal hero action"
    }

    private fun reSolve() {
        val snap = heroSnapshot
        if (snap != null) {
            cancelCompute()
            flow = snap; heroSnapshot = null; committedKey = null
            settle()
        } else if (heroNeedsOracle(flow)) {
            cancelCompute(); settle()
        } else publish()
    }

    private fun cancelCompute() {
        generation++
        job?.cancel(); job = null
        computing = false
    }

    private fun fail(msg: String) { error = msg; publish() }

    private fun publish() {
        val s = flow
        val hero = s.heroPosition
        val r = result
        val best = r?.best?.key
        val toAct = s.toAct
        val cap = toAct?.let { s.seat(it).streetInvest + s.stackLeft(it) } ?: stackBb
        _state.value = HandViewState(
            stake = stake,
            profile = profile,
            phase = s.phase,
            street = s.street,
            heroCards = s.heroCards.map { Card.toString(it) },
            heroCardIds = s.heroCards,
            heroPosition = hero?.short,
            board = s.board.map { Card.toString(it) },
            boardIds = s.board,
            seats = if (hero == null) emptyList() else Position.entries.map { p ->
                val seat = s.seat(p)
                SeatView(p.short, seat.lastLabel ?: if (seat.inHand) "" else "Folded", seat.streetInvest, seat.inHand, seat.allIn, p == hero, p == toAct)
            },
            toAct = toAct?.short,
            potBb = s.potBb,
            effectiveStackBb = if (hero == null) stackBb else s.effectiveStackBb,
            toCallBb = hero?.let { s.toCall(it) } ?: 0.0,
            options = r?.options?.map { ActionOption(it.key, it.label, it.frequency, it.evBb, it.key == best, it.kind, it.toAmount) }.orEmpty(),
            nodeLabel = r?.nodeLabel.orEmpty(),
            approximate = r?.approximate ?: false,
            latencyMs = r?.latencyMs ?: 0.0,
            heroCommittedKey = committedKey,
            heroOverridable = heroSnapshot != null,
            villainOptions = if (toAct != null && toAct != hero) StreetFlowStateMachine.options(s) else emptyList(),
            customAmountBb = customAmount,
            customStepBb = STEP,
            minCustomBb = if (toAct == null) 1.0 else minOf(StreetFlowStateMachine.minRaiseTo(s), cap),
            maxCustomBb = cap,
            cardsNeeded = s.cardsNeeded,
            pickerRank = pickerRank,
            usedCards = s.usedCards,
            canUndo = undoStack.isNotEmpty(),
            isComputing = computing,
            error = error,
            history = s.history.map { "${it.street.name.take(1)} ${it.position.short}${if (it.isHero) "★" else ""} ${label(it)}" },
        )
    }

    private fun label(a: ActionRecord): String = when (a.kind) {
        ActKind.FOLD -> "fold"
        ActKind.CHECK -> "check"
        ActKind.CALL -> "call ${StreetFlowStateMachine.fmt(a.toAmount)}"
        ActKind.LIMP -> "limp"
        ActKind.BET -> "bet ${StreetFlowStateMachine.fmt(a.toAmount)}"
        ActKind.RAISE -> "raise ${StreetFlowStateMachine.fmt(a.toAmount)}"
        ActKind.ALL_IN -> "all-in ${StreetFlowStateMachine.fmt(a.toAmount)}"
    }

    override fun onCleared() {
        cancelCompute()
        PipelineBufferPool.clearAll()
        super.onCleared()
    }

    private companion object {
        const val STEP = 0.5
        const val MAX_UNDO = 200
    }
}
