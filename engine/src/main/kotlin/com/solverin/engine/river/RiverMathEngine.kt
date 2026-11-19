package com.solverin.engine.river

import com.solverin.engine.math.KeyMemo
import com.solverin.engine.math.IdentityMemo
import com.solverin.engine.math.MdfFloor
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.Draws
import com.solverin.engine.math.PipelineBufferPool
import com.solverin.engine.math.RakeModel

/**
 * Terminal river master equation (per hero combo and size s, exact card removal, R_river = 1, no implied / RIO):
 *
 *  EV(bet s) = P(Fold_V | s, B_nut, B_fold)·(Pot + Dead) + P(Call_V | s)·[EQ_SD(h, Range_V,call(s))·(Pot + 2s) − s − Rake]
 *            + P(Raise_V | s)·EV(ReraiseResponse: fold / call / jam)
 *  EV(call B) = EQ_SD(h, BetRange_V)·(Pot + 2B) − B − Rake;   EV(fold) = 0
 *  EV(check)  = P(check behind)·[EQ_SD·Pot − Rake] + P(villain bets)·max(fold, call, raise)
 *
 * Sub-equation 1 (elastic call range): DefenseShare(s) = clamp(Pot / (Pot + s) · Elasticity_V(s) · B_nut(h) / B_fold(h), 0.05, 0.98),
 * Elasticity_V(s) = (1 − Fold_V(s)) / MDF(s) · riverCallElasticity ratio; villain continues with the top DefenseShare of his
 * live range by showdown strength (value raises from the top, bluff raises from the bottom, calls in between).
 * Sub-equation 2 (thin value): see [showdownProtected]. Sub-equation 3 (bluff-catching): [bluffShare] vs α = B / (Pot + 2B).
 */
