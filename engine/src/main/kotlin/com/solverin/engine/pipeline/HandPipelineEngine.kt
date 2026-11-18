package com.solverin.engine.pipeline

import com.solverin.engine.core.Card
import com.solverin.engine.core.Position
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopAction
import com.solverin.engine.flop.FlopAnalysis
import com.solverin.engine.flop.FlopContext
import com.solverin.engine.flop.FlopEquity
import com.solverin.engine.flop.FlopSession
import com.solverin.engine.flop.FlopSpot
import com.solverin.engine.math.RakeModel
import com.solverin.engine.preflop.IsoRaiseInterceptor
import com.solverin.engine.preflop.PreflopContext
import com.solverin.engine.preflop.PreflopEngine
import com.solverin.engine.preflop.SpotAnalysis
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.river.RiverAnalysis
import com.solverin.engine.river.RiverContext
import com.solverin.engine.river.RiverLine
import com.solverin.engine.river.RiverSession
import com.solverin.engine.river.RiverShowdown
import com.solverin.engine.river.RiverSpot
import com.solverin.engine.river.TurnToRiverRangeFilter
import com.solverin.engine.turn.FlopLine
import com.solverin.engine.turn.FlopToTurnRangeFilter
import com.solverin.engine.turn.TurnAnalysis
import com.solverin.engine.turn.TurnContext
import com.solverin.engine.turn.TurnSession
import com.solverin.engine.turn.TurnSpot

/**
 * Deterministic full-hand orchestrator PREFLOP → FLOP → TURN → RIVER (early end on FOLD / ALL_IN_RUNOUT).
 *
 * Ranges are never injected: they start from the solved preflop node ([PreflopEngine]), get exact card removal of the
 * flop, then flow through [FlopToTurnRangeFilter] (w_turn = w_flop · P(flop action | c)) and [TurnToRiverRangeFilter]
 * (w_river = w_turn · P(turn action | c)), each removing the new board card. Off-tree actions that would empty a range
 * are smoothed by [BayesianFloor]. In 3-bet pots the postflop lines are propagated as condensed (RAISE_CALL /
 * TURN_RAISE_CALL) so the raise / jam resistance of the turn and river engines sees the narrow committed ranges.
 */
