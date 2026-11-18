package com.solverin.app.presentation

import com.solverin.engine.core.Card
import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.eval.RangeEquity
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopAction
import com.solverin.engine.flop.FlopPolicy
import com.solverin.engine.math.RakeModel
import com.solverin.engine.pipeline.StreetReport
import com.solverin.engine.river.RiverAction
import com.solverin.engine.river.RiverPolicy
import com.solverin.engine.turn.TurnAction
import com.solverin.engine.turn.TurnPolicy
import com.solverin.engine.pipeline.HandPipelineEngine
import com.solverin.engine.pipeline.HandScript
import com.solverin.engine.pipeline.PreflopAction
import com.solverin.engine.pipeline.PreflopLine
import com.solverin.engine.pipeline.RiverScript
import com.solverin.engine.pipeline.Seat
import com.solverin.engine.pipeline.StreetKind
import com.solverin.engine.pipeline.StreetScript
import com.solverin.engine.preflop.HandDecision
import com.solverin.engine.preflop.IsoDecision
import com.solverin.engine.preflop.IsoRaiseInterceptor
import com.solverin.engine.preflop.PreflopContext
import com.solverin.engine.preflop.PreflopEngine
import com.solverin.engine.range.RangeParser
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey

/** One hero option of the oracle: engine key, display label, solver frequency, exact EV (bb) and its table action. */
data class OracleOption(
    val key: String,
    val label: String,
    val frequency: Float,
    val evBb: Double,
    val kind: ActKind,
    val toAmount: Double,
)

/**
 * Solved-node mathematics behind hero's Max-EV action (inputs of [SpanishStrategyExplainer]). Null fields = the node
 * does not produce that term (e.g. no realization on the river, no ΔNuts preflop).
 */
data class NodeMath(
    val street: TableStreet,
    val potBb: Double,
    val effectiveStackBb: Double,
    val spr: Double,
    /** Raw equity of hero's hand vs the continuing range (0..1). */
    val equity: Double? = null,
    /** Equity realization factor R_real (preflop) / R_post (flop, turn). */
    val realization: Double? = null,
    val rangeAdvantage: Double? = null,
    val nutAdvantage: Double? = null,
    val mdf: Double? = null,
    val potOdds: Double? = null,
    /** Villain's real P(fold) vs hero's aggression. */
    val pFoldVillain: Double? = null,
    val blockerFactor: Double? = null,
    val bNut: Double? = null,
    val bFold: Double? = null,
    /** Rake charged in the chosen branch (bb). */
    val rakeBb: Double? = null,
    val rakeRate: Double = 0.0,
    val rakeCapBb: Double = 0.0,
    /** Aggression of this hand cut by the vulnerability brake / four-flush shield / bluff cap. */
    val aggressionCapped: Boolean = false,
    /** PostflopMassiveExploitOverride margin reached (aggressive Max-EV gap ≥ the street threshold). */
    val overrideActive: Boolean = false,
    val overrideThresholdBb: Double? = null,
    val geometricSize: Double? = null,
    val coldCallVerdict: String? = null,
    val bluffShare: Double? = null,
    val alpha: Double? = null,
)

/** Hero's decision at the current node; [approximate] = the live line was projected onto the closest engine line. */
data class OracleResult(
    val nodeLabel: String,
    val options: List<OracleOption>,
    val approximate: Boolean,
    val latencyMs: Double,
    val math: NodeMath? = null,
) {
    val best: OracleOption? get() = options.filter { it.evBb.isFinite() }.maxByOrNull { it.evBb }
}

/**
 * Projects the live 6-max table onto the engine:
 *  - preflop: the solved [PreflopEngine] node of hero's spot (RFI, vs open, squeeze, vs 3-bet, cold 4-bet, vs 4-bet /
 *    5-bet) or [IsoRaiseInterceptor] when players limped;
 *  - postflop: a [HandScript] (preflop line + one action script per closed street + hero's current node) solved by
 *    [HandPipelineEngine]. Lines the engine does not model exactly (4-bet pots, limped pots, 4+ way pots, 3-way
 *    check-arounds) are projected onto the closest engine line and flagged as approximate.
 */
