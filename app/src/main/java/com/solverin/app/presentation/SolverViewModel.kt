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
import kotlinx.coroutines.CancellationException
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

    /**
     * One undo step: the table plus hero's changeable decision at that moment (snapshot, committed option, solved node,
     * implicit-fold chain), so Undo returns to exactly the same node instead of a baked-in hero action.
     */
    private class UndoEntry(
        val flow: FlowState,
        val heroSnapshot: FlowState?,
        val committedKey: String?,
        val result: OracleResult?,
        val heroChain: Boolean,
        val stake: Stake,
        val profile: VillainProfilePreset,
    )

    private val undoStack = ArrayDeque<UndoEntry>()
    /** Table state right before hero's assumed action (null = no changeable hero action). */
    private var heroSnapshot: FlowState? = null
    private var committedKey: String? = null
    private var result: OracleResult? = null
    private var selectedSeat: Position? = null
    /** Seat chosen in the preflop quick matrix (null = the seat to act). */
    private var matrixSeat: Position? = null
    /** "Avanzar al flop": keep folding by omission once hero's assumed action is committed. */
    private var autoAdvance = false
    /** Implicit folds were chained after hero's assumed action (a deviation re-runs them). */
    private var heroChain = false
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
        for (i in undoStack.indices) {
            val e = undoStack[i]
            // A restacked step keeps hero's decision but drops the solved node: Undo re-solves it.
            undoStack[i] = UndoEntry(runCatching { restack(e.flow) }.getOrDefault(e.flow),
                e.heroSnapshot?.let { h -> runCatching { restack(h) }.getOrDefault(h) }, e.committedKey, null, e.heroChain, e.stake, e.profile)
        }
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
        matrixSeat = null; autoAdvance = false; heroChain = false
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
        undoStack.addLast(entry(flow))
        flow = flow.copy(heroCards = flow.heroCards.filterIndexed { i, _ -> i != index }, phase = StreetFlowPhase.PICK_HERO_CARDS)
        error = null
        publish()
    }

    /** Quick edit of board card [index]: re-deal it if no action was registered after it. */
    fun editBoardCard(index: Int) {
        if (index !in flow.board.indices) return
        val target = undoStack.lastOrNull { it.flow.board.size == index && it.flow.phase == StreetFlowPhase.DEAL_BOARD_CARDS }
        if (target == null || flow.history.size != target.flow.history.size) { fail("Hay acciones después de esa carta: usa Undo"); return }
        cancelCompute()
        while (undoStack.isNotEmpty() && undoStack.last() !== target) undoStack.removeLast()
        flow = undoStack.removeLast().flow
        heroSnapshot = null; committedKey = null; error = null; heroChain = false; matrixSeat = null; autoAdvance = false
        resetCustom()
        settle(null)
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

    // ---- preflop quick matrix (auto-fold by omission) ----------------------------------------------------------

    /**
     * Selects a seat of the matrix. Seats before hero's turn are reached by implicit folds; a seat that acts after
     * hero first folds the villains up to hero so hero's Max-EV action is assumed, then the seat becomes editable.
     */
    fun selectMatrixSeat(p: Position) {
        if (p == flow.heroPosition) { fail("Tu acción la asume el oráculo: usa ⚡ Hice otra jugada"); return }
        if (heroFoldPending()) { if (!continueInHand()) return }
        if (!StreetFlowStateMachine.canStillAct(flow, p)) { fail("${p.short} no tiene acción pendiente en esta calle"); return }
        if (StreetFlowStateMachine.foldUntil(flow, p) != null) {
            matrixSeat = p; error = null
            resetCustom(); publish()
            return
        }
        input { StreetFlowStateMachine.foldVillainsUntilHero(it) }
        matrixSeat = p
        publish()
    }

    /** Assigns [option] to the matrix seat; every unassigned villain acting before it folds by omission. */
    fun assignSeatAction(option: VillainOption) {
        if (heroFoldPending()) { if (!continueInHand()) return }
        val target = matrixSeat ?: flow.toAct ?: run { fail("No hay asiento seleccionado"); return }
        input(chainRaisedPot = true) { s ->
            val at = requireNotNull(StreetFlowStateMachine.foldUntil(s, target)) { "${target.short} no puede actuar ahora" }
            StreetFlowStateMachine.act(at, option.kind, option.toAmount)
        }
    }

    /** Matrix seat bets / raises to the custom amount. */
    fun assignSeatCustom() {
        val at = matrixState() ?: run { fail("No hay asiento seleccionado"); return }
        val p = at.toAct ?: return
        val cap = at.seat(p).streetInvest + at.stackLeft(p)
        val kind = when {
            customAmount >= cap - 1e-9 -> ActKind.ALL_IN
            at.maxBet <= 0.0 -> ActKind.BET
            else -> ActKind.RAISE
        }
        assignSeatAction(VillainOption("Custom", kind, customAmount))
    }

    /** "✓ Avanzar al Flop (Resto Fold)": every unassigned seat folds by omission; hero's turn is auto-assumed on the way. */
    fun advancePreflopAssumingRemainingFold() {
        if (flow.phase != StreetFlowPhase.VILLAIN_ACTION_STEPPER || flow.street != TableStreet.PREFLOP) { fail("La ronda preflop no está abierta"); return }
        if (heroFoldPending()) { if (!continueInHand()) return }
        input { StreetFlowStateMachine.foldVillainsUntilHero(it) }
        autoAdvance = flow.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER && flow.toAct == flow.heroPosition
    }

    /** Manual deviation: hero actually played oracle option [key] instead of the Max-EV action. */
    fun overrideHeroAction(key: String) {
        val snap = heroSnapshot ?: run { fail("No hay acción del héroe pendiente de cambiar"); return }
        val opt = result?.options?.firstOrNull { it.key == key } ?: run { fail("Opción desconocida $key"); return }
        runCatching { StreetFlowStateMachine.act(snap, opt.kind, opt.toAmount, isHero = true) }
            .onSuccess {
                flow = if (heroChain) StreetFlowStateMachine.foldVillainsUntilHero(it) else it
                committedKey = key; error = null; resetCustom(); publish()
            }
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

    /**
     * Restores the previous input step together with hero's decision at that step: an auto-assumed hero action becomes
     * changeable again, and the node is re-solved when stake / profile / stacks changed since.
     */
    fun undo() {
        val e = undoStack.removeLastOrNull() ?: return
        cancelCompute()
        flow = e.flow
        heroSnapshot = e.heroSnapshot; committedKey = e.committedKey; result = e.result; heroChain = e.heroChain
        error = null; pickerRank = null; matrixSeat = null; autoAdvance = false
        resetCustom()
        when {
            heroSnapshot == null -> settle(null)
            result == null || e.stake != stake || e.profile != profile -> reSolve()
            else -> publish()
        }
    }

    private fun entry(f: FlowState) = UndoEntry(f, heroSnapshot, committedKey, result, heroChain, stake, profile)

    // ---- internals --------------------------------------------------------------------------------------------

    private fun heroFoldPending(): Boolean =
        heroSnapshot != null && flow.phase == StreetFlowPhase.HAND_COMPLETE && flow.heroFolded &&
            result?.options?.firstOrNull { it.key == committedKey }?.kind == ActKind.FOLD

    /** State at the matrix seat's turn (implicit folds applied), or the live state when no seat is selected. */
    private fun matrixState(): FlowState? {
        val m = matrixSeat ?: return flow.takeIf { it.toAct != null && it.toAct != it.heroPosition }
        return StreetFlowStateMachine.foldUntil(flow, m)
    }

    private fun customBounds(): Pair<Double, Double>? {
        val at = matrixState() ?: return null
        val p = at.toAct ?: return null
        val cap = at.seat(p).streetInvest + at.stackLeft(p)
        return minOf(StreetFlowStateMachine.minRaiseTo(at), cap) to cap
    }

    /**
     * Applies one user input (undoable). With [chainRaisedPot], a villain preflop 3-bet / 4-bet+ makes every villain
     * that has not put chips in voluntarily fold by omission up to hero's turn (separate undo step, so a cold 4-bet of
     * one of those seats can still be entered after Undo).
     */
    private inline fun input(chainRaisedPot: Boolean = false, transition: (FlowState) -> FlowState) {
        if (computing && flow.toAct == flow.heroPosition && flow.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER) { fail("Resolviendo la acción del héroe"); return }
        val next = try { transition(flow) } catch (e: IllegalArgumentException) { fail(e.message ?: "Entrada ilegal"); return } catch (e: IllegalStateException) { fail(e.message ?: "Entrada ilegal"); return }
        cancelCompute()
        undoStack.addLast(entry(flow))
        var settled = next
        val last = next.history.lastOrNull()
        if (chainRaisedPot && last != null && !last.isHero && last.aggressive && last.street == TableStreet.PREFLOP && last.raiseLevel >= 2) {
            val folded = StreetFlowStateMachine.foldNonParticipantsUntilHero(next)
            if (folded !== next) { undoStack.addLast(UndoEntry(next, null, null, null, false, stake, profile)); settled = folded }
        }
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        flow = settled
        heroSnapshot = null; committedKey = null; error = null
        matrixSeat = null; autoAdvance = false; heroChain = false
        resetCustom()
        settle(null)
    }

    private fun resetCustom() {
        customAmount = customBounds()?.first ?: 2.0
    }

    /** Hero still has a decision on this street: seated, live, not all-in, betting round open. */
    private fun heroNeedsOracle(s: FlowState): Boolean {
        val hero = s.heroPosition ?: return false
        return s.heroCards.size == 2 && !s.heroFolded && !s.seat(hero).allIn && s.stackLeft(hero) > 0.0 &&
            s.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER
    }

    /** After every input: solve hero's node; when hero is to act, assume the Max-EV action ([preferKey] = keep a deviation). */
    private fun settle(preferKey: String?) {
        // No decision left for hero (board to deal, hand over, hero all-in): the banner keeps the last solved node.
        if (!heroNeedsOracle(flow)) { publish(); return }
        val snapshot = flow
        val gen = ++generation
        val st = stake; val pr = profile
        computing = true
        publish()
        job = viewModelScope.launch {
            val r = try {
                withContext(computeDispatcher) { oracle.compute(snapshot, st, pr) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                safeFallback(snapshot)
            }
            if (gen != generation) return@launch
            computing = false
            result = r
            if (snapshot.toAct == snapshot.heroPosition) {
                commit(snapshot, r, preferKey)
                if (autoAdvance && flow.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER && flow.toAct != flow.heroPosition) {
                    flow = StreetFlowStateMachine.foldVillainsUntilHero(flow)
                    heroChain = true
                }
                autoAdvance = false
                resetCustom()
            }
            publish()
        }
    }

    private fun commit(snapshot: FlowState, r: OracleResult, preferKey: String?) {
        val ranked = r.options.filter { it.evBb.isFinite() }.sortedByDescending { it.evBb }
        // Assumed action: a kept deviation, else the recommended (Nash-led) action, then the rest by EV as legal fallbacks.
        val ordered = (ranked.filter { it.key == preferKey } + listOfNotNull(r.best) + ranked + r.options.filter { !it.evBb.isFinite() }).distinct()
        for (o in ordered) {
            val applied = runCatching { StreetFlowStateMachine.act(snapshot, o.kind, o.toAmount, isHero = true) }.getOrNull() ?: continue
            heroSnapshot = snapshot; committedKey = o.key; flow = applied
            resetCustom()
            return
        }
        // Empty / illegal node: deterministic check-or-fold so the pointer never stays on hero.
        val fallback = safeFallback(snapshot)
        result = fallback
        for (o in fallback.options) {
            val applied = runCatching { StreetFlowStateMachine.act(snapshot, o.kind, o.toAmount, isHero = true) }.getOrNull() ?: continue
            heroSnapshot = snapshot; committedKey = o.key; flow = applied
            resetCustom()
            return
        }
        error = "Sin acción legal para el héroe"
    }

    /**
     * Deterministic last-resort node (engine failure or no legal option): CHECK when nothing is owed, otherwise FOLD as
     * the recommendation with CALL kept as the continue option (EV = −amount owed, the price of continuing blind).
     */
    private fun safeFallback(s: FlowState): OracleResult {
        val hero = requireNotNull(s.heroPosition)
        val invested = s.seat(hero).streetInvest
        val toCall = s.toCall(hero)
        val cap = invested + s.stackLeft(hero)
        val options = if (toCall <= 1e-9) listOf(OracleOption("CHECK", "Check", 1f, 0.0, ActKind.CHECK, invested))
        else listOf(
            OracleOption("FOLD", "Fold", 1f, 0.0, ActKind.FOLD, invested),
            OracleOption("CALL", "Call ${StreetFlowStateMachine.fmt(minOf(s.maxBet, cap))}", 0f, -minOf(toCall, cap - invested), ActKind.CALL, minOf(s.maxBet, cap)),
        )
        return OracleResult("${s.street.name} · nodo de respaldo", options, true, 0.0)
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
        val live = if (foldPending) heroSnapshot?.let { snap ->
            r?.options?.filter { it.kind != ActKind.FOLD && it.evBb.isFinite() }?.sortedByDescending { it.evBb }
                ?.firstNotNullOfOrNull { o -> runCatching { StreetFlowStateMachine.act(snap, o.kind, o.toAmount, isHero = true) }.getOrNull() }
        } ?: s else s
        // Matrix seat: options are those of that seat once the villains before it folded by omission.
        val stepper = matrixSeat?.let { StreetFlowStateMachine.foldUntil(live, it) } ?: live
        val streetActed = s.streetRecords(s.street).map { it.position }.toSet()
        // Hero's decision node: the state before the assumed action, or the live state for a preview.
        val node = heroSnapshot ?: s
        val optionViews = r?.options?.map { o ->
            val (amount, pct) = amountOf(o, node)
            ActionOption(o.key, o.label, o.frequency, o.evBb, if (best != null && o.evBb.isFinite()) o.evBb - best.evBb else Double.NaN,
                o.key == best?.key, o.kind, o.toAmount, shortName(o, node, pct), amount, pct)
        }.orEmpty()
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
                    seat.allIn, p == hero, p == s.toAct, p == selectedSeat,
                    actedThisStreet = p in streetActed, canAct = StreetFlowStateMachine.canStillAct(live, p) && p != hero,
                    isMatrixSelected = p == stepper.toAct && p != hero && stepper.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER)
            },
            toAct = s.toAct?.short,
            actingSeat = toAct?.takeIf { it != hero }?.short,
            canAdvancePreflop = s.street == TableStreet.PREFLOP && (s.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER || foldPending) && !computing,
            potBb = s.potBb,
            effectiveStackBb = if (hero == null) s.stackBb else s.effectiveStackBb,
            spr = if (hero == null) Double.POSITIVE_INFINITY else s.spr,
            tableStackBb = tableStack,
            selectedSeat = selectedSeat?.short,
            toCallBb = hero?.let { s.toCall(it) } ?: 0.0,
            options = optionViews,
            streetMaxBetBb = stepper.maxBet,
            heroCommittedBb = hero?.let { s.seat(it).streetInvest } ?: 0.0,
            recommendedAmountBb = optionViews.firstOrNull { it.isMaxEv }?.amountBb ?: 0.0,
            recommendedPotPct = optionViews.firstOrNull { it.isMaxEv }?.potPct ?: 0.0,
            oraclePreview = r != null && committedKey == null,
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

    /** Explicit bb amount (street total for aggression, chips added for call / limp) and size as a pot fraction. */
    private fun amountOf(o: OracleOption, node: FlowState): Pair<Double, Double> {
        val hero = node.heroPosition ?: return 0.0 to 0.0
        val invested = node.seat(hero).streetInvest
        return when (o.kind) {
            ActKind.FOLD, ActKind.CHECK -> 0.0 to 0.0
            ActKind.CALL, ActKind.LIMP -> StreetFlowStateMachine.round1(maxOf(0.0, o.toAmount - invested)) to 0.0
            ActKind.BET, ActKind.RAISE, ActKind.ALL_IN ->
                o.toAmount to if (node.potBb > 0.0) maxOf(0.0, o.toAmount - invested) / node.potBb else 0.0
        }
    }

    /** Short banner name: OPEN / ISO-RAISE / 3-BET / 4-BET / C-BET / BARREL / OVERBET / DONK / PROBE / RAISE / ALL-IN … */
    private fun shortName(o: OracleOption, node: FlowState, potPct: Double): String {
        val hero = node.heroPosition
        return when (o.kind) {
            ActKind.FOLD -> "FOLD"
            ActKind.CHECK -> "CHECK"
            ActKind.CALL -> "CALL"
            ActKind.LIMP -> "LIMP"
            ActKind.ALL_IN -> "ALL-IN"
            ActKind.BET, ActKind.RAISE -> when {
                node.street == TableStreet.PREFLOP -> when {
                    o.key == "ISO_RAISE" -> "ISO-RAISE"
                    node.raiseLevel == 0 -> "OPEN"
                    else -> "${node.raiseLevel + 2}-BET"
                }
                node.maxBet > 0.0 -> if (node.raiseLevel <= 1) "RAISE" else "${node.raiseLevel + 1}-BET"
                potPct > 1.0 + 1e-9 -> "OVERBET"
                o.key == "DONK" -> "DONK"
                o.key == "PROBE" -> "PROBE"
                o.key == "SECOND_BARREL" || o.key == "TRIPLE_BARREL" -> "BARREL"
                o.key == "DELAYED_CBET" || o.key == "DELAYED_BET" -> "C-BET"
                node.history.lastOrNull { it.aggressive && it.street != node.street }?.position == hero ->
                    if (node.street == TableStreet.FLOP) "C-BET" else "BARREL"
                else -> "BET"
            }
        }
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