class HandPipelineEngine(
    private val preflopEngine: PreflopEngine = PreflopEngine(),
    private val flopStride: Int = FlopEquity.FAST_STRIDE,
    private val isoInterceptor: IsoRaiseInterceptor = IsoRaiseInterceptor(),
) {
    private class Ctx(val script: HandScript, val state: HandSessionState) {
        val positions = HashMap<Seat, Position>()
        var multiway = false
        var threeBetPot = false
        val heroCombo: Int = Combos.parse(script.heroHand)
        val reports = ArrayList<StreetReport>()
        var flopKind: StreetKind? = null
        var turnKind: StreetKind? = null
        /** Smoothing / off-tree flags of the last bridge, reported with the street that produced them. */
        var pendingSmoothed: Set<Seat> = emptySet()
        var pendingOff = false
    }

    fun play(script: HandScript): HandResult {
        val t0 = System.nanoTime()
        val pf = script.preflop
        val seats = if (pf.third != null) listOf(Seat.OPENER, Seat.RESPONDER, Seat.THIRD) else listOf(Seat.OPENER, Seat.RESPONDER)
        val c = Ctx(script, HandSessionState(seats, script.stackBb))
        c.positions[Seat.OPENER] = pf.opener; c.positions[Seat.RESPONDER] = pf.responder
        pf.third?.let { c.positions[Seat.THIRD] = it }
        c.multiway = pf.third != null
        c.threeBetPot = pf.line == PreflopLine.THREE_BET_CALL
        val flop = script.flop?.let { parseBoard(it) } ?: IntArray(0)
        val turn = script.turn?.let { Card.parse(it) }
        val river = script.river?.let { Card.parse(it) }
        val all = flop + listOfNotNull(turn, river) + intArrayOf(Combos.C1[c.heroCombo], Combos.C2[c.heroCombo])
        require(all.toSet().size == all.size) { "Duplicate cards between board and hero hand" }

        preflop(c)
        if (script.flop == null) {
            return HandResult(script, c.reports, HandStatus.IN_PROGRESS, c.state.potBb, c.state.seats.associateWith { c.state.stack(it) }, null,
                c.state.rakeAccruedBb, System.nanoTime() - t0)
        }
        if (c.state.status == HandStatus.IN_PROGRESS) flop(c, flop, turn)
        if (c.state.status == HandStatus.IN_PROGRESS && turn != null) turn(c, flop + turn, river)
        if (c.state.status == HandStatus.IN_PROGRESS && turn != null && river != null) river(c, flop + turn + river)
        var equity: Double? = null
        val st = c.state
        if (st.status == HandStatus.ALL_IN_RUNOUT || st.status == HandStatus.SHOWDOWN) {
            if (turn != null && river != null) equity = showdownEquity(c, flop + turn + river)
        }
        if (st.status == HandStatus.IN_PROGRESS) st.status = HandStatus.SHOWDOWN
        return HandResult(script, c.reports, st.status, st.potBb, st.seats.associateWith { st.stack(it) }, equity, st.rakeAccruedBb, System.nanoTime() - t0)
    }

    private fun parseBoard(s: String): IntArray = s.chunked(2).map { Card.parse(it) }.toIntArray()

    // ---- seats / positions ----------------------------------------------------------------------------------------

    private fun Ctx.pos(s: Seat) = positions.getValue(s)
    private fun Ctx.ip(a: Seat, b: Seat) = pos(a).isInPositionVs(pos(b))
    private fun Ctx.mainOpponent(s: Seat): Seat = when (s) {
        Seat.OPENER -> Seat.RESPONDER
        Seat.RESPONDER -> Seat.OPENER
        Seat.THIRD -> Seat.OPENER
    }
    private fun Ctx.extraOf(hero: Seat, villain: Seat): Seat? = if (!multiway) null else state.seats.first { it != hero && it != villain }
    private val Ctx.hero: Seat get() = script.heroSeat

    // ---- preflop ---------------------------------------------------------------------------------------------------

    private fun preflop(c: Ctx) {
        val t0 = System.nanoTime()
        val s = c.script; val pf = s.preflop; val st = c.state
        val ctx = PreflopContext(s.stake, effectiveStackBb = s.stackBb, locks = s.locks.preflop)
        // Opponents' ranges come from the population solve (no hero discipline); hero's own range and node from hero's.
        val villainCtx = ctx.copy(policy = ctx.policy.copy(heroDiscipline = false))
        val sz = ctx.sizing
        fun solve(key: SpotKey, seat: Seat? = null): SpotAnalysis {
            val use = if (seat == null || seat == c.hero) ctx else villainCtx
            require(preflopEngine.isValid(key, use)) { "Invalid preflop node $key" }
            return preflopEngine.analyze(key, use)
        }
        if (pf.line == PreflopLine.ISO_RAISE_CALL) { isoPreflop(c, t0); return }
        val rfi = solve(SpotKey.of(Scenario.RFI, pf.opener), Seat.OPENER)
        val defend = solve(SpotKey.of(Scenario.VS_RFI, pf.responder, pf.opener), Seat.RESPONDER)
        val weights = HashMap<Seat, FloatArray>()
        val heroNode: SpotAnalysis
        val size: Double
        when (pf.line) {
            PreflopLine.RFI_CALL -> {
                weights[Seat.OPENER] = rfi.strategy.raiseWeights()
                weights[Seat.RESPONDER] = FloatArray(com.solverin.engine.core.HandClass.COUNT) { defend.strategy.call(it) }
                size = sz.open(pf.opener)
                heroNode = if (c.hero == Seat.OPENER) rfi else defend
                st.initiative = Seat.OPENER
            }
            PreflopLine.THREE_BET_CALL -> {
                val vs3 = solve(SpotKey.of(Scenario.VS_3BET, pf.opener, pf.responder), Seat.OPENER)
                weights[Seat.OPENER] = FloatArray(com.solverin.engine.core.HandClass.COUNT) { rfi.strategy.raise(it) * vs3.strategy.call(it) }
                weights[Seat.RESPONDER] = defend.strategy.raiseWeights()
                size = sz.threeBet(pf.opener, pf.responder)
                heroNode = if (c.hero == Seat.OPENER) vs3 else defend
                st.initiative = Seat.RESPONDER
            }
            PreflopLine.ISO_RAISE_CALL -> error("handled by isoPreflop")
            PreflopLine.RFI_COLD_CALL_BB_CALL -> {
                val squeeze = solve(SpotKey.of(Scenario.SQUEEZE, Position.BB, pf.opener, pf.responder), Seat.THIRD)
                weights[Seat.OPENER] = rfi.strategy.raiseWeights()
                weights[Seat.RESPONDER] = FloatArray(com.solverin.engine.core.HandClass.COUNT) { defend.strategy.call(it) }
                weights[Seat.THIRD] = FloatArray(com.solverin.engine.core.HandClass.COUNT) { squeeze.strategy.call(it) }
                size = sz.open(pf.opener)
                heroNode = when (c.hero) { Seat.OPENER -> rfi; Seat.RESPONDER -> defend; Seat.THIRD -> squeeze }
                st.initiative = Seat.OPENER
            }
        }
        val inHand = st.seats.map { c.pos(it) }.toSet()
        st.deadBb = Position.SB.blindBb.takeIf { Position.SB !in inHand }.orZero() + Position.BB.blindBb.takeIf { Position.BB !in inHand }.orZero()
        for (seat in st.seats) {
            st.invest(seat, size)
            st.ranges[seat] = Combos.fromClassWeights(weights.getValue(seat))
        }
        st.rakeAccruedBb = RakeModel.rakeModel(ctx.rake, st.potBb, 0.0)
        val hc = Combos.HAND_CLASS[c.heroCombo]
        val d = heroNode.decision(hc)
        c.reports += StreetReport(
            Street.PREFLOP, IntArray(0), pf.line.name, st.potBb, st.effectiveStackBb, st.seats.associateWith { st.stack(it) },
            st.seats.associateWith { st.mass(it) }, emptySet(), heroOffTree = false,
            heroMix = mapOf("RAISE" to d.mix.raise, "CALL" to d.mix.call, "FOLD" to d.mix.fold),
            heroEv = buildMap { if (!d.evRaise.isNaN()) put("RAISE", d.evRaise); if (!d.evCall.isNaN()) put("CALL", d.evCall); put("FOLD", 0.0) },
            heroBetFraction = null, mdf = d.call?.mdf ?: d.raise?.mdf, rakeAccruedBb = st.rakeAccruedBb, latencyNanos = System.nanoTime() - t0,
            preflop = heroNode,
        )
    }

    private fun Double?.orZero() = this ?: 0.0

    /** Limped pot isolated by hero ([IsoRaiseInterceptor]): ranges and pot injected toward the flop. */
    private fun isoPreflop(c: Ctx, t0: Long) {
        val s = c.script; val pf = s.preflop; val st = c.state
        val iso = isoInterceptor.analyze(pf.opener, pf.limpers, s.stake, s.stackBb)
        val inHand = st.seats.map { c.pos(it) }.toSet()
        st.deadBb = (pf.limpers.size - 1) * 1.0 +
            Position.SB.blindBb.takeIf { Position.SB !in inHand }.orZero() + Position.BB.blindBb.takeIf { Position.BB !in inHand }.orZero()
        st.ranges[Seat.OPENER] = Combos.fromClassWeights(iso.isoRange)
        st.ranges[Seat.RESPONDER] = Combos.fromClassWeights(iso.callRange)
        for (seat in st.seats) st.invest(seat, iso.sizeBb)
        st.initiative = Seat.OPENER
        st.rakeAccruedBb = RakeModel.rakeModel(s.stake.defaultRake, st.potBb, 0.0)
        val hc = Combos.HAND_CLASS[c.heroCombo]
        val best = iso.decision(hc)
        c.reports += StreetReport(
            Street.PREFLOP, IntArray(0), "ISO_RAISE_CALL(${pf.limpers.size} limpers, %.1fbb)".format(iso.sizeBb), st.potBb, st.effectiveStackBb,
            st.seats.associateWith { st.stack(it) }, st.seats.associateWith { st.mass(it) }, emptySet(), heroOffTree = best != com.solverin.engine.preflop.IsoDecision.ISO_RAISE,
            heroMix = iso.ev(hc).keys.associate { it.name to if (it == best) 1f else 0f }, heroEv = iso.ev(hc).mapKeys { it.key.name },
            heroBetFraction = null, mdf = null, rakeAccruedBb = st.rakeAccruedBb, latencyNanos = System.nanoTime() - t0, iso = iso,
        )
    }

    // ---- card removal / smoothing -----------------------------------------------------------------------------------

    /** Applies [filtered] ranges to every seat with the Bayesian floor and hero's own-combo floor; returns smoothed seats. */
    private fun Ctx.update(next: Map<Seat, FloatArray>, board: IntArray): Pair<Set<Seat>, Boolean> {
        val smoothed = HashSet<Seat>()
        for ((seat, w) in next) {
            val (r, sm) = BayesianFloor.apply(w, state.ranges.getValue(seat), board)
            if (sm) smoothed += seat
            state.ranges[seat] = r
        }
        val heroRange = state.ranges.getValue(hero)
        val off = heroRange[heroCombo] < BayesianFloor.HERO_FLOOR
        if (off) heroRange[heroCombo] = BayesianFloor.HERO_FLOOR
        return smoothed to off
    }

    private fun Ctx.removeBoard(board: IntArray) {
        val dead = Combos.mask(board)
        for (seat in state.seats) {
            val w = state.ranges.getValue(seat)
            for (k in 0 until Combos.COUNT) if (!Combos.isLive(k, dead)) w[k] = 0f
        }
    }

    // ---- flop -----------------------------------------------------------------------------------------------------

    private fun flopLineOf(c: Ctx, kind: StreetKind): FlopLine = when (kind) {
        StreetKind.BET_CALL, StreetKind.BET_FOLD -> if (c.threeBetPot) FlopLine.RAISE_CALL else FlopLine.BET_CALL
        StreetKind.CHECK_CHECK -> FlopLine.CHECK_CHECK
        StreetKind.RAISE_CALL -> FlopLine.RAISE_CALL
        StreetKind.MULTIWAY_BET_CALL -> FlopLine.MULTIWAY_3WAY
    }

    private fun filterLine(kind: StreetKind): FlopLine = when (kind) {
        StreetKind.BET_CALL, StreetKind.BET_FOLD -> FlopLine.BET_CALL
        StreetKind.CHECK_CHECK -> FlopLine.CHECK_CHECK
        StreetKind.RAISE_CALL -> FlopLine.RAISE_CALL
        StreetKind.MULTIWAY_BET_CALL -> FlopLine.MULTIWAY_3WAY
    }

    private fun validateKind(c: Ctx, kind: StreetKind) {
        if (c.multiway) require(kind == StreetKind.MULTIWAY_BET_CALL || kind == StreetKind.BET_FOLD) { "3-way hands play MULTIWAY_BET_CALL (or BET_FOLD) on every street" }
        else require(kind != StreetKind.MULTIWAY_BET_CALL) { "MULTIWAY_BET_CALL needs a 3-way preflop line" }
    }

    private fun flopSpot(c: Ctx, me: Seat, villain: Seat, facing: Double): FlopSpot {
        val st = c.state
        val extra = c.extraOf(me, villain)
        return FlopSpot(st.board, st.ranges.getValue(me), st.ranges.getValue(villain), c.ip(me, villain), st.initiative == me,
            st.potBb, st.effectiveStackBb, facing, extra?.let { st.ranges.getValue(it) })
    }

    private fun flop(c: Ctx, flop: IntArray, turn: Int?) {
        val t0 = System.nanoTime()
        val st = c.state; val s = c.script
        st.street = Street.FLOP; st.board = flop
        c.removeBoard(flop)
        val (sm0, off0) = c.update(st.seats.associateWith { st.ranges.getValue(it) }, flop)
        val ctx = FlopContext(s.stake, locks = s.locks.flop)
        val action = s.flopAction
        val pot0 = st.potBb; val stacks0 = st.seats.associateWith { st.stack(it) }; val eff0 = st.effectiveStackBb
        val mass0 = st.seats.associateWith { st.mass(it) }
        if (action == null || eff0 <= 0.0) {
            val a = FlopSession(flopSpot(c, c.hero, c.mainOpponent(c.hero), 0.0), flopStride).analyze(ctx)
            c.reports += flopReport(c, a, "DECISION", pot0, eff0, stacks0, mass0, sm0, off0, null, t0)
            if (eff0 <= 0.0) st.status = HandStatus.ALL_IN_RUNOUT
            return
        }
        validateKind(c, action.kind)
        c.flopKind = action.kind
        val (perspective, villain) = streetActors(c, action)
        val spot = flopSpot(c, perspective, villain, 0.0)
        val session = FlopSession(spot, flopStride)
        val solved = session.analyze(ctx)
        val bet = action.betFraction?.let { minOf(it * pot0, eff0) } ?: solved.betSizeBb
        // Hero's own decision node.
        val heroAnalysis = when {
            c.hero == perspective -> solved
            action.kind == StreetKind.CHECK_CHECK -> FlopSession(flopSpot(c, c.hero, perspective, 0.0), flopStride).analyze(ctx)
            else -> FlopSession(flopSpot(c, c.hero, perspective, bet ?: 0.0), flopStride).analyze(ctx)
        }
        if (action.kind == StreetKind.BET_FOLD) {
            st.invest(perspective, bet ?: 0.0)
            st.status = HandStatus.FOLDED
            c.reports += flopReport(c, heroAnalysis, action.kind.name, pot0, eff0, stacks0, mass0, sm0, off0, bet, t0)
            return
        }
        if (turn == null) {
            c.reports += flopReport(c, heroAnalysis, action.kind.name, pot0, eff0, stacks0, mass0, sm0, off0, bet, t0)
            return
        }
        val tr = FlopToTurnRangeFilter.fromFlop(spot, ctx, filterLine(action.kind), turn, flopStride, bet, session)
        applyBridge(c, perspective, villain, pot0, eff0, tr.potBb, tr.effectiveStackBb,
            mapOf(perspective to tr.hero, villain to tr.villain) + (c.extraOf(perspective, villain)?.let { mapOf(it to tr.extra!!) } ?: emptyMap()),
            flop + turn, action, tr.heroIsAggressor)
        c.reports += flopReport(c, heroAnalysis, action.kind.name, pot0, eff0, stacks0, mass0, sm0, off0, bet, t0)
    }

    /** (spot perspective, its main villain) for a street action: the bettor, or the IP player after a check-check. */
    private fun streetActors(c: Ctx, a: StreetScript): Pair<Seat, Seat> {
        if (a.kind == StreetKind.CHECK_CHECK) {
            val x = Seat.OPENER; val y = Seat.RESPONDER
            return if (c.ip(x, y)) x to y else y to x
        }
        val b = a.bettor!!
        require(b in c.state.seats) { "Bettor $b is not in the hand" }
        return b to c.mainOpponent(b)
    }

    /** Pot / stack accounting of a Bayesian bridge (invariant-checked) and range update. */
    private fun applyBridge(
        c: Ctx, perspective: Seat, villain: Seat, pot0: Double, eff0: Double, newPot: Double, newEff: Double,
        next: Map<Seat, FloatArray>, board: IntArray, action: StreetScript, perspectiveAggressor: Boolean,
    ) {
        val st = c.state
        val callers = st.seats.size
        val each = (newPot - pot0) / callers
        check(each >= -1e-9 && each <= eff0 + 1e-6) { "Street investment $each outside [0, $eff0]" }
        for (seat in st.seats) st.invest(seat, each)
        check(kotlin.math.abs(st.potBb - newPot) < 1e-6) { "Pot accounting mismatch ${st.potBb} vs $newPot" }
        check(kotlin.math.abs(st.effectiveStackBb - newEff) < 1e-6) { "Stack accounting mismatch ${st.effectiveStackBb} vs $newEff" }
        st.initiative = when (action.kind) {
            StreetKind.CHECK_CHECK -> st.initiative
            StreetKind.RAISE_CALL -> if (perspectiveAggressor) perspective else villain
            else -> perspective
        }
        st.board = board
        val (smoothed, off) = c.update(next, board)
        c.pendingSmoothed = smoothed; c.pendingOff = off
        st.rakeAccruedBb = RakeModel.rakeModel(c.script.stake.defaultRake, st.potBb, 0.0)
        if (st.effectiveStackBb <= 1e-9) st.status = HandStatus.ALL_IN_RUNOUT
    }

    private fun flopReport(
        c: Ctx, a: FlopAnalysis, line: String, pot: Double, eff: Double, stacks: Map<Seat, Double>, mass: Map<Seat, Double>,
        smoothed: Set<Seat>, off: Boolean, bet: Double?, t0: Long,
    ): StreetReport {
        val h = c.heroCombo
        return StreetReport(
            Street.FLOP, c.state.board.copyOf(3), line, pot, eff, stacks, mass, smoothed + c.pendingSmoothed.also { c.pendingSmoothed = emptySet() },
            off || c.pendingOff.also { c.pendingOff = false },
            a.mix(h).mapKeys { it.key.name }, a.actions.filter { it != FlopAction.FOLD }.associate { it.name to a.ev(it, h) }.filterValues { it.isFinite() } + ("FOLD" to 0.0),
            a.betSizeFraction ?: bet?.let { it / pot }, a.mdf, c.state.rakeAccruedBb, System.nanoTime() - t0, flop = a,
        )
    }

    // ---- turn -----------------------------------------------------------------------------------------------------

    private fun turnSpot(c: Ctx, me: Seat, villain: Seat, facing: Double): TurnSpot {
        val st = c.state
        val extra = c.extraOf(me, villain)
        return TurnSpot(st.board, st.ranges.getValue(me), st.ranges.getValue(villain), c.ip(me, villain), st.initiative == me,
            flopLineOf(c, c.flopKind ?: StreetKind.CHECK_CHECK), st.potBb, st.effectiveStackBb, facing, extra?.let { st.ranges.getValue(it) },
            threeBetPot = c.threeBetPot)
    }

    private fun turn(c: Ctx, board: IntArray, river: Int?) {
        val t0 = System.nanoTime()
        val st = c.state; val s = c.script
        st.street = Street.TURN
        val ctx = TurnContext(s.stake, locks = s.locks.turn)
        val action = s.turnAction
        val pot0 = st.potBb; val stacks0 = st.seats.associateWith { st.stack(it) }; val eff0 = st.effectiveStackBb
        val mass0 = st.seats.associateWith { st.mass(it) }
        if (action == null) {
            val ses = TurnSession(turnSpot(c, c.hero, c.mainOpponent(c.hero), 0.0))
            c.reports += turnReport(c, ses.analyze(ctx), ses, ctx, "DECISION", pot0, eff0, stacks0, mass0, null, t0)
            return
        }
        validateKind(c, action.kind)
        c.turnKind = action.kind
        val (perspective, villain) = streetActors(c, action)
        val spot = turnSpot(c, perspective, villain, 0.0)
        val session = TurnSession(spot)
        val solved = session.analyze(ctx)
        val bet = action.betFraction?.let { minOf(it * pot0, eff0) } ?: solved.betSizeBb
        val (heroSession, heroAnalysis) = when {
            c.hero == perspective -> session to solved
            action.kind == StreetKind.CHECK_CHECK -> TurnSession(turnSpot(c, c.hero, perspective, 0.0)).let { it to it.analyze(ctx) }
            else -> TurnSession(turnSpot(c, c.hero, perspective, bet ?: 0.0)).let { it to it.analyze(ctx) }
        }
        if (action.kind == StreetKind.BET_FOLD) {
            st.invest(perspective, bet ?: 0.0)
            st.status = HandStatus.FOLDED
            c.reports += turnReport(c, heroAnalysis, heroSession, ctx, action.kind.name, pot0, eff0, stacks0, mass0, bet, t0)
            return
        }
        if (river == null) {
            c.reports += turnReport(c, heroAnalysis, heroSession, ctx, action.kind.name, pot0, eff0, stacks0, mass0, bet, t0)
            return
        }
        val rr = TurnToRiverRangeFilter.fromTurn(spot, ctx, riverFilterLine(action.kind), river, bet, session)
        applyBridge(c, perspective, villain, pot0, eff0, rr.potBb, rr.effectiveStackBb,
            mapOf(perspective to rr.hero, villain to rr.villain) + (c.extraOf(perspective, villain)?.let { mapOf(it to rr.extra!!) } ?: emptyMap()),
            board + river, action, rr.heroIsAggressor)
        c.reports += turnReport(c, heroAnalysis, heroSession, ctx, action.kind.name, pot0, eff0, stacks0, mass0, bet, t0)
    }

    private fun riverFilterLine(kind: StreetKind): RiverLine = when (kind) {
        StreetKind.BET_CALL, StreetKind.BET_FOLD -> RiverLine.BARREL_CALL
        StreetKind.CHECK_CHECK -> RiverLine.CHECK_CHECK_TURN
        StreetKind.RAISE_CALL -> RiverLine.TURN_RAISE_CALL
        StreetKind.MULTIWAY_BET_CALL -> RiverLine.MULTIWAY_3WAY
    }

    private fun turnReport(
        c: Ctx, a: TurnAnalysis, ses: TurnSession, ctx: TurnContext, line: String, pot: Double, eff: Double, stacks: Map<Seat, Double>,
        mass: Map<Seat, Double>, bet: Double?, t0: Long,
    ): StreetReport {
        val h = c.heroCombo
        return StreetReport(
            Street.TURN, ses.spot.board.copyOf(), line, pot, eff, stacks, mass, c.pendingSmoothed.also { c.pendingSmoothed = emptySet() },
            c.pendingOff.also { c.pendingOff = false },
            a.mix(h).mapKeys { it.key.name }, a.actions.filter { it != com.solverin.engine.turn.TurnAction.FOLD }.associate { it.name to a.ev(it, h) }.filterValues { it.isFinite() } + ("FOLD" to 0.0),
            a.betSizeFraction ?: bet?.let { it / pot }, a.mdf, c.state.rakeAccruedBb, System.nanoTime() - t0,
            turn = a, turnSession = ses, turnContext = ctx,
        )
    }

    // ---- river -----------------------------------------------------------------------------------------------------

    private fun riverLineOf(c: Ctx): RiverLine {
        val l = riverFilterLine(c.turnKind ?: StreetKind.CHECK_CHECK)
        return if (c.threeBetPot && l == RiverLine.BARREL_CALL) RiverLine.TURN_RAISE_CALL else l
    }

    private fun river(c: Ctx, board: IntArray) {
        val t0 = System.nanoTime()
        val st = c.state; val s = c.script
        st.street = Street.RIVER
        val ctx = RiverContext(s.stake, locks = s.locks.river)
        val pot0 = st.potBb; val eff0 = st.effectiveStackBb
        val mass0 = st.seats.associateWith { st.mass(it) }
        val d = s.riverDecision
        val villain = d.bettor ?: c.mainOpponent(c.hero)
        require(villain != c.hero && villain in st.seats) { "River bettor must be an opponent in the hand" }
        val extra = c.extraOf(c.hero, villain)
        val facing = d.villainBetFraction?.let { minOf(it * pot0, eff0) } ?: 0.0
        val spot = RiverSpot(board, st.ranges.getValue(c.hero), st.ranges.getValue(villain), c.ip(c.hero, villain), st.initiative == c.hero,
            riverLineOf(c), pot0, eff0, facing, extra?.let { st.ranges.getValue(it) }, threeBetPot = c.threeBetPot)
        val session = RiverSession(spot)
        val a = session.analyze(ctx)
        val h = c.heroCombo
        c.reports += StreetReport(
            Street.RIVER, board.copyOf(), if (facing > 0) "FACING_${"%.2f".format(d.villainBetFraction)}" else "HERO_ACTS", pot0, eff0,
            st.seats.associateWith { st.stack(it) }, mass0, emptySet(), false,
            a.mix(h).mapKeys { it.key.name }, a.actions.filter { it != com.solverin.engine.river.RiverAction.FOLD }.associate { it.name to a.ev(it, h) }.filterValues { it.isFinite() } + ("FOLD" to 0.0),
            a.betSize(h).takeIf { !it.isNaN() }, a.mdf, st.rakeAccruedBb, System.nanoTime() - t0,
            river = a, riverSession = session, riverContext = ctx,
        )
        st.status = HandStatus.SHOWDOWN
    }

    /** Hero's showdown equity on the complete board vs the main opponent's final range (R = 1, no implied / RIO). */
    private fun showdownEquity(c: Ctx, board: IntArray): Double {
        val sd = RiverShowdown(board)
        val w = c.state.ranges.getValue(c.mainOpponent(c.hero)).copyOf()
        val dead = Combos.mask(board)
        for (k in 0 until Combos.COUNT) if (!Combos.isLive(k, dead)) w[k] = 0f
        val e = sd.equity(c.heroCombo, w, sd.order(w))
        return if (e.isNaN()) 0.0 else e.toDouble()
    }
}