class HeroOracle(
    private val preflop: PreflopEngine,
    private val pipeline: HandPipelineEngine,
    private val iso: IsoRaiseInterceptor,
) {
    private val rangeEquity = RangeEquity()
    private val strongRange = RangeParser.parse("TT+,AQs+,AKo")

    fun compute(s: FlowState, stake: Stake, profile: VillainProfilePreset): OracleResult {
        val t0 = System.nanoTime()
        val r = if (s.street == TableStreet.PREFLOP) {
            runCatching { preflopNode(s, stake, profile) }.getOrElse { potOddsFallback(s, "Preflop fallback", stake) }
        } else {
            runCatching { postflopNode(s, stake, profile, simplified = false) }
                .recoverCatching { postflopNode(s, stake, profile, simplified = true) }
                .getOrElse { potOddsFallback(s, "${s.street.name} fallback", stake) }
        }
        return r.copy(latencyMs = (System.nanoTime() - t0) / 1e6, math = r.math ?: baseMath(s, stake))
    }

    /** Pot / stack / rake terms common to every node. */
    private fun baseMath(s: FlowState, stake: Stake): NodeMath {
        val rake = stake.defaultRake
        val postflop = s.street != TableStreet.PREFLOP
        return NodeMath(
            street = s.street, potBb = s.potBb, effectiveStackBb = s.effectiveStackBb, spr = s.spr,
            rakeBb = if (postflop) RakeModel.rakeModel(rake, s.potBb, s.effectiveStackBb) else 0.0,
            rakeRate = rake.rate, rakeCapBb = rake.capBb,
        )
    }

    /** Last-resort node: call / check when the preflop class equity vs a strong range beats the pot odds. */
    private fun potOddsFallback(s: FlowState, label: String, stake: Stake): OracleResult {
        val hero = s.heroPosition!!
        val hc = HandClass.fromCards(s.heroCards[0], s.heroCards[1])
        val eq = rangeEquity.vs(hc, strongRange).toDouble()
        val toCall = s.toCall(hero)
        val callEv = eq * (s.potBb + toCall) - toCall
        val good = callEv > 0
        val toPot = if (toCall > 1e-9) toCall / (s.potBb + toCall) else null
        return OracleResult(label, passiveOptions(s, hero, if (good) 1f else 0f, callEv, if (good) 0f else 1f), true, 0.0,
            baseMath(s, stake).copy(equity = eq, potOdds = toPot))
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private fun heroHandString(s: FlowState): String = Card.toString(s.heroCards[0]) + Card.toString(s.heroCards[1])

    private fun cap(s: FlowState, hero: Position) = s.seat(hero).streetInvest + s.stackLeft(hero)

    /** Aggressive hero action at street total [to] (legalised: ≥ min-raise, all-in when it reaches the stack). */
    private fun raiseOption(s: FlowState, hero: Position, key: String, label: String, freq: Float, ev: Double, to: Double): OracleOption {
        val c = cap(s, hero)
        val target = maxOf(to, StreetFlowStateMachine.minRaiseTo(s))
        return if (target >= c - 1e-9) OracleOption(key, "$label · All-In ${StreetFlowStateMachine.fmt(c)}", freq, ev, ActKind.ALL_IN, c)
        else OracleOption(key, "$label ${StreetFlowStateMachine.fmt(StreetFlowStateMachine.round1(target))}", freq, ev,
            if (s.maxBet <= 0.0) ActKind.BET else ActKind.RAISE, StreetFlowStateMachine.round1(target))
    }

    private fun passiveOptions(s: FlowState, hero: Position, callFreq: Float, callEv: Double, foldFreq: Float): List<OracleOption> {
        val toCall = s.toCall(hero)
        return if (toCall <= 1e-9) listOf(OracleOption("CHECK", "Check", callFreq + foldFreq, callEv, ActKind.CHECK, s.seat(hero).streetInvest))
        else listOf(
            OracleOption("CALL", "Call ${StreetFlowStateMachine.fmt(minOf(s.maxBet, cap(s, hero)))}", callFreq, callEv, ActKind.CALL, minOf(s.maxBet, cap(s, hero))),
            OracleOption("FOLD", "Fold", foldFreq, 0.0, ActKind.FOLD, s.seat(hero).streetInvest),
        )
    }

    // ---- preflop -----------------------------------------------------------------------------------------------

    private fun preflopNode(s: FlowState, stake: Stake, profile: VillainProfilePreset): OracleResult {
        val hero = s.heroPosition!!
        val hc = HandClass.fromCards(s.heroCards[0], s.heroCards[1])
        val ctx = PreflopContext(stake, effectiveStackBb = s.stackBb, locks = profile.locks.preflop)
        val sz = ctx.sizing
        val recs = s.streetRecords(TableStreet.PREFLOP)
        val raises = recs.filter { it.aggressive }
        val limpers = recs.filter { it.kind == ActKind.LIMP && it.position != hero }.map { it.position }
        fun valid(k: SpotKey) = preflopEngine(k, ctx)
        fun fromDecision(label: String, scenarioName: String, d: HandDecision, raiseTo: Double, approx: Boolean = false): OracleResult {
            val out = ArrayList<OracleOption>()
            if (!d.evRaise.isNaN()) out += raiseOption(s, hero, "RAISE", label, d.mix.raise, d.evRaise, raiseTo)
            val toCall = s.toCall(hero)
            if (toCall > 1e-9) {
                if (!d.evCall.isNaN()) out += OracleOption("CALL", "Call ${StreetFlowStateMachine.fmt(s.maxBet)}", d.mix.call, d.evCall, ActKind.CALL, s.maxBet)
                out += OracleOption("FOLD", "Fold", d.mix.fold, 0.0, ActKind.FOLD, s.seat(hero).streetInvest)
            } else out += OracleOption("CHECK", "Check", d.mix.call + d.mix.fold, if (d.evCall.isNaN()) 0.0 else d.evCall, ActKind.CHECK, s.seat(hero).streetInvest)
            val bestKey = out.filter { it.evBb.isFinite() }.maxByOrNull { it.evBb }?.key
            val t = (if (bestKey == "RAISE") d.raise else d.call) ?: d.raise ?: d.call
            val aggr = d.raise
            val math = baseMath(s, stake).copy(
                equity = t?.equity?.toDouble(), realization = t?.realization?.toDouble(),
                mdf = aggr?.mdf, pFoldVillain = aggr?.mainFold, blockerFactor = aggr?.blockerFactor,
                potOdds = if (toCall > 1e-9 && s.maxBet > StreetFlowStateMachine.BB_BLIND + 1e-9) toCall / (s.potBb + toCall) else null,
                rakeBb = t?.rake ?: 0.0, coldCallVerdict = d.coldCall?.name,
            )
            return OracleResult(scenarioName, out, approx, 0.0, math)
        }
        fun analyze(k: SpotKey) = preflop.analyze(k, ctx).decision(hc)

        if (raises.isEmpty()) {
            if (limpers.isNotEmpty()) {
                val isoLimpers = limpers.filter { !it.isBlind && it.ordinal < hero.ordinal }
                val facing = s.toCall(hero) > 1e-9
                (if (isoLimpers.isEmpty()) null else runCatching { iso.analyze(hero, isoLimpers, stake, s.stackBb) }.getOrNull())?.let { a ->
                    val ev = a.ev(hc).filterKeys { d ->
                        when (d) { IsoDecision.FOLD, IsoDecision.OVER_LIMP -> facing; IsoDecision.CHECK_BB -> !facing; else -> true }
                    }
                    val best = ev.maxByOrNull { it.value }?.key
                    val out = ev.map { (d, v) ->
                        val f = if (d == best) 1f else 0f
                        when (d) {
                            IsoDecision.ISO_RAISE -> raiseOption(s, hero, "ISO_RAISE", "Iso-Raise", f, v, a.sizeBb)
                            IsoDecision.OVER_LIMP -> OracleOption("OVER_LIMP", "Over-Limp ${StreetFlowStateMachine.fmt(s.maxBet)}", f, v, ActKind.LIMP, s.maxBet)
                            IsoDecision.CHECK_BB -> OracleOption("CHECK_BB", "Check", f, v, ActKind.CHECK, s.seat(hero).streetInvest)
                            IsoDecision.FOLD -> OracleOption("FOLD", "Fold", f, v, ActKind.FOLD, s.seat(hero).streetInvest)
                        }
                    }
                    return OracleResult("ISO vs ${isoLimpers.size} limper(s)", out, false, 0.0,
                        baseMath(s, stake).copy(pFoldVillain = a.pFoldAll, equity = rangeEquity.vs(hc, a.callRange).toDouble()))
                }
            }
            if (hero == Position.BB) return OracleResult("BB option", passiveOptions(s, hero, 1f, 0.0, 0f), true, 0.0, baseMath(s, stake))
            val k = SpotKey.of(Scenario.RFI, hero)
            return fromDecision("Open", "RFI ${hero.short}", analyze(k), sz.open(hero))
        }
        val o = raises[0].position
        val heroRaises = raises.filter { it.position == hero }
        val callsAfter = { after: ActionRecord -> recs.drop(recs.indexOf(after) + 1).filter { it.kind == ActKind.CALL && it.position != hero } }
        val candidates = ArrayList<Triple<SpotKey, String, Double>>()
        when {
            raises.size == 1 -> {
                val callers = callsAfter(raises[0]).map { it.position }.filter { it != o }
                if (callers.isNotEmpty()) candidates += Triple(SpotKey.of(Scenario.SQUEEZE, hero, o, callers.first()), "Squeeze", s.maxBet * (4 + callers.size))
                candidates += Triple(SpotKey.of(Scenario.VS_RFI, hero, o), "3-Bet", s.maxBet * if (hero.isInPositionVs(o)) 3.0 else 4.0)
            }
            raises.size == 2 -> {
                val t = raises[1].position
                val callerBetween = callsAfter(raises[0]).firstOrNull { recs.indexOf(it) < recs.indexOf(raises[1]) }?.position
                when {
                    hero == o && callerBetween != null -> candidates += Triple(SpotKey.of(Scenario.VS_SQUEEZE_OPENER, hero, t, callerBetween), "4-Bet", s.maxBet * 2.25)
                    hero == o -> candidates += Triple(SpotKey.of(Scenario.VS_3BET, hero, t), "4-Bet", s.maxBet * 2.25)
                    recs.any { it.position == hero && it.kind == ActKind.CALL && it.raiseLevel == 1 } ->
                        candidates += Triple(SpotKey.of(Scenario.VS_SQUEEZE_CALLER, hero, t, o), "4-Bet", s.maxBet * 2.25)
                    else -> candidates += Triple(SpotKey.of(Scenario.VS_3BET_COLD, hero, t, o), "Cold 4-Bet", s.maxBet * 2.4)
                }
                candidates += Triple(SpotKey.of(Scenario.VS_3BET, hero, t), "4-Bet", s.maxBet * 2.25)
                candidates += Triple(SpotKey.of(Scenario.VS_RFI, hero, t), "Raise", s.maxBet * 2.5)
            }
            else -> {
                val last = raises.last().position
                if (heroRaises.size >= 2) candidates += Triple(SpotKey.of(Scenario.VS_5BET, hero, last), "Jam", cap(s, hero))
                if (heroRaises.isNotEmpty()) candidates += Triple(SpotKey.of(Scenario.VS_4BET, hero, last), "5-Bet Jam", cap(s, hero))
                candidates += Triple(SpotKey.of(Scenario.VS_3BET, hero, last), "Jam", cap(s, hero))
            }
        }
        candidates.forEachIndexed { i, (k, label, to) ->
            if (valid(k)) return fromDecision(label, "${k.scenario.name.replace('_', ' ')} ${hero.short}", analyze(k), to, approx = i > 0 || raises.size > 3)
        }
        // Outside the solved tree: pot-odds call vs a strong range.
        return potOddsFallback(s, "Pot odds vs ${raises.size}-raise pot", stake)
    }

    private fun preflopEngine(k: SpotKey, ctx: PreflopContext): Boolean = runCatching { preflop.isValid(k, ctx) }.getOrDefault(false)

    // ---- postflop ----------------------------------------------------------------------------------------------

    private data class Projection(val line: PreflopLine, val seats: Map<Position, Seat>, val opener: Position, val responder: Position,
                                  val limpers: List<Position>, val approximate: Boolean)

    private fun project(s: FlowState, stake: Stake, profile: VillainProfilePreset, simplified: Boolean): Projection {
        val hero = s.heroPosition!!
        val ctx = PreflopContext(stake, effectiveStackBb = s.stackBb, locks = profile.locks.preflop)
        fun v(k: SpotKey) = preflopEngine(k, ctx)
        fun rfiCall(o: Position, r: Position) = v(SpotKey.of(Scenario.RFI, o)) && v(SpotKey.of(Scenario.VS_RFI, r, o))
        fun threeBet(o: Position, r: Position) = rfiCall(o, r) && v(SpotKey.of(Scenario.VS_3BET, o, r))
        val pre = s.streetRecords(TableStreet.PREFLOP)
        val raisers = pre.filter { it.aggressive }.map { it.position }
        val limpers = pre.filter { it.kind == ActKind.LIMP }.map { it.position }
        val players = s.inHand.sortedBy { it.ordinal }
        if (simplified) {
            val villain = raisers.lastOrNull { it != hero && it in players } ?: players.first { it != hero }
            val (a, b) = listOf(hero, villain).sortedBy { it.ordinal }
            val (o, r) = if (rfiCall(a, b) || !rfiCall(b, a)) a to b else b to a
            return Projection(PreflopLine.RFI_CALL, mapOf(o to Seat.OPENER, r to Seat.RESPONDER), o, r, emptyList(), true)
        }
        var approx = players.size > 3
        val pair: List<Position>
        if (players.size == 3 && Position.BB in players && raisers.size == 1) {
            val raiser = raisers[0]
            val other = players.first { it != raiser && it != Position.BB }
            if (raiser != Position.BB && other.ordinal > raiser.ordinal && rfiCall(raiser, other) && v(SpotKey.of(Scenario.SQUEEZE, Position.BB, raiser, other)))
                return Projection(PreflopLine.RFI_COLD_CALL_BB_CALL, mapOf(raiser to Seat.OPENER, other to Seat.RESPONDER, Position.BB to Seat.THIRD), raiser, other, emptyList(), false)
        }
        if (players.size >= 3) {
            approx = true
            val villain = raisers.lastOrNull { it != hero && it in players } ?: players.first { it != hero }
            pair = listOf(hero, villain).sortedBy { it.ordinal }
        } else pair = players
        val (a, b) = pair
        val raisersIn = raisers.filter { it in pair }.distinct()
        // Isolated limper(s): hero isolated and one limper called.
        if (raisers.size == 1 && raisers[0] == hero && limpers.isNotEmpty()) {
            val caller = pair.first { it != hero }
            val isoLimpers = limpers.filter { !it.isBlind && it.ordinal < hero.ordinal }
            if (caller in isoLimpers) return Projection(PreflopLine.ISO_RAISE_CALL, mapOf(hero to Seat.OPENER, caller to Seat.RESPONDER), hero, caller, isoLimpers, approx)
        }
        when {
            raisers.size >= 2 && raisersIn.size == 2 && threeBet(raisersIn[0], raisersIn[1]) && (raisers.size == 2) ->
                return Projection(PreflopLine.THREE_BET_CALL, mapOf(raisersIn[0] to Seat.OPENER, raisersIn[1] to Seat.RESPONDER), raisersIn[0], raisersIn[1], emptyList(), approx)
            raisers.size >= 2 && threeBet(a, b) ->
                return Projection(PreflopLine.THREE_BET_CALL, mapOf(a to Seat.OPENER, b to Seat.RESPONDER), a, b, emptyList(), true)
            raisers.size == 1 && raisers[0] in pair -> {
                val raiser = raisers[0]; val other = pair.first { it != raiser }
                if (rfiCall(raiser, other)) return Projection(PreflopLine.RFI_CALL, mapOf(raiser to Seat.OPENER, other to Seat.RESPONDER), raiser, other, emptyList(), approx)
            }
        }
        val (o, r) = if (rfiCall(a, b)) a to b else b to a
        return Projection(PreflopLine.RFI_CALL, mapOf(o to Seat.OPENER, r to Seat.RESPONDER), o, r, emptyList(), true)
    }

    private fun streetScript(s: FlowState, street: TableStreet, p: Projection, simplified: Boolean): Pair<StreetScript, Boolean> {
        val multiway = p.seats.size == 3
        if (simplified) return StreetScript(StreetKind.CHECK_CHECK) to true
        val recs = s.streetRecords(street).filter { it.position in p.seats }
        val bets = recs.filter { it.aggressive }
        val pot = s.streetPotStart[street] ?: s.potStart
        if (bets.isEmpty()) {
            return if (multiway) StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.OPENER, 0.01) to true
            else StreetScript(StreetKind.CHECK_CHECK) to false
        }
        val first = bets.first()
        val bettor = p.seats.getValue(first.position)
        val fraction = (first.toAmount / pot).coerceAtLeast(0.01)
        return when {
            multiway -> StreetScript(StreetKind.MULTIWAY_BET_CALL, bettor, fraction) to (bets.size > 1)
            bets.size > 1 -> StreetScript(StreetKind.RAISE_CALL, bettor) to (bets.size > 2)
            else -> StreetScript(StreetKind.BET_CALL, bettor, fraction) to false
        }
    }

    private fun postflopNode(s: FlowState, stake: Stake, profile: VillainProfilePreset, simplified: Boolean): OracleResult {
        val hero = s.heroPosition!!
        val p = project(s, stake, profile, simplified)
        var approx = p.approximate
        val heroSeat = p.seats.getValue(hero)
        val board = s.board.map { Card.toString(it) }
        val flop = board.take(3).joinToString("")
        val toCall = s.toCall(hero)
        val facing = toCall > 1e-9
        val multiway = p.seats.size == 3
        val bettorPos = s.streetRecords(s.street).lastOrNull { it.aggressive && it.position in p.seats && it.position != hero }?.position
            ?: p.seats.keys.first { it != hero }
        val bettorSeat = p.seats.getValue(bettorPos)
        val fraction = if (facing) (toCall / (s.potBb - toCall).coerceAtLeast(1e-6)).coerceAtLeast(0.01) else 0.0
        val facingScript = if (!facing) null else if (multiway) StreetScript(StreetKind.MULTIWAY_BET_CALL, bettorSeat, fraction)
        else StreetScript(StreetKind.BET_CALL, bettorSeat, fraction)
        val (flopScript, fa) = if (s.street != TableStreet.FLOP) streetScript(s, TableStreet.FLOP, p, simplified) else (facingScript to false)
        val (turnScript, ta) = when (s.street) {
            TableStreet.TURN -> facingScript to false
            TableStreet.RIVER -> streetScript(s, TableStreet.TURN, p, simplified)
            else -> null to false
        }
        approx = approx || fa || ta
        val script = HandScript(
            stake, PreflopAction(p.line, p.opener, p.responder, p.limpers), heroSeat, heroHandString(s), flop,
            board.getOrNull(3), board.getOrNull(4), flopScript, turnScript,
            if (s.street == TableStreet.RIVER) RiverScript(if (facing) fraction else null, if (facing) bettorSeat else null) else RiverScript(),
            s.stackBb, profile.locks,
        )
        val result = pipeline.play(script)
        val rep = result.reports.last()
        val street = when (s.street) { TableStreet.FLOP -> com.solverin.engine.pipeline.Street.FLOP; TableStreet.TURN -> com.solverin.engine.pipeline.Street.TURN; else -> com.solverin.engine.pipeline.Street.RIVER }
        if (rep.street != street) approx = true
        val pot = s.potBb
        val out = ArrayList<OracleOption>()
        for (key in (rep.heroMix.keys + rep.heroEv.keys).distinct()) {
            val f = rep.heroMix[key] ?: 0f
            val ev = rep.heroEv[key] ?: Double.NaN
            if (!ev.isFinite()) continue
            when (key) {
                "FOLD" -> if (facing) out += OracleOption("FOLD", "Fold", f, ev, ActKind.FOLD, s.seat(hero).streetInvest)
                "CHECK" -> if (!facing) out += OracleOption("CHECK", "Check", f, ev, ActKind.CHECK, s.seat(hero).streetInvest)
                "CALL" -> if (facing) out += OracleOption("CALL", "Call ${StreetFlowStateMachine.fmt(minOf(s.maxBet, cap(s, hero)))}", f, ev, ActKind.CALL, minOf(s.maxBet, cap(s, hero)))
                "RAISE" -> if (facing) out += raiseOption(s, hero, "RAISE", "Raise", f, ev, s.maxBet * 3)
                else -> if (!facing) {
                    val frac = rep.heroBetFraction ?: 0.66
                    out += raiseOption(s, hero, key, "${key.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')} ${Math.round(frac * 100)}%", f, ev,
                        maxOf(StreetFlowStateMachine.BB_BLIND, frac * pot))
                }
            }
        }
        if (out.isEmpty()) out += passiveOptions(s, hero, 1f, 0.0, 0f)
        return OracleResult("${s.street.name} · ${p.line.name.replace('_', ' ')}${if (multiway) " · 3-way" else ""}", out, approx, 0.0,
            postflopMath(s, stake, rep, out))
    }

    /** Terms of the solved postflop node for hero's combo: equity, realization, ΔRange / ΔNuts, MDF, P(fold), blockers, rake. */
    private fun postflopMath(s: FlowState, stake: Stake, rep: StreetReport, out: List<OracleOption>): NodeMath {
        val h = Combos.index(s.heroCards[0], s.heroCards[1])
        val ranked = out.filter { it.evBb.isFinite() }.sortedByDescending { it.evBb }
        val best = ranked.firstOrNull()
        val subject = ranked.firstOrNull { it.kind != ActKind.FOLD }?.key
        val aggr = ranked.firstOrNull { it.kind == ActKind.BET || it.kind == ActKind.RAISE || it.kind == ActKind.ALL_IN }
        val passiveEv = ranked.filter { it.kind == ActKind.CHECK || it.kind == ActKind.CALL }.maxOfOrNull { it.evBb } ?: 0.0
        val base = baseMath(s, stake)
        val gap = if (best != null && aggr != null && best.key == aggr.key) aggr.evBb - maxOf(passiveEv, 0.0) else null
        rep.flop?.let { a ->
            val t = subject?.let { k -> runCatching { a.terms(FlopAction.valueOf(k), h) }.getOrNull() }
            val ta = aggr?.let { k -> runCatching { a.terms(FlopAction.valueOf(k.key), h) }.getOrNull() }
            val th = FlopPolicy().exploitOverrideBb
            return base.copy(
                equity = t?.equity?.toDouble(), realization = t?.realization?.toDouble(),
                rangeAdvantage = a.rangeAdvantage, nutAdvantage = a.nutAdvantage, mdf = a.mdf, potOdds = a.potOdds,
                pFoldVillain = ta?.pFold, rakeBb = t?.rake ?: base.rakeBb, aggressionCapped = a.capped(h),
                overrideActive = gap != null && gap >= th && (ta?.equity ?: 1f) < 0.5f, overrideThresholdBb = th,
                geometricSize = a.geometricSize.takeIf { it.isFinite() },
            )
        }
        rep.turn?.let { a ->
            val t = subject?.let { k -> runCatching { a.terms(TurnAction.valueOf(k), h) }.getOrNull() }
            val ta = aggr?.let { k -> runCatching { a.terms(TurnAction.valueOf(k.key), h) }.getOrNull() }
            val th = TurnPolicy().exploitOverrideBb
            return base.copy(
                equity = t?.equity?.toDouble(), realization = t?.realization?.toDouble(),
                rangeAdvantage = a.rangeAdvantage, nutAdvantage = a.nutAdvantage, mdf = a.mdf, potOdds = a.potOdds,
                pFoldVillain = ta?.pFold, rakeBb = t?.rake ?: base.rakeBb, aggressionCapped = a.capped(h),
                overrideActive = gap != null && gap >= th && (ta?.equity ?: 1f) < 0.5f, overrideThresholdBb = th,
                geometricSize = a.geometricSize.takeIf { it.isFinite() },
            )
        }
        rep.river?.let { a ->
            val t = subject?.let { k -> runCatching { a.terms(RiverAction.valueOf(k), h) }.getOrNull() }
            val ta = aggr?.let { k -> runCatching { a.terms(RiverAction.valueOf(k.key), h) }.getOrNull() }
            val pol = RiverPolicy()
            val th = minOf(pol.exploitOverrideBb, pol.exploitPotShare * s.potBb)
            return base.copy(
                equity = t?.equity?.toDouble(), realization = 1.0,
                rangeAdvantage = a.rangeAdvantage, nutAdvantage = a.nutAdvantage, mdf = a.mdf, potOdds = a.potOdds,
                pFoldVillain = ta?.pFold, bNut = (ta ?: t)?.bNut, bFold = (ta ?: t)?.bFold, rakeBb = t?.rake ?: base.rakeBb,
                aggressionCapped = a.capped(h), overrideThresholdBb = th,
                overrideActive = gap != null && gap >= th && (ta?.bNut ?: 1.0) < pol.exploitBlocker && (ta?.equity ?: 1f) < 0.5f,
                bluffShare = a.bluffShare.takeIf { it.isFinite() }, alpha = a.alpha.takeIf { it.isFinite() },
            )
        }
        return base.copy(mdf = rep.mdf)
    }
}
