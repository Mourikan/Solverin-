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
 * oracle is solved off the main thread ([computeDispatcher]) and its Max-EV action is assumed automatically. The
 * assumed action can be replaced with [overrideHeroAction] until the next input confirms it. A Max-EV FOLD stays
 * recoverable: [continueInHand] (or any further villain input) switches hero to the best non-fold action.
 * Stake, effective stacks and the villain node-lock profile re-solve the current node instantly. Inputs are undoable.
 */
class SolverViewModel(
    preflopEngine: PreflopEngine = PreflopEngine(),
    pipeline: HandPipelineEngine = HandPipelineEngine(preflopEngine),
    iso: IsoRaiseInterceptor = IsoRaiseInterceptor(),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    initialStackBb: Double = 100.0,
) : ViewModel() {

    private val oracle = HeroOracle(preflopEngine, pipeline, iso)

    private var stake = Stake.NL50
    private var profile = VillainProfilePreset.POPULATION
    private var tableStack = initialStackBb
    private var flow = StreetFlowStateMachine.newHand(tableStack)
    private val undoStack = ArrayDeque<FlowState>()
    /** Table state right before hero's assumed action (null = no changeable hero action). */
    private var heroSnapshot: FlowState? = null
    private var committedKey: String? = null
    private var result: OracleResult? = null
    private var selectedSeat: Position? = null
    private var pickerRank: Int? = null
    private var customAmount = 2.0
    private var error: String? = null
    private var computing = false
    private var generation = 0L
    private var job: Job? = null

    private val _state = MutableStateFlow(HandViewState())
    val state: StateFlow<HandViewState> = _state.asStateFlow()

    init { publish() }

    // ---- tactical bar -----------------------------------------------------------------------------------------

    fun setStake(s: Stake) { if (s != stake) { stake = s; reSolve() } }

    fun setProfile(p: VillainProfilePreset) { if (p != profile) { profile = p; reSolve() } }

    /** Toggles the seat whose stack the stack selector edits (null = whole table). */
    fun selectSeat(p: Position?) {
        selectedSeat = if (p == selectedSeat) null else p
        publish()
    }

    /** Sets the starting stack (bb) of the selected seat, or of every seat when none is selected. */
    fun setEffectiveStack(bb: Double) {
        val v = StreetFlowStateMachine.round1(bb.coerceIn(MIN_STACK, MAX_STACK))
        val seat = selectedSeat
        val stacks = if (seat == null) List(6) { v } else flow.stacks.toMutableList().also { it[seat.ordinal] = v }
        if (seat == null) tableStack = v
        applyStacks(stacks)
    }

    /** ± adjustment of the selected seat's (or the table's) stack. */
    fun adjustStack(deltaBb: Double) {
        val base = selectedSeat?.let { flow.startStack(it) } ?: tableStack
        setEffectiveStack(base + deltaBb)
    }

    private fun applyStacks(stacks: List<Double>) {
        val restack = { s: FlowState -> StreetFlowStateMachine.withStacks(s, stacks) }
        val r = runCatching { restack(flow) }
        if (r.isFailure) { fail(r.exceptionOrNull()?.message ?: "Stack inválido"); return }
        for (i in undoStack.indices) undoStack[i] = runCatching { restack(undoStack[i]) }.getOrDefault(undoStack[i])
        val snap = heroSnapshot
        if (snap != null) {
            heroSnapshot = restack(snap)
            reSolve()
        } else {
            flow = r.getOrThrow()
            reSolve()
        }
    }

    fun resetHand() {
        cancelCompute()
        flow = StreetFlowStateMachine.newHand(tableStack)
        undoStack.clear(); heroSnapshot = null; committedKey = null; result = null; pickerRank = null; error = null; selectedSeat = null
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
        val r = pickerRank ?: run { fail("Elige primero el rango"); return }
        if (suit !in 0..3) return
        pickerRank = null
        pickCard(Card.id(r, suit))
    }

    fun pickCard(card: Int) {
        when (flow.phase) {
            StreetFlowPhase.PICK_HERO_CARDS -> input { StreetFlowStateMachine.pickHeroCard(it, card) }
            StreetFlowPhase.DEAL_BOARD_CARDS -> input { StreetFlowStateMachine.dealCard(it, card) }
            StreetFlowPhase.VILLAIN_ACTION_STEPPER -> fail("La ronda de ${flow.street.name} sigue abierta")
            else -> fail("No se espera ninguna carta ahora")
        }
    }

    /** Quick edit of hole card [index] (before the hand starts). */
    fun editHeroCard(index: Int) {
        if (flow.phase != StreetFlowPhase.PICK_HERO_POSITION && flow.phase != StreetFlowPhase.PICK_HERO_CARDS) { fail("Las cartas del héroe se bloquean al empezar la mano"); return }
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
        if (target == null || flow.history.size != target.history.size) { fail("Hay acciones después de esa carta: usa Undo"); return }
        cancelCompute()
        while (undoStack.isNotEmpty() && undoStack.last() !== target) undoStack.removeLast()
        flow = undoStack.removeLast()
        heroSnapshot = null; committedKey = null; error = null
        publish()
    }

    // ---- table input ------------------------------------------------------------------------------------------

    fun setHeroPosition(p: Position) = input { StreetFlowStateMachine.setHeroPosition(it, p) }

    /** Villain one-tap action; it confirms hero's assumed action (a pending Max-EV FOLD is switched to continue first). */
    fun villainAct(option: VillainOption) {
        if (heroFoldPending()) { if (!continueInHand()) return }
        if (flow.toAct != null && flow.toAct == flow.heroPosition) { fail("Resolviendo la acción del héroe"); return }
        input { StreetFlowStateMachine.act(it, option.kind, option.toAmount) }
    }

    /** Villain bet / raise to the custom amount (all-in when it reaches the stack). */
    fun villainCustom() {
        if (heroFoldPending()) { if (!continueInHand()) return }
        val p = flow.toAct ?: return
        if (p == flow.heroPosition) { fail("Resolviendo la acción del héroe"); return }
        val cap = flow.seat(p).streetInvest + flow.stackLeft(p)
        val kind = when {
            customAmount >= cap - 1e-9 -> ActKind.ALL_IN
            flow.maxBet <= 0.0 -> ActKind.BET
            else -> ActKind.RAISE
        }
        input { StreetFlowStateMachine.act(it, kind, customAmount) }
    }

    fun adjustCustom(steps: Int) {
        val (min, cap) = customBounds() ?: return
        customAmount = StreetFlowStateMachine.round1((customAmount + steps * STEP).coerceIn(min, cap))
        publish()
    }

    fun setCustomAmount(bb: Double) {
        val (min, cap) = customBounds() ?: return
        customAmount = StreetFlowStateMachine.round1(bb.coerceIn(min, cap))
        publish()
    }

    /** Manual deviation: hero actually played oracle option [key] instead of the Max-EV action. */
    fun overrideHeroAction(key: String) {
        val snap = heroSnapshot ?: run { fail("No hay acción del héroe pendiente de cambiar"); return }
        val opt = result?.options?.firstOrNull { it.key == key } ?: run { fail("Opción desconocida $key"); return }
        runCatching { StreetFlowStateMachine.act(snap, opt.kind, opt.toAmount, isHero = true) }
            .onSuccess { flow = it; committedKey = key; error = null; resetCustom(); publish() }
            .onFailure { fail(it.message ?: "Acción ilegal") }
    }

    /** Anti-lock: replaces a pending hero FOLD with the best continuing (non-fold) action. */
    fun continueInHand(): Boolean {
        val snap = heroSnapshot
        val r = result
        if (snap == null || r == null || committedKey == null) { fail("No hay fold del héroe que revertir"); return false }
        val alt = r.options.filter { it.kind != ActKind.FOLD && it.evBb.isFinite() }.sortedByDescending { it.evBb }
        for (o in alt) {
            val next = runCatching { StreetFlowStateMachine.act(snap, o.kind, o.toAmount, isHero = true) }.getOrNull() ?: continue
            flow = next; committedKey = o.key; error = null
            resetCustom(); publish()
            return true
        }
        fail("No hay acción de continuación legal")
        return false
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        cancelCompute()
        flow = undoStack.removeLast()
        heroSnapshot = null; committedKey = null; error = null; pickerRank = null
        resetCustom()
        settle(null)
    }

    // ---- internals --------------------------------------------------------------------------------------------

    private fun heroFoldPending(): Boolean =
        heroSnapshot != null && flow.phase == StreetFlowPhase.HAND_COMPLETE && flow.heroFolded &&
            result?.options?.firstOrNull { it.key == committedKey }?.kind == ActKind.FOLD

    private fun customBounds(): Pair<Double, Double>? {
        val p = flow.toAct ?: return null
        val cap = flow.seat(p).streetInvest + flow.stackLeft(p)
        return minOf(StreetFlowStateMachine.minRaiseTo(flow), cap) to cap
    }

    private inline fun input(transition: (FlowState) -> FlowState) {
        if (computing && flow.toAct == flow.heroPosition && flow.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER) { fail("Resolviendo la acción del héroe"); return }
        val next = try { transition(flow) } catch (e: IllegalArgumentException) { fail(e.message ?: "Entrada ilegal"); return } catch (e: IllegalStateException) { fail(e.message ?: "Entrada ilegal"); return }
        cancelCompute()
        undoStack.addLast(flow)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        flow = next
        heroSnapshot = null; committedKey = null; error = null
        resetCustom()
        settle(null)
    }

    private fun resetCustom() {
        customAmount = customBounds()?.first ?: 2.0
    }

    private fun heroNeedsOracle(s: FlowState): Boolean =
        s.heroPosition != null && s.heroCards.size == 2 && !s.heroFolded && s.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER

    /** After every input: solve hero's node; when hero is to act, assume the Max-EV action ([preferKey] = keep a deviation). */
    private fun settle(preferKey: String?) {
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
            if (snapshot.toAct == snapshot.heroPosition) commit(snapshot, r, preferKey)
            publish()
        }
    }

    private fun commit(snapshot: FlowState, r: OracleResult, preferKey: String?) {
        val ranked = r.options.filter { it.evBb.isFinite() }.sortedByDescending { it.evBb }
        val ordered = (ranked.filter { it.key == preferKey } + ranked + r.options.filter { !it.evBb.isFinite() }).distinct()
        for (o in ordered) {
            val applied = runCatching { StreetFlowStateMachine.act(snapshot, o.kind, o.toAmount, isHero = true) }.getOrNull() ?: continue
            heroSnapshot = snapshot; committedKey = o.key; flow = applied
            resetCustom()
            return
        }
        error = "Sin acción legal para el héroe"
    }

    /** Re-solves the current node (stake / stack / profile change); a pending hero action is re-assumed. */
    private fun reSolve() {
        val snap = heroSnapshot
        cancelCompute()
        if (snap != null) {
            val keep = committedKey?.takeIf { it != result?.best?.key }
            flow = snap; heroSnapshot = null; committedKey = null
            settle(keep)
        } else settle(null)
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
        val best = r?.best
        val foldPending = heroFoldPending()
        // A pending hero fold keeps the villain stepper of the continuation line available.
        val stepper = if (foldPending) heroSnapshot?.let { snap ->
            r?.options?.filter { it.kind != ActKind.FOLD && it.evBb.isFinite() }?.sortedByDescending { it.evBb }
                ?.firstNotNullOfOrNull { o -> runCatching { StreetFlowStateMachine.act(snap, o.kind, o.toAmount, isHero = true) }.getOrNull() }
        } ?: s else s
        val toAct = stepper.toAct
        val cap = toAct?.let { stepper.seat(it).streetInvest + stepper.stackLeft(it) } ?: tableStack
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
            seats = Position.entries.map { p ->
                val seat = s.seat(p)
                SeatView(p.short, p, seat.lastLabel ?: if (seat.inHand) "" else "Fold", seat.streetInvest, s.stackLeft(p), seat.inHand,
                    seat.allIn, p == hero, p == s.toAct, p == selectedSeat)
            },
            toAct = s.toAct?.short,
            potBb = s.potBb,
            effectiveStackBb = if (hero == null) s.stackBb else s.effectiveStackBb,
            spr = if (hero == null) Double.POSITIVE_INFINITY else s.spr,
            tableStackBb = tableStack,
            selectedSeat = selectedSeat?.short,
            toCallBb = hero?.let { s.toCall(it) } ?: 0.0,
            options = r?.options?.map {
                ActionOption(it.key, it.label, it.frequency, it.evBb, if (best != null && it.evBb.isFinite()) it.evBb - best.evBb else Double.NaN,
                    it.key == best?.key, it.kind, it.toAmount)
            }.orEmpty(),
            nodeLabel = r?.nodeLabel.orEmpty(),
            approximate = r?.approximate ?: false,
            latencyMs = r?.latencyMs ?: 0.0,
            heroCommittedKey = committedKey,
            heroOverridable = heroSnapshot != null,
            manualDeviation = committedKey != null && best != null && committedKey != best.key,
            heroFoldPending = foldPending,
            explanation = r?.let { SpanishStrategyExplainer.explain(it, stake, profile, committedKey) },
            villainOptions = if (toAct != null && toAct != hero) StreetFlowStateMachine.options(stepper) else emptyList(),
            customAmountBb = customAmount,
            customStepBb = STEP,
            minCustomBb = if (toAct == null) 1.0 else minOf(StreetFlowStateMachine.minRaiseTo(stepper), cap),
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

    companion object {
        const val STEP = 0.5
        const val MAX_UNDO = 200
        const val MIN_STACK = 5.0
        const val MAX_STACK = 400.0
        val STACK_PRESETS = listOf(20.0, 30.0, 40.0, 50.0, 75.0, 100.0, 125.0, 150.0, 200.0)
    }
}
