package com.solverin.engine.turn

import com.solverin.engine.flop.BoardStrength
import com.solverin.engine.flop.ComboPartition
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.Draws
import com.solverin.engine.flop.FlopEquity
import com.solverin.engine.flop.FlopNodeLock
import com.solverin.engine.flop.FlopNodeLocks
import com.solverin.engine.flop.FlopProfile
import com.solverin.engine.flop.FlopVillainModel
import com.solverin.engine.flop.FlopVillainNode
import com.solverin.engine.math.RakeModel
import java.util.IdentityHashMap

/**
 * Master turn EV equation — single judge (per hero combo, exact card removal, exact 46-card river projection):
 *
 *  EV(bet)  = P(fold)·(Pot + Dead) + P(call)·[EQ46·R_turn·PotTotal − Inv − Rake + EV_implied_river − Penalty_RIO]
 *           + P(raise)·EV(ReraiseResponse: fold / call / jam)
 *  EV(call) = EQ46·R_turn·PotTotal − Inv − Rake + EV_implied_river − Penalty_RIO
 *  EV(check)= P(check behind)·[EQ46·R_turn·Pot − Rake + implied − RIO] + P(villain bets)·max(fold, call, raise)
 *
 * Coupled sub-equations ([TurnRiverProjectionEngine]):
 *  EV_implied_river = |O_clean| / 46 · min(Stack_rem, Pot_after_turn · Sizing_river_opt) · P(Payoff | Elasticity, Pos)
 *  Penalty_RIO      = |O_dirty| / 46 · min(Stack_rem, Pot_after_turn · Sizing_villain_river) · P(HeroTrapped | EQ_river)
 *  LeverageFactor   = 1 + α_pos · max(0, ΔNuts_turn) · |S_bluff| / 46 · (2 − BlockerFactor_nuts(h)) · tanh(Stack_rem / Pot_after_turn)
 *  R_turn = PosFactor · Initiative · (1 − Outs_clean_villain / 46 · f(SPR)) · LeverageFactor (aggressor only),
 *           f(SPR) = villainOutsWeight · min(1, SPR / 2); SPR → 0 pulls R → 1; Stack_rem = 0 ⇒ R = 1, implied = RIO = 0.
 *  Hero's turn bets: P(fold)_eff = min(1, P(fold) · LeverageFactor), call / raise rescaled (probabilities sum to 1).
 */