class RiverMathEngine(
    private val spot: RiverSpot,
    private val ctx: RiverContext,
    shared: Shared? = null,
) {
    class Shared(spot: RiverSpot) {
        val showdown = RiverShowdown(spot.board)
        private val dead = showdown.dead
        val hero = FloatArray(Combos.COUNT) { if (Combos.isLive(it, dead)) spot.heroRange[it] else 0f }
        val villain = FloatArray(Combos.COUNT) { if (Combos.isLive(it, dead)) spot.villainRange[it] else 0f }
        val extra: FloatArray? = spot.extraRange?.let { x -> FloatArray(Combos.COUNT) { if (Combos.isLive(it, dead)) x[it] else 0f } }
        val impact = RiverCardImpactEngine.classify(spot.turnBoard, spot.riverCard)
        /** Turn draw outs of every live combo. */
        val turnOuts = IntArray(Combos.COUNT) { k -> if (Combos.isLive(k, dead)) Draws.outs(spot.turnBoard, k) else 0 }
        /** Turn draws (≥ 4 outs) that end with at most one pair: villain's natural river bluffs. */
        val missedDraw = BooleanArray(Combos.COUNT) { k -> Combos.isLive(k, dead) && showdown.category(k) <= 1 && turnOuts[k] >= 4 }
        val heroIdx = (0 until Combos.COUNT).filter { hero[it] > 0f }.toIntArray()
        val villainVsHero: FloatArray = showdown.equities(villain, hero)
        val heroVsVillain: FloatArray = showdown.equities(hero, villain)
        val extraVsHero: FloatArray? = extra?.let { showdown.equities(it, hero) }
        val topStraightCard: Int = RiverCardImpactEngine.topStraightCard(spot.board)
    }

    private val s = shared ?: Shared(spot)
    private val p = ctx.policy
    val policy: RiverPolicy get() = p
    private val prof = ctx.profile
    private val stack = spot.effectiveStackBb
    private val pot = spot.potBb
    val showdown get() = s.showdown
    val hero get() = s.hero
    val villain get() = s.villain
    val extra get() = s.extra
    val impact get() = s.impact
    val heroIdx get() = s.heroIdx

    fun rake(finalPot: Double, eq: Double): Double = RakeModel.charge(ctx.rake, ctx.rakeAttribution, finalPot, 0.0, eq)

    // ---- range advantage -----------------------------------------------------------------------------------

    private fun share(range: FloatArray, eq: FloatArray, t: Float): Double {
        var n = 0.0; var d = 0.0
        for (k in range.indices) { val w = range[k]; if (w <= 0f || eq[k].isNaN()) continue; d += w; if (eq[k] >= t) n += w }
        return if (d > 0) n / d else 0.0
    }

    val heroNutShare: Double = share(hero, s.heroVsVillain, p.nutEquity)
    val villainNutShare: Double = share(villain, s.villainVsHero, p.nutEquity)
    /** ΔNuts_river. */
    val nutAdvantage: Double = heroNutShare - villainNutShare
    val villainCapped: Boolean = villainNutShare < p.cappedNutShare
    val rangeAdvantage: Double = run {
        var n = 0.0; var d = 0.0
        for (h in s.heroIdx) { val e = s.heroVsVillain[h]; if (e.isNaN()) continue; n += hero[h] * e; d += hero[h] }
        if (d > 0) n / d - 0.5 else 0.0
    }

    // ---- villain bet ranges --------------------------------------------------------------------------------

    /** Villain's bet / check split: [value] from the top, [bluff] non-value part, [check] the rest. */
    class BetPartition(val bet: FloatArray, val check: FloatArray, val value: FloatArray, val bluff: FloatArray) {
        val betTotal: Double = Combos.total(bet)
        val bluffShare: Double = if (betTotal > 0) Combos.total(bluff) / betTotal else 0.0
        val betIdx: IntArray = (0 until Combos.COUNT).filter { bet[it] > 0f }.toIntArray()
        val checkIdx: IntArray = (0 until Combos.COUNT).filter { check[it] > 0f }.toIntArray()
    }

    private val partitionCache = KeyMemo<Pair<Int, Double>, BetPartition>()

    private fun fill(rem: FloatArray, dst: FloatArray, budget: Double, order: List<Int>) {
        var left = budget
        for (k in order) {
            if (left <= 1e-9) return
            val a = rem[k]; if (a <= 0f) continue
            val take = minOf(a.toDouble(), left).toFloat()
            dst[k] += take; rem[k] = (rem[k] - take).coerceAtLeast(0f); left -= take
        }
    }

    private val villainIsAggressor: Boolean get() = !spot.heroIsAggressor

    /** Villain bet frequency at his decision (lead / barrel / delayed / probe / stab). */
    val villainBetFrequency: Double get() = when {
        spot.villainDonks -> ctx.locks[RiverVillainNode.DONK]?.frequency ?: prof.donk
        villainIsAggressor && spot.line == RiverLine.CHECK_CHECK_TURN -> ctx.locks[RiverVillainNode.BET]?.frequency ?: prof.delayedBet
        villainIsAggressor -> ctx.locks[RiverVillainNode.BET]?.frequency ?: prof.barrel
        !spot.heroInPosition -> ctx.locks[RiverVillainNode.BET]?.frequency ?: prof.stab
        else -> ctx.locks[RiverVillainNode.BET]?.frequency ?: prof.probe
    }

    /** Donk read of a villain lead of [size] pot fractions. */
    fun donkType(size: Double): RiverDonkType? = when {
        !spot.villainDonks -> null
        size <= p.weakDonkSize -> RiverDonkType.WEAK
        size >= p.polarDonkSize && impact.completing -> RiverDonkType.POLARIZED
        else -> null
    }

    /** Value share of villain's bet of [size]: lock, micro / weak / polarized lead, else profile. */
    fun villainValueShare(size: Double): Double {
        val lock = (if (spot.villainDonks) ctx.locks[RiverVillainNode.DONK] else ctx.locks[RiverVillainNode.BET])?.valueShare
        if (lock != null) return lock
        return when {
            size <= p.minBetRatio -> prof.microBetValueShare
            donkType(size) == RiverDonkType.WEAK -> p.weakDonkValueShare
            donkType(size) == RiverDonkType.POLARIZED -> p.polarDonkValueShare
            else -> prof.betValueShare
        }
    }

    private val villainOrder: List<Int> = s.showdown.order(villain).toList()

    /** Villain's first action with a bet of [size] (pot fractions). */
    fun villainBets(size: Double): BetPartition = partitionCache.get(0 to Math.round(size * 1e6) / 1e6) {
        val rem = villain.copyOf()
        val bet = FloatArray(Combos.COUNT); val value = FloatArray(Combos.COUNT); val bluff = FloatArray(Combos.COUNT)
        val total = Combos.total(villain)
        val f = villainBetFrequency.coerceIn(0.0, 1.0)
        val vs = villainValueShare(size).coerceIn(0.0, 1.0)
        // Block / weak bets: the value part comes from thin value (not his nuts, which bet big).
        val valueOrder = if (size <= p.weakDonkSize) {
            villainOrder.filter { s.villainVsHero[it] in 0.5f..p.blockerNutEquity } + villainOrder.filter { s.villainVsHero[it] !in 0.5f..p.blockerNutEquity }
        } else villainOrder
        fill(rem, value, total * f * vs, valueOrder)
        val weakest = villainOrder.reversed()
        // Natural bluffs: the biggest missed draws first (busted flush draws, then straight draws), then air.
        val missed = weakest.filter { s.missedDraw[it] }.sortedByDescending { s.turnOuts[it] } + weakest.filter { !s.missedDraw[it] }
        val nonValue = total * f * (1 - vs)
        if (size <= p.weakDonkSize) {
            val weakMade = weakest.filter { s.showdown.category(it) >= 1 }
            fill(rem, bluff, nonValue * p.weakBetMadeShare, weakMade)
            fill(rem, bluff, nonValue * (1 - p.weakBetMadeShare), missed)
        } else fill(rem, bluff, nonValue, missed)
        for (k in 0 until Combos.COUNT) bet[k] = value[k] + bluff[k]
        BetPartition(bet, rem, value, bluff)
    }

    /** Villain's range when hero acts: his whole range, or his checking range after he checked to hero. */
    val villainNodeRange: FloatArray by lazy {
        if (spot.nodeType == RiverNodeType.VS_CHECK) villainBets(prof.betSizeFraction).check else villain
    }
    private val nodeIdx: IntArray by lazy { s.showdown.order(villainNodeRange) }

    // ---- blockers -------------------------------------------------------------------------------------------

    private val blockerCache = IdentityMemo<RiverBlockerEngine>()
    fun blockers(range: FloatArray, extraPlayer: Boolean = false): RiverBlockerEngine = blockerCache.get(range) {
        RiverBlockerEngine(range, if (extraPlayer) s.extraVsHero!! else s.villainVsHero, p.blockerNutEquity, p.blockerFoldEquity)
    }

    // ---- villain responses to hero bets -----------------------------------------------------------------------

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    /**
     * Fold_V(s): linear from 0 at size 0 to foldToRiverBet at 66%, foldToRiverOverbet at 150%; beyond, scaled by the Nash
     * fold proportion s / (1 + s) (locks first), capped at MAX_FOLD.
     */
    fun villainFold(size: Double): Double {
        val lockBet = ctx.locks[RiverVillainNode.VS_BET]?.fold
        val lockOver = ctx.locks[RiverVillainNode.VS_OVERBET]?.fold
        val f66 = lockBet ?: prof.foldToRiverBet
        val f150 = lockOver ?: (if (lockBet != null) (lockBet + prof.foldToRiverOverbet - prof.foldToRiverBet).coerceIn(0.0, 0.98) else prof.foldToRiverOverbet)
        val v = when {
            size <= REF_SIZE -> f66 * size / REF_SIZE
            size <= OVER_SIZE -> lerp(f66, f150, (size - REF_SIZE) / (OVER_SIZE - REF_SIZE))
            else -> f150 * (size / (1 + size)) / (OVER_SIZE / (1 + OVER_SIZE))
        }
        return v.coerceIn(0.0, MAX_FOLD)
    }

    val elasticityRatio: Double get() = (ctx.locks[RiverVillainNode.RIVER_ELASTICITY]?.frequency ?: prof.riverCallElasticity) / prof.riverCallElasticity

    /** Range-level DefenseShare(s) (B_nut = B_fold = 1). */
    fun defenseShare(size: Double, bNut: Double = 1.0, bFold: Double = 1.0): Double {
        val mdf = 1 / (1 + size)
        val elasticity = (1 - villainFold(size)) / mdf * elasticityRatio
        // B_nut / B_fold with degenerate removal guarded (hero blocking a whole sub-range must not produce 0 / 0).
        val ratio = when {
            bFold <= 0.0 && bNut <= 0.0 -> 1.0
            bFold <= 0.0 -> 2.0
            else -> (bNut / bFold).let { if (it.isNaN()) 1.0 else it }
        }
        var d = (mdf * elasticity * ratio.coerceIn(0.5, 2.0)).coerceIn(0.05, 0.98)
        if (size <= p.minBetRatio) d = maxOf(d, 1 - prof.microBetMaxFold)
        return d
    }

    val betFoldLocked: Boolean get() = ctx.locks[RiverVillainNode.VS_BET]?.fold != null || ctx.locks[RiverVillainNode.VS_OVERBET]?.fold != null
    /** NIT node-lock read: no PostflopMassiveExploitOverride. */
    val nit: Boolean get() = ctx.locks.nitRead
    /** Maniac read: a locked river betting range that is bluff-heavy (value share ≤ MANIAC_VALUE_SHARE). */
    val maniac: Boolean get() = (ctx.locks[RiverVillainNode.BET]?.valueShare ?: 1.0) <= MANIAC_VALUE_SHARE
    /** Villain's live river range (combos). */
    val villainCombos: Double get() = Combos.total(s.villain)
    val station: Boolean get() = ctx.locks[RiverVillainNode.VS_BET]?.fold?.let { it <= p.stationFold } ?: false

    /** Villain's per-hand response to hero's bet: probabilities, equities vs the call / raise subsets. */
    class Response(
        val pFold: Double, val pCall: Double, val pRaise: Double,
        val eqCall: Float, val eqContinue: Float, val eqRaise: Float, val eqRaiseValue: Float,
        val raiseValueShare: Double, val defense: Double, val bNut: Double, val bFold: Double,
    )


    private fun responseOf(h: Int, range: FloatArray, idx: IntArray, size: Double, raiseFrac: Double, raiseValue: Double, blk: RiverBlockerEngine): Response {
        val m = Combos.MASK[h]; val sh = s.showdown.strength[h]
        var live = 0.0
        for (b in idx) if (m and Combos.MASK[b] == 0L) live += range[b]
        val bn = blk.nutBlocker(h); val bf = blk.foldBlocker(h)
        // Hero blocks the whole range: neutral answer (call, coin-flip equity), never a free fold.
        if (live <= 0.0) return Response(0.0, 1.0, 0.0, 0.5f, 0.5f, 0.5f, 0.5f, 1.0, 1.0, bn, bf)
        val def = defenseShare(size, bn, bf)
        val r = minOf(raiseFrac, def)
        var vrLeft = r * raiseValue * live
        var callLeft = (def - r) * live
        var brLeft = r * (1 - raiseValue) * live
        var cw = 0.0; var cn = 0.0; var rw = 0.0; var rn = 0.0; var vw = 0.0; var vn = 0.0
        // Pooled scratch (per-thread): reset to zero through the touched list before release.
        val used = PipelineBufferPool.acquireDoubles(Combos.COUNT)
        val touchedIdx = PipelineBufferPool.acquireInts(2 * Combos.COUNT)
        var touched = 0
        fun res(b: Int): Double { val t = s.showdown.strength[b]; return if (sh > t) 1.0 else if (sh == t) 0.5 else 0.0 }
        for (b in idx) {
            if (m and Combos.MASK[b] != 0L) continue
            var avail = range[b].toDouble()
            if (vrLeft > 1e-12) { val t = minOf(avail, vrLeft); vrLeft -= t; avail -= t; rw += t; rn += t * res(b); vw += t; vn += t * res(b); used[b] += t; touchedIdx[touched++] = b }
            if (avail > 1e-12 && callLeft > 1e-12) { val t = minOf(avail, callLeft); callLeft -= t; avail -= t; cw += t; cn += t * res(b); used[b] += t; touchedIdx[touched++] = b }
            if (vrLeft <= 1e-12 && callLeft <= 1e-12) break
        }
        for (i in idx.indices.reversed()) {
            if (brLeft <= 1e-12) break
            val b = idx[i]
            if (m and Combos.MASK[b] != 0L) continue
            val avail = range[b] - used[b]
            if (avail <= 1e-12) continue
            val t = minOf(avail, brLeft); brLeft -= t; rw += t; rn += t * res(b)
        }
        for (i in 0 until touched) used[touchedIdx[i]] = 0.0
        PipelineBufferPool.release(used); PipelineBufferPool.release(touchedIdx)
        val pc = cw / live; val pr = rw / live
        val eqC = if (cw > 0) (cn / cw).toFloat() else 0.5f
        val eqR = if (rw > 0) (rn / rw).toFloat() else 0.5f
        val eqV = if (vw > 0) (vn / vw).toFloat() else eqR
        val eqCont = if (cw + rw > 0) ((cn + rn) / (cw + rw)).toFloat() else 0.5f
        return Response((1 - pc - pr).coerceAtLeast(0.0), pc, pr, eqC, eqCont, eqR, eqV, if (rw > 0) vw / rw else 1.0, def, bn, bf)
    }

    fun respond(h: Int, size: Double): Response {
        val raise = ctx.locks[RiverVillainNode.VS_BET]?.raise ?: prof.raiseVsBet
        return responseOf(h, villainNodeRange, nodeIdx, size, raise, prof.raiseValueShare, blockers(villainNodeRange))
    }

    // ---- endogenous raise / jam resistance ------------------------------------------------------------------

    private val condensation: Double get() = when (spot.line) {
        RiverLine.CHECK_CHECK_TURN -> p.condensationCheckCheck
        RiverLine.BARREL_CALL -> p.condensationBarrelCall
        RiverLine.MULTIWAY_3WAY -> p.condensationMultiway
        RiverLine.TURN_RAISE_CALL -> p.condensationRaiseCall
    }

    private fun band(odds: Double): Double {
        val t = ((odds - p.committedOddsLow) / (p.committedOddsHigh - p.committedOddsLow)).coerceIn(0.0, 1.0)
        return p.committedFoldMin + (p.committedFoldMax - p.committedFoldMin) * t
    }

    /** Villain's fold vs hero's raise of his bet [bet] (lock first, else endogenous: condensation × price, committed band). */
    fun raiseFold(bet: Double): Double {
        ctx.locks[RiverVillainNode.VS_RAISE]?.fold?.let { return it }
        val to = raiseTo(bet, pot + bet)
        val call = (to - bet).coerceAtLeast(0.0)
        val odds = if (pot + bet + to + call > 0) call / (pot + bet + to + call) else 0.0
        val open = (prof.foldToRiverRaise * condensation * (odds / p.raiseFoldReferenceOdds).coerceIn(0.5, 1.2)).coerceIn(0.0, 0.95)
        val committed = bet >= p.committedBetSize * pot || spot.line == RiverLine.TURN_RAISE_CALL || stack / pot <= p.committedSpr
        return if (committed) minOf(band(odds), maxOf(open, p.committedFoldMin)) else open
    }

    /** Villain's fold vs hero's jam after he raised to [raisedTo] over hero's [heroBet]. */
    fun jamFold(heroBet: Double, raisedTo: Double): Double {
        ctx.locks[RiverVillainNode.VS_JAM]?.fold?.let { return it }
        val open = ((1 - prof.callJam) * condensation).coerceIn(0.0, 0.95)
        val call = (stack - raisedTo).coerceAtLeast(0.0)
        val odds = call / (pot + 2 * stack)
        val sprAfter = call / (pot + heroBet + raisedTo)
        val committed = sprAfter <= p.committedSpr || spot.line == RiverLine.TURN_RAISE_CALL
        return if (committed) minOf(band(odds), maxOf(open, p.committedFoldMin)) else open
    }

    fun isMinBet(bet: Double, potBefore: Double): Boolean = bet <= p.minBetRatio * potBefore || bet <= 1.0

    fun raiseTo(previous: Double, potWithBet: Double): Double {
        val base = p.raiseMultiplier * previous
        val weighted = if (isMinBet(previous, potWithBet - previous)) previous + p.raisePotFraction * (potWithBet + previous) else 0.0
        return minOf(stack, maxOf(base, weighted))
    }

    // ---- shields ------------------------------------------------------------------------------------------------

    val fourFlushSuit: Int get() = impact.fourFlushSuit
    val fourStraight: Boolean get() = impact.oneCardStraight
    val fourCardBoard: Boolean get() = p.fourCardShield && (fourFlushSuit >= 0 || fourStraight)

    private fun holdsSuitRank(h: Int, suit: Int, minRank: Int): Boolean {
        val a = Combos.C1[h]; val b = Combos.C2[h]
        return ((a and 3) == suit && (a ushr 2) >= minRank) || ((b and 3) == suit && (b ushr 2) >= minRank)
    }

    /** Holds the nut / second-nut blocker of the four-card board (A / K of the suit, or the top one-card straight rank). */
    fun shieldBlocker(h: Int): Boolean {
        if (fourFlushSuit >= 0) return holdsSuitRank(h, fourFlushSuit, 11)
        if (fourStraight) return (Combos.C1[h] ushr 2) == s.topStraightCard || (Combos.C2[h] ushr 2) == s.topStraightCard
        return true
    }

    /** Holds the completed flush (with a suit card) or straight of the four-card board. */
    fun shieldMade(h: Int): Boolean {
        val cat = s.showdown.category(h)
        if (fourFlushSuit >= 0) return cat >= 5 && holdsSuitRank(h, fourFlushSuit, 0)
        if (fourStraight) return cat >= 4
        return true
    }

    // ---- equities -------------------------------------------------------------------------------------------------

    /** Condensed range whose fold rate would otherwise be over-stated (3-bet pot, turn raise line, or a locked fold read). */
    val tightened: Boolean get() = spot.condensedVillain && (spot.threeBetPot || spot.line == RiverLine.TURN_RAISE_CALL || betFoldLocked)

    fun eqTotal(h: Int): Float = s.showdown.equity(h, villainNodeRange, nodeIdx).let { if (it.isNaN()) 0f else it }
    fun eqVs(h: Int, w: FloatArray, idx: IntArray): Float = s.showdown.equity(h, w, idx).let { if (it.isNaN()) 0f else it }

    /** Sub-equation 2: a medium showdown hand must not bet [size] when its EQ_SD vs the calling range is < thinValueEquity. */
    fun showdownProtected(h: Int, eqCall: Float): Boolean {
        val t = eqTotal(h)
        return t >= p.showdownLow && t <= p.showdownHigh && eqCall < p.thinValueEquity
    }

    fun fieldEquity(pairwise: List<Float>): Float {
        if (pairwise.size == 1) return pairwise[0]
        var heroShare = 1.0
        for (e in pairwise) heroShare *= e
        var others = 0.0
        for (e in pairwise) others += (1 - e) / pairwise.size
        val t = heroShare + others
        return if (t <= 0.0) 0f else (heroShare / t).toFloat()
    }

    // ---- hero bet --------------------------------------------------------------------------------------------------

    /** Hero's best answer to villain's raise to [y] over hero's [x]: fold (−x), call, or jam (3rd level). */
    class ReraiseResponse(val foldEv: Double, val callEv: Double, val jamEv: Double, val rawJamEv: Double, val jamEquity: Double, val raisedTo: Double) {
        val value: Double get() = maxOf(foldEv, callEv, jamEv)
        val best: RiverAction get() = when {
            jamEv >= callEv && jamEv >= foldEv && jamEv.isFinite() -> RiverAction.RAISE
            callEv >= foldEv -> RiverAction.CALL
            else -> RiverAction.FOLD
        }
        val stacksOff: Boolean get() = best == RiverAction.RAISE
    }

    fun reraiseResponse(h: Int, x: Double, r: Response): ReraiseResponse {
        val y = raiseTo(x, pot + x)
        val callEv = r.eqRaise * (pot + 2 * y) - y - rake(pot + 2 * y, r.eqRaise.toDouble())
        var jam = Double.NEGATIVE_INFINITY; var raw = Double.NEGATIVE_INFINITY; var ej = Double.NaN
        if (y < stack) {
            val pf = (1 - r.raiseValueShare) + r.raiseValueShare * jamFold(x, y)
            ej = r.eqRaiseValue.toDouble()
            raw = pf * (pot + y) + (1 - pf) * (ej * (pot + 2 * stack) - stack - rake(pot + 2 * stack, ej))
            jam = raw
            if (ej < 0.5 && jam < maxOf(-x, callEv) + p.stackOffBluffMarginBb) jam = Double.NEGATIVE_INFINITY
            if (fourCardBoard && !shieldMade(h)) jam = Double.NEGATIVE_INFINITY
        }
        return ReraiseResponse(-x, callEv, jam, raw, ej, y)
    }

    /** Hero bets [size] pot fractions (heads-up or into both players of a 3-way pot). */
    fun bet(h: Int, size: Double): RiverEvTerms {
        val x = minOf(size * pot, stack).let { if (it >= stack - 1e-9) stack else it }
        val r = respond(h, size)
        if (extra != null) return betMulti(h, x, r)
        val allIn = x >= stack
        // Condensed villain (called two streets / 3-bet pot): he folds less than his unconditional profile.
        val eqC = if (allIn) r.eqContinue else r.eqCall
        // Tightened condensed villain: bluffs (< 50% vs his calling range) get less fold equity; value keeps the profile.
        val persist = when {
            tightened -> p.condensedFoldScale
            spot.villainCalledTwoStreets -> p.persistenceTightening
            else -> 1.0
        }
        val pFold = if (eqC < 0.5f) r.pFold * persist else r.pFold
        var pc = r.pCall + (r.pFold - pFold); var pr = r.pRaise
        if (allIn) { pc += pr; pr = 0.0 }
        val fin = pot + 2 * x
        val rk = rake(fin, eqC.toDouble())
        val called = eqC * fin - x - rk
        val evR = if (pr > 0) reraiseResponse(h, x, r).value else 0.0
        return RiverEvTerms(pFold, pc, pr, pot, eqC, fin, x, rk, evR, called, allIn, pFold * pot + pc * called + pr * evR,
            x, r.defense, r.bNut, r.bFold)
    }

    private val extraNodeRange: FloatArray? by lazy {
        extra?.let { x -> if (spot.nodeType == RiverNodeType.VS_CHECK) extraBets.check else x }
    }
    private val extraIdx: IntArray by lazy { extraNodeRange?.let { s.showdown.order(it) } ?: IntArray(0) }

    /** Third player's stab / check split at profile size (he acts like a non-aggressor). */
    val extraBets: BetPartition by lazy {
        val x = extra!!
        val rem = x.copyOf()
        val bet = FloatArray(Combos.COUNT); val value = FloatArray(Combos.COUNT); val bluff = FloatArray(Combos.COUNT)
        val total = Combos.total(x)
        val order = s.showdown.order(x).toList()
        fill(rem, value, total * prof.stab * prof.betValueShare, order)
        val weakest = order.reversed()
        fill(rem, bluff, total * prof.stab * (1 - prof.betValueShare), weakest.filter { s.missedDraw[it] }.sortedByDescending { s.turnOuts[it] } + weakest.filter { !s.missedDraw[it] })
        for (k in 0 until Combos.COUNT) bet[k] = value[k] + bluff[k]
        BetPartition(bet, rem, value, bluff)
    }

    fun respondExtra(h: Int, size: Double): Response =
        responseOf(h, extraNodeRange!!, extraIdx, size, prof.raiseVsBet, prof.raiseValueShare, blockers(extraNodeRange!!, extraPlayer = true))

    private fun betMulti(h: Int, x: Double, v: Response): RiverEvTerms {
        val w = respondExtra(h, x / pot)
        val allIn = x >= stack
        val fv = v.pFold; var cv = v.pCall; var rv = v.pRaise
        val fx = w.pFold; var cx = w.pCall; var rx = w.pRaise
        if (allIn) { cv += rv; rv = 0.0; cx += rx; rx = 0.0 }
        val eqV = if (allIn) v.eqContinue else v.eqCall
        val eqX = if (allIn) w.eqContinue else w.eqCall
        val pf = fv * fx
        val pr = 1 - (1 - rv) * (1 - rx)
        val onlyV = cv * fx; val onlyX = fv * cx; val both = cv * cx
        val pc = onlyV + onlyX + both
        fun branch(eq: Float, fin: Double) = eq * fin - x - rake(fin, eq.toDouble())
        val bv = branch(eqV, pot + 2 * x); val bx = branch(eqX, pot + 2 * x)
        val eqB = fieldEquity(listOf(eqV, eqX)); val bb = branch(eqB, pot + 3 * x)
        val called = if (pc > 0) (onlyV * bv + onlyX * bx + both * bb) / pc else 0.0
        val wV = rv; val wX = (1 - rv) * rx
        val evR = if (pr > 0) (wV * (if (wV > 0) reraiseResponse(h, x, v).value else 0.0) + wX * (if (wX > 0) reraiseResponse(h, x, w).value else 0.0)) / pr else 0.0
        return RiverEvTerms(pf, pc, pr, pot, eqV, pot + 2 * x, x, rake(pot + 2 * x, eqV.toDouble()), evR, called, allIn,
            pf * pot + pc * called + pr * evR, x, v.defense, v.bNut, v.bFold)
    }

    // ---- hero facing a bet --------------------------------------------------------------------------------------

    /** Villain's bet range at a facing bet of [bet] bb. */
    fun facingPartition(bet: Double): BetPartition = villainBets(bet / pot)

    fun bluffShare(bet: Double): Double = facingPartition(bet).bluffShare

    /** Per-hand bluff share of villain's bet range after hero's card removal. */
    fun bluffShareFor(h: Int, bet: Double): Double {
        val part = facingPartition(bet); val m = Combos.MASK[h]
        var b = 0.0; var t = 0.0
        for (k in part.betIdx) { if (m and Combos.MASK[k] != 0L) continue; t += part.bet[k]; b += part.bluff[k] }
        return if (t > 0) b / t else 0.0
    }

    /** Nash bluff share α = B / (Pot + 2B) of a bet of [bet] bb into [potBefore]. */
    fun alpha(bet: Double, potBefore: Double = pot): Double = bet / (potBefore + 2 * bet)

    fun call(h: Int, bet: Double, potBefore: Double = pot): RiverEvTerms {
        val b = minOf(bet, stack)
        val part = villainBets(bet / potBefore)
        val eq = eqVs(h, part.bet, part.betIdx)
        val fin = potBefore + 2 * b
        val rk = rake(fin, eq.toDouble())
        val hu = eq * fin - b - rk
        val blk = blockers(part.bet)
        if (extra != null && spot.nodeType == RiverNodeType.VS_BET) {
            // Sandwich: the third player behind overcalls, squeezes (hero folds) or folds.
            val w = respondExtra(h, b / (potBefore + b))
            val eqMw = fieldEquity(listOf(eq, w.eqCall))
            val fin3 = potBefore + 3 * b
            val mw = eqMw * fin3 - b - rake(fin3, eqMw.toDouble())
            val pc = w.pFold + w.pCall
            val called = if (pc > 0) (w.pFold * hu + w.pCall * mw) / pc else 0.0
            // Drawing dead vs the bettor (who stays in every branch): the call is lost whatever the third player does.
            if (eq < MdfFloor.MIN_LIVE_EQUITY) return RiverEvTerms(0.0, 1.0, 0.0, 0.0, eq, fin, b, 0.0, -b, -b, b >= stack, -b,
                b, Double.NaN, blk.nutBlocker(h), blk.foldBlocker(h))
            // Squeeze behind: hero folds (−b) or calls the squeeze all-in; the bettor stays in, so hero needs equity vs
            // both the bettor's range and the squeezer's raise range (3-way all-in pot).
            val eqSq = fieldEquity(listOf(eq, w.eqRaise))
            val finSq = potBefore + 3 * stack
            val sq = if (stack > b) eqSq * finSq - stack - rake(finSq, eqSq.toDouble()) else Double.NEGATIVE_INFINITY
            val evR = maxOf(-b, sq)
            return RiverEvTerms(0.0, pc, w.pRaise, 0.0, eq, fin, b, rk, evR, called, b >= stack, pc * called + w.pRaise * evR,
                b, Double.NaN, blk.nutBlocker(h), blk.foldBlocker(h))
        }
        return RiverEvTerms(0.0, 1.0, 0.0, 0.0, eq, fin, b, rk, 0.0, hu, b >= stack, hu, b, Double.NaN, blk.nutBlocker(h), blk.foldBlocker(h))
    }

    /** Hero raises villain's bet: he folds the bottom (raiseFold), jams the top (reraise), calls the rest. */
    fun raise(h: Int, bet: Double, potBefore: Double = pot): RiverEvTerms? {
        if (bet >= stack || extra != null) return null
        val part = villainBets(bet / potBefore)
        val to = raiseTo(bet, potBefore + bet)
        val m = Combos.MASK[h]; val sh = s.showdown.strength[h]
        var live = 0.0
        for (k in part.betIdx) if (m and Combos.MASK[k] == 0L) live += part.bet[k]
        if (live <= 0.0) return null
        val f = raiseFold(bet)
        val rr = minOf(ctx.locks[RiverVillainNode.VS_RAISE]?.raise ?: prof.reraiseVsRaise, 1 - f)
        val order = s.showdown.order(part.bet)
        var jamLeft = rr * live; var callLeft = (1 - f - rr) * live
        var jw = 0.0; var jn = 0.0; var cw = 0.0; var cn = 0.0
        for (k in order) {
            if (m and Combos.MASK[k] != 0L) continue
            var a = part.bet[k].toDouble()
            val res = s.showdown.strength[k].let { t -> if (sh > t) 1.0 else if (sh == t) 0.5 else 0.0 }
            if (jamLeft > 1e-12) { val t = minOf(a, jamLeft); jamLeft -= t; a -= t; jw += t; jn += t * res }
            if (a > 1e-12 && callLeft > 1e-12) { val t = minOf(a, callLeft); callLeft -= t; cw += t; cn += t * res }
            if (jamLeft <= 1e-12 && callLeft <= 1e-12) break
        }
        val allIn = to >= stack
        var pc = cw / live; var pr = jw / live
        val pf = (1 - pc - pr).coerceAtLeast(0.0)
        val eqC = if (allIn) (if (cw + jw > 0) ((cn + jn) / (cw + jw)).toFloat() else 0.5f) else if (cw > 0) (cn / cw).toFloat() else 0.5f
        if (allIn) { pc += pr; pr = 0.0 }
        val fin = potBefore + 2 * to
        val rk = rake(fin, eqC.toDouble())
        val called = eqC * fin - to - rk
        val ej = if (jw > 0) jn / jw else 0.5
        val vsJam = maxOf(-to, ej * (potBefore + 2 * stack) - stack - rake(potBefore + 2 * stack, ej))
        val evR = if (pr > 0) vsJam else 0.0
        val foldWin = potBefore + bet
        val blk = blockers(part.bet)
        return RiverEvTerms(pf, pc, pr, foldWin, eqC, fin, to, rk, evR, called, allIn, pf * foldWin + pc * called + pr * evR,
            to, Double.NaN, blk.nutBlocker(h), blk.foldBlocker(h))
    }

    // ---- check ------------------------------------------------------------------------------------------------------

    private val checkBehindIdx: IntArray by lazy { s.showdown.order(villainBets(prof.betSizeFraction).check) }

    /** Hero checks: villain (if he acts after) bets his first-action range at profile size; else showdown. */
    fun check(h: Int, villainActsAfter: Boolean): RiverEvTerms {
        val part = villainBets(prof.betSizeFraction)
        if (extra != null) return checkMulti(h, villainActsAfter, part)
        val checkRange = if (villainActsAfter) part.check else villainNodeRange
        val idx = if (villainActsAfter) checkBehindIdx else nodeIdx
        val eq = eqVs(h, checkRange, idx)
        val sd = eq * pot - rake(pot, eq.toDouble())
        if (!villainActsAfter || stack <= 0.0) return RiverEvTerms(0.0, 1.0, 0.0, 0.0, eq, pot, 0.0, rake(pot, eq.toDouble()), 0.0, sd, false, sd)
        val m = Combos.MASK[h]
        var b = 0.0; var t = 0.0
        for (k in part.betIdx) if (m and Combos.MASK[k] == 0L) b += part.bet[k]
        for (k in idx) if (m and Combos.MASK[k] == 0L) t += part.check[k]
        val pb = if (b + t > 0) b / (b + t) else 0.0
        val bet = minOf(prof.betSizeFraction * pot, stack)
        val best = if (pb > 0 && bet > 0.0) {
            val cl = call(h, bet)
            val rs = raise(h, bet)?.ev ?: Double.NEGATIVE_INFINITY
            val foldEv = if (isMinBet(bet, pot) && cl.equity >= bet / (pot + 2 * bet)) Double.NEGATIVE_INFINITY else 0.0
            maxOf(foldEv, cl.ev, rs)
        } else 0.0
        return RiverEvTerms(0.0, 1 - pb, pb, 0.0, eq, pot, 0.0, rake(pot, eq.toDouble()), best, sd, false, (1 - pb) * sd + pb * best)
    }

    private fun checkMulti(h: Int, opponentsActAfter: Boolean, part: BetPartition): RiverEvTerms {
        val xb = extraBets
        val vRange = if (opponentsActAfter) part.check else villainNodeRange
        val xRange = if (opponentsActAfter) xb.check else extraNodeRange!!
        val eqV = eqVs(h, vRange, s.showdown.order(vRange)); val eqX = eqVs(h, xRange, s.showdown.order(xRange))
        val eq = fieldEquity(listOf(eqV, eqX))
        val sd = eq * pot - rake(pot, eq.toDouble())
        if (!opponentsActAfter || stack <= 0.0) return RiverEvTerms(0.0, 1.0, 0.0, 0.0, eq, pot, 0.0, rake(pot, eq.toDouble()), 0.0, sd, false, sd)
        val m = Combos.MASK[h]
        fun prob(bp: BetPartition): Double {
            var b = 0.0; var t = 0.0
            for (k in 0 until Combos.COUNT) { if (m and Combos.MASK[k] != 0L) continue; b += bp.bet[k]; t += bp.bet[k] + bp.check[k] }
            return if (t > 0) b / t else 0.0
        }
        val pv = prob(part); val px = prob(xb)
        val bet = minOf(prof.betSizeFraction * pot, stack)
        fun minBetFold(e: Float) = if (isMinBet(bet, pot) && e >= bet / (pot + 2 * bet)) Double.NEGATIVE_INFINITY else 0.0
        val bestV = if (pv > 0 && bet > 0) call(h, bet).let { maxOf(minBetFold(it.equity), it.ev) } else 0.0
        val bestX = if (px > 0 && bet > 0) {
            val e = eqVs(h, xb.bet, xb.betIdx); val fin = pot + 2 * bet
            maxOf(minBetFold(e), e * fin - bet - rake(fin, e.toDouble()))
        } else 0.0
        val wV = pv; val wX = (1 - pv) * px
        val pb = wV + wX
        val best = if (pb > 0) (wV * bestV + wX * bestX) / pb else 0.0
        return RiverEvTerms(0.0, 1 - pb, pb, 0.0, eq, pot, 0.0, rake(pot, eq.toDouble()), best, sd, false, (1 - pb) * sd + pb * best)
    }

    companion object {
        const val MANIAC_VALUE_SHARE = 0.5
        const val REF_SIZE = 0.66
        const val OVER_SIZE = 1.5
        const val MAX_FOLD = 0.9
    }
}