class TurnMathEngine(
    private val spot: TurnSpot,
    private val ctx: TurnContext,
    private val equity: TurnEquityMatrix,
    shared: Shared? = null,
    private val extraEquity: TurnEquityMatrix? = null,
) {
    class Shared(spot: TurnSpot) {
        private val dead = Combos.mask(spot.board)
        val hero = FloatArray(Combos.COUNT) { if (Combos.isLive(it, dead)) spot.heroRange[it] else 0f }
        val villain = FloatArray(Combos.COUNT) { if (Combos.isLive(it, dead)) spot.villainRange[it] else 0f }
        val extra: FloatArray? = spot.extraRange?.let { x -> FloatArray(Combos.COUNT) { if (Combos.isLive(it, dead)) x[it] else 0f } }
        val impact = TurnCardImpactEngine.classify(spot.flop, spot.turnCard)
        val strength = BoardStrength(spot.board)
        val drawOuts = IntArray(Combos.COUNT) { if (Combos.isLive(it, dead)) Draws.outs(spot.board, it) else 0 }
    }

    private val s = shared ?: Shared(spot)
    private val p = ctx.policy
    private val stack = spot.effectiveStackBb

    val hero get() = s.hero
    val villain get() = s.villain
    val extra: FloatArray? get() = s.extra
    val impact get() = s.impact
    val strength get() = s.strength
    val drawOuts get() = s.drawOuts

    init {
        require(s.extra == null || extraEquity != null) { "3-way spot needs an extra equity provider" }
    }

    private val eqCache = IdentityHashMap<FloatArray, FlopEquity.Result>()
    private val extraEqCache = IdentityHashMap<FloatArray, FlopEquity.Result>()

    fun eqVs(range: FloatArray): FlopEquity.Result = eqCache.getOrPut(range) { equity.heroVs(range) }
    fun eqVsExtra(range: FloatArray): FlopEquity.Result = extraEqCache.getOrPut(range) { extraEquity!!.heroVs(range) }
    fun outsVs(range: FloatArray): TurnRiverProjectionEngine.Projection = equity.outs(range, riverContinueFraction)
    private fun outsVsExtra(range: FloatArray): TurnRiverProjectionEngine.Projection = extraEquity!!.outs(range, riverContinueFraction)

    val heroVsVillain: FlopEquity.Result = eqVs(villain)
    private val villainVsHero: FlopEquity.Result = equity.villainVs(hero)

    /** ΔRange_turn = hero range equity (exact over 46 rivers) − 50%. */
    val rangeAdvantage: Double = heroVsVillain.rangeEquity(hero) - 0.5
    val heroNutShare: Double = highEquityShare(hero, heroVsVillain, p.nutEquity)
    val villainNutShare: Double = highEquityShare(villain, villainVsHero, p.nutEquity)
    /** ΔNuts_turn = hero share of combos with > 85% equity − villain share. */
    val nutAdvantage: Double = heroNutShare - villainNutShare
    /** Villain holds (almost) no combos above nutEquity: overbet target. */
    val villainCapped: Boolean = villainNutShare < p.cappedNutShare

    private fun highEquityShare(range: FloatArray, eq: FlopEquity.Result, threshold: Double): Double {
        var t = 0.0; var x = 0.0
        for (k in range.indices) {
            val w = range[k]
            if (w <= 0f) continue
            val e = eq.equity(k)
            if (e.isNaN()) continue
            t += w
            if (e > threshold) x += w
        }
        return if (t > 0) x / t else 0.0
    }

    /** Villain combos with > nutEquity vs hero's range (blocker reference). */
    val villainNutRange: FloatArray = FloatArray(Combos.COUNT) { k ->
        val w = villain[k]
        if (w <= 0f) 0f else villainVsHero.equity(k).let { e -> if (!e.isNaN() && e > p.nutEquity) w else 0f }
    }
    private val nutTotal = Combos.total(villainNutRange)
    private val avgNutUnblocked: Double = run {
        if (nutTotal <= 0.0) return@run 1.0
        var n = 0.0; var d = 0.0
        for (k in hero.indices) { val w = hero[k]; if (w <= 0f) continue; n += w * Combos.remaining(k, villainNutRange, nutTotal); d += w }
        if (d > 0) n / d / nutTotal else 1.0
    }

    /** Blocker factor: villain nut combos left given hero's hand / range average (< 1 = hero blocks the nuts). */
    fun blockerFactor(h: Int): Double =
        if (nutTotal <= 0.0) 1.0 else Combos.remaining(h, villainNutRange, nutTotal) / nutTotal / avgNutUnblocked

    // ---- villain model (turn profile mapped onto the flop response machinery) ------------------------

    private fun mdf(f: Double) = 1 / (1 + f)

    /** Fold at the 66% reference → fold at the 50% reference used by [FlopVillainModel]. */
    private fun ref50(fold66: Double) = 1 - ((1 - fold66) * mdf(0.5) / mdf(REF_SIZE)).coerceIn(0.02, 0.98)

    private val prof = ctx.profile

    val responseFold: Double = when (spot.villainResponseNode) {
        TurnVillainNode.VS_SECOND_BARREL -> prof.foldToSecondBarrel
        TurnVillainNode.VS_DELAYED_CBET -> prof.foldToDelayedCbet
        TurnVillainNode.VS_PROBE -> prof.foldToProbe
        TurnVillainNode.VS_DONK -> prof.foldToDonk
        else -> prof.foldToBet
    }

    private val firstFrequency: Double = when (spot.villainBetNode) {
        TurnVillainNode.BARREL -> prof.barrel
        TurnVillainNode.DELAYED_CBET -> prof.delayedCbet
        TurnVillainNode.PROBE -> prof.probe
        TurnVillainNode.DONK -> prof.donk
        else -> prof.stab
    }

    /** Reference turn raise: over villain's facing bet, or over his profile-size bet in the check subtree. */
    private val referenceRaise: Pair<Double, Double> = run {
        val pot = spot.potBb
        val b = if (spot.nodeType == TurnNodeType.VS_BET) minOf(spot.facingBetBb, stack) else minOf(prof.betSizeFraction * pot, stack)
        b to raiseTo(b, pot + b)
    }

    /** Pot odds villain faces after hero's reference raise (call amount / final pot). */
    val raisePotOdds: Double = run {
        val (b, to) = referenceRaise
        val call = (to - b).coerceAtLeast(0.0)
        val fin = spot.potBb + b + to + call
        if (fin <= 0.0) 0.0 else call / fin
    }

    /**
     * Villain's fold vs a turn raise without a node lock: profile foldToRaise × range condensation of the entry line ×
     * price factor (pot odds / reference, [0.5, 1.2]); in RAISE_CALL pots or SPR ≤ committedSpr it contracts to the
     * committed band [committedFoldMin, committedFoldMax] by the jam pot odds, blending back to the condensed value by
     * SPR committedSpr + committedBlendSpr. A VS_TURN_RAISE lock replaces it (see [flopLocks]).
     */
    val raiseFoldEndogenous: Double = run {
        val condensation = when (spot.line) {
            FlopLine.CHECK_CHECK -> p.raiseFoldCondensationCheckCheck
            FlopLine.BET_CALL -> p.raiseFoldCondensationBetCall
            FlopLine.MULTIWAY_3WAY -> p.raiseFoldCondensationMultiway
            FlopLine.RAISE_CALL -> p.raiseFoldCondensationRaiseCall
        }
        val price = (raisePotOdds / p.raiseFoldReferenceOdds).coerceIn(0.5, 1.2)
        val open = (prof.foldToRaise * condensation * price).coerceIn(0.0, 0.95)
        val t = ((raisePotOdds - p.committedOddsLow) / (p.committedOddsHigh - p.committedOddsLow)).coerceIn(0.0, 1.0)
        val band = minOf(open, p.committedFoldMin + (p.committedFoldMax - p.committedFoldMin) * t)
        val spr = spot.effectiveStackBb / spot.potBb
        when {
            spot.line == FlopLine.RAISE_CALL || spr <= p.committedSpr -> band
            else -> band + (open - band) * ((spr - p.committedSpr) / p.committedBlendSpr).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Villain's fold vs hero's jam over his own turn raise without a VS_JAM lock: (1 − profile callJam) × condensation,
     * contracted to the committed band by the jam pot odds when his raise leaves him committed (remaining SPR ≤
     * committedSpr) or in RAISE_CALL pots. Reference: hero bets standardBet, villain raises, hero jams.
     */
    val jamFoldEndogenous: Double = run {
        val pot = spot.potBb
        val condensation = when (spot.line) {
            FlopLine.CHECK_CHECK -> p.raiseFoldCondensationCheckCheck
            FlopLine.BET_CALL -> p.raiseFoldCondensationBetCall
            FlopLine.MULTIWAY_3WAY -> p.raiseFoldCondensationMultiway
            FlopLine.RAISE_CALL -> p.raiseFoldCondensationRaiseCall
        }
        val open = ((1 - prof.callJam) * condensation).coerceIn(0.0, 0.95)
        val b = minOf(p.standardBet * pot, stack)
        val y = raiseTo(b, pot + b)
        if (y >= stack || stack <= 0.0) return@run open
        val odds = (stack - y) / (pot + 2 * stack)
        val t = ((odds - p.committedOddsLow) / (p.committedOddsHigh - p.committedOddsLow)).coerceIn(0.0, 1.0)
        val band = minOf(open, p.committedFoldMin + (p.committedFoldMax - p.committedFoldMin) * t)
        val sprAfter = (stack - y) / (pot + 2 * y)
        when {
            spot.line == FlopLine.RAISE_CALL || sprAfter <= p.committedSpr -> band
            else -> band + (open - band) * ((sprAfter - p.committedSpr) / p.committedBlendSpr).coerceIn(0.0, 1.0)
        }
    }

    /** Villain's effective fold vs hero's jam over his raise (lock first). */
    val jamFold: Double get() = ctx.locks[TurnVillainNode.VS_JAM]?.fold ?: jamFoldEndogenous

    /** Villain's effective fold vs hero's turn raise (lock first). */
    val raiseFold: Double get() = ctx.locks[TurnVillainNode.VS_TURN_RAISE]?.fold ?: raiseFoldEndogenous

    /** Turn donk read when villain leads into the flop aggressor; null otherwise. */
    val donkType: TurnDonkType? = if (spot.villainBetNode != TurnVillainNode.DONK) null else {
        val changing = impact.has(TurnCardCategory.FLUSH_COMPLETING) || impact.has(TurnCardCategory.STRAIGHT_COMPLETING) ||
            impact.has(TurnCardCategory.BOARD_PAIRING)
        if (changing && villainNutShare >= heroNutShare) TurnDonkType.POLARIZED else TurnDonkType.WEAK
    }

    private val donkValueShare: Double? = donkType?.let { if (it == TurnDonkType.POLARIZED) p.polarDonkValueShare else p.weakDonkValueShare }

    private val flopProfile = FlopProfile(
        foldToBet = ref50(responseFold), raiseVsBet = prof.raiseVsBet, cbet = firstFrequency, stabVsCheck = firstFrequency,
        donk = firstFrequency, foldToRaise = raiseFoldEndogenous, reraiseVsRaise = prof.reraiseVsRaise, callJam = 1 - jamFoldEndogenous,
        betValueShare = prof.betValueShare, raiseValueShare = prof.raiseValueShare, betSizeFraction = prof.betSizeFraction,
        foldToDonk = ref50(responseFold), microBetMaxFold = prof.microBetMaxFold,
    )

    private fun TurnNodeLock.toFlop() = FlopNodeLock(fold, raise, frequency, valueShare)

    private val flopLocks: FlopNodeLocks = run {
        var l = FlopNodeLocks.NONE
        ctx.locks[spot.villainResponseNode]?.let { l = l.with(FlopVillainNode.VS_BET, it.toFlop()).with(FlopVillainNode.VS_DONK, it.toFlop()) }
        ctx.locks[TurnVillainNode.VS_TURN_RAISE]?.let { l = l.with(FlopVillainNode.VS_RAISE, it.toFlop()) }
        ctx.locks[TurnVillainNode.VS_JAM]?.let { l = l.with(FlopVillainNode.VS_JAM, it.toFlop()) }
        ctx.locks[spot.villainBetNode]?.let { l = l.with(FlopVillainNode.STAB, it.toFlop()).with(FlopVillainNode.DONK, it.toFlop()) }
        l
    }

    /** True when the user locked villain's fold vs hero's turn bet (a read: bluff cap relaxes to 20×). */
    val responseFoldLocked: Boolean get() = ctx.locks[spot.villainResponseNode]?.fold != null
    val raiseFoldLocked: Boolean get() = ctx.locks[TurnVillainNode.VS_TURN_RAISE]?.fold != null
    /** NIT node-lock read: no PostflopMassiveExploitOverride. */
    val nit: Boolean get() = ctx.locks.nitRead
    /** Locked calling station vs hero's bet: pure bluffs → 0, thin value expands. */
    val station: Boolean get() = ctx.locks[spot.villainResponseNode]?.fold?.let { it <= p.stationFold } ?: false

    val riverElasticity: Double = ctx.locks[TurnVillainNode.RIVER_ELASTICITY]?.frequency ?: prof.riverCallElasticity

    /** Estimated share of villain's range continuing vs a river bet (dirty-out reference). */
    val riverContinueFraction: Double get() = riverElasticity.coerceIn(p.minRiverContinue, 1.0)

    private val villainLive = (0 until Combos.COUNT).filter { villain[it] > 0f }
    private fun safe(e: Float, d: Float) = if (e.isNaN()) d else e
    private val byStrength = villainLive.sortedByDescending { safe(villainVsHero.equity(it), 0f) }.toIntArray()
    private val byBluff = villainLive.sortedWith(
        compareByDescending<Int> { drawOuts[it] }.thenBy { safe(villainVsHero.equity(it), 1f) }
    ).toIntArray()
    private val byWeakMade = villainLive.sortedWith(
        compareByDescending<Int> { if (strength.category[it] >= 1 && !strength.inTop(it, p.strongFraction)) 1 else 0 }
            .thenBy { safe(villainVsHero.equity(it), 1f) }
    ).toIntArray()

    private val textureFactor = FlopVillainModel.textureFactor(rangeAdvantage, nutAdvantage, FlopVillainModel.VOLATILITY_REF)

    val villainModel = FlopVillainModel(byStrength, byBluff, flopProfile, flopLocks, textureFactor, byWeakMade, p.minBetRatio)

    private val firstNode = if (spot.villainBetNode == TurnVillainNode.DONK) FlopVillainNode.DONK else FlopVillainNode.STAB

    /** Villain's first action at this node (raise = betting range, fold = checking range). */
    val villainFirstAction: ComboPartition = villainModel.firstAction(villain, firstNode, donkValueShare)

    // ---- third player ------------------------------------------------------------------------------

    val extraModel: FlopVillainModel? = s.extra?.let { x ->
        val xVsHero = extraEquity!!.villainVs(hero)
        val live = (0 until Combos.COUNT).filter { x[it] > 0f }
        val so = live.sortedByDescending { safe(xVsHero.equity(it), 0f) }.toIntArray()
        val bo = live.sortedWith(compareByDescending<Int> { drawOuts[it] }.thenBy { safe(xVsHero.equity(it), 1f) }).toIntArray()
        FlopVillainModel(so, bo, flopProfile, flopLocks, textureFactor, bo, p.minBetRatio)
    }
    val extraFirstAction: ComboPartition? = extraModel?.firstAction(s.extra!!, FlopVillainNode.STAB)
    val extraActsAfterHero: Boolean get() = s.extra != null && spot.nodeType != TurnNodeType.VS_CHECK
    val extraNodeRange: FloatArray? = s.extra?.let { if (spot.nodeType == TurnNodeType.VS_CHECK) extraFirstAction!!.fold else it }

    fun fieldEquity(pairwise: List<Float>): Float {
        if (pairwise.size == 1) return pairwise[0]
        var heroShare = 1.0
        for (e in pairwise) heroShare *= e
        var others = 0.0
        for (e in pairwise) others += (1 - e) / pairwise.size
        val t = heroShare + others
        return if (t <= 0.0) 0f else (heroShare / t).toFloat()
    }

    // ---- sub-equations ------------------------------------------------------------------------------

    /** Sub-equation 3: future-threat leverage of combo [h] with [behind] chips left over a [pot] after the turn action. */
    fun leverage(h: Int, behind: Double, pot: Double): Double {
        if (behind <= 0.0 || pot <= 0.0) return 1.0
        val alpha = if (spot.heroInPosition) p.leverageAlphaIp else p.leverageAlphaOop
        val l = 1 + alpha * maxOf(0.0, nutAdvantage) * equity.projection.scareShare(h) * (2 - blockerFactor(h)) * Math.tanh(behind / pot)
        return l.coerceIn(1.0, p.maxLeverage)
    }

    /** R_turn for hero combo [h] vs a villain range projected as [o]. */
    fun realization(h: Int, o: TurnRiverProjectionEngine.Projection, aggressor: Boolean, behind: Double, pot: Double): Double {
        val spr = behind / pot
        val pos = if (spot.heroInPosition) p.posIp else p.posOop
        val init = if (aggressor) p.initiativeAggressor else p.initiativeCaller
        val f = p.villainOutsWeight * minOf(1.0, spr / 2)
        val lev = if (aggressor) leverage(h, behind, pot) else 1.0
        val r = pos * init * (1 - o.villainClean[h].toDouble() / TurnRiverProjectionEngine.UNSEEN_RIVERS * f) * lev
        val low = ((p.lowSpr - spr) / p.lowSpr).coerceIn(0.0, 1.0)
        return r + (1 - r) * low
    }

    /** P(Payoff_V | Elasticity_V, Pos). */
    val payoffProbability: Double get() = riverElasticity * if (spot.heroInPosition) 1.0 else p.payoffOop

    /** Sub-equation 2a: EV_implied_river. */
    fun implied(h: Int, o: TurnRiverProjectionEngine.Projection, pot: Double, behind: Double): Double {
        if (behind <= 0.0 || o.clean[h] == 0) return 0.0
        return o.clean[h].toDouble() / TurnRiverProjectionEngine.UNSEEN_RIVERS * minOf(behind, pot * p.riverValueBet) * payoffProbability
    }

    /** Sub-equation 2b: Penalty_RIO. */
    fun rio(h: Int, o: TurnRiverProjectionEngine.Projection, pot: Double, behind: Double): Double {
        if (behind <= 0.0 || o.dirty[h] == 0) return 0.0
        return o.dirty[h].toDouble() / TurnRiverProjectionEngine.UNSEEN_RIVERS * minOf(behind, pot * prof.betSizeFraction) * o.trapped[h]
    }

    /** Flush suit of a four-flush turn board, else −1. */
    val fourFlushSuit: Int = run { val c = IntArray(4); for (x in spot.board) c[x and 3]++; c.indexOfFirst { it >= 4 } }

    /** Four-flush shield: [h] holds no card of the flush suit → no bet / raise / stack-off. */
    fun shielded(h: Int): Boolean = p.fourFlushShield && fourFlushSuit >= 0 &&
        (Combos.C1[h] and 3) != fourFlushSuit && (Combos.C2[h] and 3) != fourFlushSuit

    fun isNut(h: Int): Boolean = safe(heroVsVillain.equity(h), 0f) > p.nutEquity
    fun isMinBet(bet: Double, potBefore: Double): Boolean = bet <= p.minBetRatio * potBefore || bet <= 1.0

    /** Suit with exactly three cards on the turn board (3-flush), else −1. */
    val threeFlushSuit: Int = run { val c = IntArray(4); for (x in spot.board) c[x and 3]++; c.indexOfFirst { it == 3 } }

    private val brakeTexture: Boolean = threeFlushSuit >= 0 || impact.has(TurnCardCategory.STRAIGHT_COMPLETING)

    private val madeShareCache = IdentityHashMap<FloatArray, Double>()

    /** Share of [range] holding a made straight or better (flushes included) on the turn board. */
    fun villainMadeShare(range: FloatArray): Double = madeShareCache.getOrPut(range) {
        var t = 0.0; var m = 0.0
        for (k in range.indices) { val w = range[k]; if (w <= 0f || strength.strength[k] < 0) continue; t += w; if (strength.category[k] >= 4) m += w }
        if (t > 0) m / t else 0.0
    }

    /**
     * ThreeFlushTurnBrake: on a 3-flush / straight-completing turn a vulnerable made hand (one pair .. trips, no card
     * of the flush suit, no straight, < semiBluffOuts draw) facing a range with ≥ brakeVillainMadeShare made
     * straights / flushes is braked: R_turn is penalized, no raise vs big bets, no overbet.
     */
    fun braked(h: Int, range: FloatArray): Boolean {
        if (!brakeTexture) return false
        val cat = strength.category[h]
        if (cat !in 1..3 || drawOuts[h] >= p.semiBluffOuts) return false
        if (threeFlushSuit >= 0 && ((Combos.C1[h] and 3) == threeFlushSuit || (Combos.C2[h] and 3) == threeFlushSuit)) return false
        return villainMadeShare(range) >= p.brakeVillainMadeShare
    }

    /** PostflopMassiveExploitOverride eligibility: nut blocker (BF < exploitBlocker) or ≥ semiBluffOuts clean outs vs [range]. */
    /**
     * Condensed range whose fold rate would otherwise be over-stated: 3-bet pot, RAISE_CALL, or a villain who called
     * hero's flop bet while his fold rate is node-locked (a locked read is unconditional, his surviving range is not).
     */
    val tightened: Boolean get() = spot.condensedVillain && (spot.threeBetPot || spot.line == FlopLine.RAISE_CALL || responseFoldLocked)

    /** Fold-rate multiplier on hero's bluffs / semi-bluffs: condensed + locked (0.65), called hero's flop bet (0.88), else 1. */
    val persistence: Double get() = when {
        tightened -> p.condensedFoldScale
        spot.condensedVillain -> p.persistenceTightening
        else -> 1.0
    }

    fun exploitEligible(h: Int, range: FloatArray): Boolean {
        val o = outsVs(range)
        return (nutTotal > 0.0 && blockerFactor(h) < p.exploitBlocker) || (o.behind(h) && o.clean[h] >= p.exploitOverrideOuts)
    }

    /** Semi-bluff priority: ≥ semiBluffOuts clean outs vs [range], or blocks villain's nut combos (BF < 1). */
    fun semiBluffPriority(h: Int, range: FloatArray): Boolean {
        val o = outsVs(range)
        return (o.behind(h) && o.clean[h] >= p.semiBluffOuts) || (nutTotal > 0.0 && blockerFactor(h) < 1.0) ||
            (equity.projection.scare[h] > 0 && leverage(h, stack, spot.potBb) > 1.0)
    }

    class Branch(
        val value: Double, val equity: Float, val realization: Float, val potTotal: Double, val investment: Double,
        val rake: Double, val implied: Double, val rio: Double, val allIn: Boolean,
    )

    fun called(h: Int, range: FloatArray, pot: Double, invest: Double, behindAfter: Double, aggressor: Boolean): Branch =
        calledMulti(h, range, null, pot, invest, behindAfter, aggressor)

    fun calledMulti(h: Int, vRange: FloatArray?, xRange: FloatArray?, pot: Double, invest: Double, behindAfter: Double, aggressor: Boolean): Branch {
        require(vRange != null || xRange != null)
        val pair = ArrayList<Float>(2)
        vRange?.let { pair += safe(eqVs(it).equity(h), 0f) }
        xRange?.let { pair += safe(eqVsExtra(it).equity(h), 0f) }
        val e = fieldEquity(pair)
        val opponents = pair.size
        val o = if (vRange != null) outsVs(vRange) else outsVsExtra(xRange!!)
        val main = vRange ?: xRange!!
        val behind = behindAfter.coerceAtLeast(0.0)
        if (behind <= p.commitSpr * pot) {
            val potAll = pot + behind * (1 + opponents)
            val inv = invest + behind
            val rk = RakeModel.charge(ctx.rake, ctx.rakeAttribution, potAll, 0.0, e.toDouble())
            return Branch(e * potAll - inv - rk, e, 1f, potAll, inv, rk, 0.0, 0.0, true)
        }
        var r = realization(h, o, aggressor, behind, pot)
        if (braked(h, main)) r *= 1 - p.brakeRealizationK * villainMadeShare(main)
        if (opponents > 1) r *= Math.pow(MULTIWAY_DECAY, (opponents - 1).toDouble())
        val eqR = minOf(e * r, MAX_EQR)
        val rk = RakeModel.charge(ctx.rake, ctx.rakeAttribution, pot, behind, eqR)
        var imp = implied(h, o, pot, behind)
        var rio = rio(h, o, pot, behind)
        if (opponents > 1) { imp *= MULTIWAY_DECAY; rio /= MULTIWAY_DECAY }
        // Hero keeps the option to fold on the river: the continuation (EQ·R·Pot + implied − RIO) is never below 0,
        // so a call never costs more than the chips it puts in (plus rake).
        rio = minOf(rio, maxOf(0.0, eqR * pot + imp))
        return Branch(eqR * pot - invest - rk + imp - rio, e, r.toFloat(), pot, invest, rk, imp, rio, false)
    }

    fun raiseTo(previousTotal: Double, potWithBet: Double): Double {
        val base = p.raiseMultiplier * previousTotal
        val potBeforeBet = potWithBet - previousTotal
        val weighted = if (isMinBet(previousTotal, potBeforeBet)) previousTotal + p.raisePotFraction * (potWithBet + previousTotal) else 0.0
        return minOf(stack, maxOf(base, weighted))
    }

    private fun terms(pf: Double, pc: Double, pr: Double, foldWin: Double, br: Branch, calledEv: Double, evR: Double, needsImplied: Boolean = false) =
        TurnEvTerms(
            pFold = pf, pCall = pc, pRaise = pr, foldWin = foldWin, equity = br.equity, realization = br.realization,
            potTotal = br.potTotal, investment = br.investment, rake = br.rake, impliedRiver = br.implied, rioPenalty = br.rio,
            evVsRaise = evR, calledEv = calledEv, allIn = br.allIn, ev = pf * foldWin + pc * calledEv + pr * evR,
            drawNeedsImplied = needsImplied,
        )

    /** Hero bets / raises to street total [raiseTo] (level 1 = bet: may jam over a raise; level 2 = raise). */
    fun aggressive(
        h: Int, potBefore: Double, raiseTo: Double, heroInv: Double, vInv: Double, resp: ComboPartition, level: Int,
        extraResp: ComboPartition? = null, xInv: Double = 0.0,
    ): TurnEvTerms {
        if (extraResp != null) return aggressiveMulti(h, potBefore, raiseTo, heroInv, vInv, resp, level, extraResp, xInv)
        val x = minOf(raiseTo, stack)
        val a = x - heroInv
        val pr0 = leveraged(h, resp.probabilities(h), level, potBefore + a + (x - vInv), stack - x)
        val callRange = if (x >= stack) resp.continueRange else resp.call
        val br = called(h, callRange, potBefore + a + (x - vInv), a, stack - x, aggressor = true)
        // Condensed villain (tightened): a bluff / semi-bluff (< 50% vs his calling range) gets less fold equity than his
        // unconditional profile says; value hands keep the profile (the extra callers would be weaker, never stronger).
        val pf = if (br.equity < 0.5f) pr0[0] * persistence else pr0[0]
        var pc = pr0[1] + (pr0[0] - pf); var pr = pr0[2]
        if (x >= stack) { pc += pr; pr = 0.0 }
        val evR = if (pr > 0) reraiseResponse(h, potBefore + a, x, vInv, a, resp.raise, level).value else 0.0
        return terms(pf, pc, pr, potBefore, br, br.value, evR)
    }

    private fun aggressiveMulti(
        h: Int, potBefore: Double, raiseTo: Double, heroInv: Double, vInv: Double, resp: ComboPartition, level: Int,
        extraResp: ComboPartition, xInv: Double,
    ): TurnEvTerms {
        val x = minOf(raiseTo, stack)
        val a = x - heroInv
        val v = leveraged(h, resp.probabilities(h), level, potBefore + a + (x - vInv), stack - x)
        val w = leveraged(h, extraResp.probabilities(h), level, potBefore + a + (x - xInv), stack - x)
        val fv = v[0]; var cv = v[1]; var rv = v[2]
        val fx = w[0]; var cx = w[1]; var rx = w[2]
        if (x >= stack) { cv += rv; rv = 0.0; cx += rx; rx = 0.0 }
        val pf = fv * fx
        val pr = 1 - (1 - rv) * (1 - rx)
        val onlyV = cv * fx; val onlyX = fv * cx; val both = cv * cx
        val pc = onlyV + onlyX + both
        val callV = if (x >= stack) resp.continueRange else resp.call
        val callX = if (x >= stack) extraResp.continueRange else extraResp.call
        val bV = calledMulti(h, callV, null, potBefore + a + (x - vInv), a, stack - x, aggressor = true)
        val bX = calledMulti(h, null, callX, potBefore + a + (x - xInv), a, stack - x, aggressor = true)
        val bB = calledMulti(h, callV, callX, potBefore + a + (x - vInv) + (x - xInv), a, stack - x, aggressor = true)
        val calledEv = if (pc > 0) (onlyV * bV.value + onlyX * bX.value + both * bB.value) / pc else 0.0
        val wV = rv; val wX = (1 - rv) * rx
        val evR = if (pr > 0) {
            val evV = if (wV > 0) reraiseResponse(h, potBefore + a, x, vInv, a, resp.raise, level).value else 0.0
            val evX = if (wX > 0) reraiseResponse(h, potBefore + a, x, xInv, a, extraResp.raise, level, extraModel!!, extra = true).value else 0.0
            (wV * evV + wX * evX) / pr
        } else 0.0
        return terms(pf, pc, pr, potBefore, bV, calledEv, evR)
    }

    /** Hero's turn bet (level 1): P(fold) · LeverageFactor, call / raise rescaled so the three still sum to 1. */
    private fun leveraged(h: Int, pr: DoubleArray, level: Int, potAfter: Double, behind: Double): DoubleArray {
        if (level != 1) return pr
        val l = leverage(h, behind, potAfter)
        if (l <= 1.0 || pr[0] >= 1.0) return pr
        val f = minOf(1.0, pr[0] * l)
        val k = (1 - f) / (1 - pr[0])
        return doubleArrayOf(f, pr[1] * k, pr[2] * k)
    }

    /** Hero's decision after a re-raise of hero's street total [x]: fold (−a), call, or (level 1) jam. */
    /**
     * [jamEquity]: hero's equity vs villain's calling range of the jam (NaN when no jam is possible);
     * [callAllowed] false when an all-in call is shielded (four-flush without a flush card).
     */
    class ReraiseResponse(
        val reraiseTo: Double, val toCall: Double, val call: Branch, val jamEv: Double, val invested: Double,
        val jamEquity: Double = Double.NaN, val rawJamEv: Double = Double.NEGATIVE_INFINITY, val callAllowed: Boolean = true,
    ) {
        val callEv: Double get() = if (callAllowed) call.value else Double.NEGATIVE_INFINITY
        val best: TurnAction get() = when {
            maxOf(callEv, jamEv) <= 0.0 -> TurnAction.FOLD
            jamEv > callEv -> TurnAction.RAISE
            else -> TurnAction.CALL
        }
        val stacksOff: Boolean get() = best == TurnAction.RAISE || (best == TurnAction.CALL && call.allIn)
        val value: Double get() = -invested + maxOf(0.0, callEv, jamEv)
    }

    fun reraiseResponse(
        h: Int, potAfterHero: Double, x: Double, vInv: Double, a: Double, raiseRange: FloatArray, level: Int,
        model: FlopVillainModel = villainModel, extra: Boolean = false,
    ): ReraiseResponse {
        val y = raiseTo(x, potAfterHero)
        val potR = potAfterHero + (y - vInv)
        val toCall = y - x
        val callBranch = if (extra) calledMulti(h, null, raiseRange, potR + toCall, toCall, stack - y, aggressor = false)
        else called(h, raiseRange, potR + toCall, toCall, stack - y, aggressor = false)
        var jamEv = Double.NEGATIVE_INFINITY
        var jamEq = Double.NaN
        var rawJam = Double.NEGATIVE_INFINITY
        if (level == 1 && y < stack) {
            val jr = model.respondToJam(raiseRange)
            val pr = jr.probabilities(h)
            val fin = potR + (stack - x) + (stack - y)
            val ej = safe((if (extra) eqVsExtra(jr.call) else eqVs(jr.call)).equity(h), 0f).toDouble()
            val rk = RakeModel.charge(ctx.rake, ctx.rakeAttribution, fin, 0.0, ej)
            jamEv = pr[0] * potR + (pr[1] + pr[2]) * (ej * fin - (stack - x) - rk)
            jamEq = ej; rawJam = jamEv
            if (ej < 0.5 && jamEv < maxOf(0.0, callBranch.value) + p.stackOffBluffMarginBb) jamEv = Double.NEGATIVE_INFINITY
            // A raise range is condensed: re-jamming over it needs stackOffEquity vs his calling range or a nut hand.
            if (ej < p.stackOffEquity && !isNut(h) && !station) jamEv = Double.NEGATIVE_INFINITY
        }
        val shield = shielded(h)
        if (shield) jamEv = Double.NEGATIVE_INFINITY
        return ReraiseResponse(y, toCall, callBranch, jamEv, a, jamEq, rawJam, callAllowed = !(shield && callBranch.allIn))
    }

    fun reraiseResponse(h: Int, potBefore: Double, raiseTo: Double, heroInv: Double, vInv: Double, resp: ComboPartition, level: Int): ReraiseResponse {
        val x = minOf(raiseTo, stack)
        val a = x - heroInv
        return reraiseResponse(h, potBefore + a, x, vInv, a, resp.raise, level)
    }

    /** Hero calls a bet: [potBefore] includes the bet; flags draws calling below direct pot odds. */
    fun call(h: Int, potBefore: Double, toCall: Double, betRange: FloatArray): TurnEvTerms {
        val c = minOf(toCall, stack)
        if (extraActsAfterHero && c > 0.0) return callSandwiched(h, potBefore, c, betRange)
        val br = called(h, betRange, potBefore + c, c, stack - c, aggressor = false)
        return terms(0.0, 1.0, 0.0, 0.0, br, br.value, 0.0, needsImplied(h, betRange, br, c, potBefore))
    }

    /** Draw below direct pot odds: EQ46 · R_turn < PotOdds_direct with river outs (call only if implied − RIO covers). */
    private fun needsImplied(h: Int, range: FloatArray, br: Branch, c: Double, potBefore: Double): Boolean {
        if (br.allIn || c <= 0.0) return false
        val o = outsVs(range)
        return o.behind(h) && o.clean[h] + o.dirty[h] > 0 && br.equity * br.realization < c / (potBefore + c)
    }

    /** Sandwich: the third player behind folds (heads-up), calls (3-way) or squeezes (hero gives up the call). */
    private fun callSandwiched(h: Int, potBefore: Double, c: Double, betRange: FloatArray): TurnEvTerms {
        val xResp = extraModel!!.respondToBet(extraNodeRange!!, potBefore, c)
        val xr = xResp.probabilities(h)
        val hu = called(h, betRange, potBefore + c, c, stack - c, aggressor = false)
        val mw = calledMulti(h, betRange, xResp.call, potBefore + 2 * c, c, stack - c, aggressor = false)
        val pc = xr[0] + xr[1]
        val calledEv = if (pc > 0) (xr[0] * hu.value + xr[1] * mw.value) / pc else 0.0
        // Squeeze behind: hero gives up the call, or (strong hands) calls the squeeze all-in vs the squeezer's range.
        val evR = if (xr[2] > 0 && stack > 0.0) maxOf(-c, calledMulti(h, null, xResp.raise, potBefore + 2 * stack, stack, 0.0, aggressor = false).value) else -c
        return terms(0.0, pc, xr[2], 0.0, hu, calledEv, evR, needsImplied(h, betRange, hu, c, potBefore))
    }

    fun raiseVsBet(h: Int, potBefore: Double, bet: Double, betRange: FloatArray): TurnEvTerms {
        val to = raiseTo(bet, potBefore)
        val xResp = if (extraActsAfterHero) extraModel!!.respondToBet(extraNodeRange!!, potBefore, minOf(to, stack)) else null
        return aggressive(h, potBefore, to, 0.0, bet, villainModel.respondToRaise(betRange), level = 2, extraResp = xResp)
    }

    /** Hero checks: villain (if he acts after) bets his first-action range at profile size, else check-through. */
    fun check(h: Int, pot: Double, villainActsAfter: Boolean): TurnEvTerms {
        if (extra != null) return checkMulti(h, pot, villainActsAfter)
        val through = called(h, villainFirstAction.fold, pot, 0.0, stack, aggressor = false)
        if (!villainActsAfter) return terms(0.0, 1.0, 0.0, 0.0, through, through.value, 0.0)
        val pb = villainFirstAction.probabilities(h)[2]
        val betRange = villainFirstAction.raise
        val b = minOf(prof.betSizeFraction * pot, stack)
        val best = if (pb > 0 && b > 0.0) {
            val cl = call(h, pot + b, b, betRange)
            val rs = if (b < stack) raiseVsBet(h, pot + b, b, betRange).ev else Double.NEGATIVE_INFINITY
            val foldEv = if (isMinBet(b, pot) && cl.equity >= b / (pot + 2 * b)) Double.NEGATIVE_INFINITY else 0.0
            maxOf(foldEv, cl.ev, rs)
        } else 0.0
        return terms(0.0, 1 - pb, pb, 0.0, through, through.value, best)
    }

    private fun checkMulti(h: Int, pot: Double, opponentsActAfter: Boolean): TurnEvTerms {
        val xCheck = if (opponentsActAfter) extraFirstAction!!.fold else extraNodeRange!!
        val through = calledMulti(h, villainFirstAction.fold, xCheck, pot, 0.0, stack, aggressor = false)
        if (!opponentsActAfter) return terms(0.0, 1.0, 0.0, 0.0, through, through.value, 0.0)
        val pv = villainFirstAction.probabilities(h)[2]
        val px = extraFirstAction!!.probabilities(h)[2]
        val b = minOf(prof.betSizeFraction * pot, stack)
        fun minBetFold(eq: Float) = if (isMinBet(b, pot) && eq >= b / (pot + 2 * b)) Double.NEGATIVE_INFINITY else 0.0
        val bestV = if (pv > 0 && b > 0.0) {
            val cl = call(h, pot + b, b, villainFirstAction.raise)
            val rs = if (b < stack) raiseVsBet(h, pot + b, b, villainFirstAction.raise).ev else Double.NEGATIVE_INFINITY
            maxOf(minBetFold(cl.equity), cl.ev, rs)
        } else 0.0
        val bestX = if (px > 0 && b > 0.0) {
            val cl = calledMulti(h, null, extraFirstAction.raise, pot + 2 * b, b, stack - b, aggressor = false)
            maxOf(minBetFold(cl.equity), cl.value)
        } else 0.0
        val wV = pv; val wX = (1 - pv) * px
        val pb = wV + wX
        val best = if (pb > 0) (wV * bestV + wX * bestX) / pb else 0.0
        return terms(0.0, 1 - pb, pb, 0.0, through, through.value, best)
    }

    companion object {
        const val MAX_EQR = 0.98
        const val MULTIWAY_DECAY = 0.88
        const val REF_SIZE = 0.66
    }
}
