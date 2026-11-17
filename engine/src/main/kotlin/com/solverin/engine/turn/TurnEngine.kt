package com.solverin.engine.turn

import com.solverin.engine.core.HandClass
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopEngine

/** Turn solver: one [TurnSession] per spot builds the exact 46-river equity matrix once; node locks re-solve in ms. */
class TurnEngine {
    fun session(spot: TurnSpot, policy: TurnPolicy = TurnPolicy()): TurnSession = TurnSession(spot, policy)
    fun analyze(spot: TurnSpot, ctx: TurnContext): TurnAnalysis = session(spot, ctx.policy).analyze(ctx)
}

class TurnSession(val spot: TurnSpot, policy: TurnPolicy = TurnPolicy()) {
    private val shared = TurnMathEngine.Shared(spot)

    init {
        require(shared.hero.any { it > 0f }) { "Hero range is empty on this board" }
        require(shared.villain.any { it > 0f }) { "Villain range is empty on this board" }
        require(shared.extra == null || shared.extra.any { it > 0f }) { "Extra range is empty on this board" }
    }

    val matrix: TurnEquityMatrix by lazy {
        TurnEquityMatrix(spot.board, shared.hero, shared.villain, policy)
    }
    val extraMatrix: TurnEquityMatrix? by lazy {
        shared.extra?.let { TurnEquityMatrix(spot.board, shared.hero, it, policy) }
    }

    fun math(ctx: TurnContext) = TurnMathEngine(spot, ctx, matrix, shared, extraMatrix)

    fun analyze(ctx: TurnContext): TurnAnalysis {
        val m = math(ctx)
        val p = ctx.policy
        val pot = spot.potBb
        val heroIdx = (0 until Combos.COUNT).filter { m.hero[it] > 0f }
        if (spot.nodeType == TurnNodeType.VS_BET) return facingBet(m, ctx, heroIdx)
        val villainActsAfter = spot.nodeType == TurnNodeType.FIRST_TO_ACT
        val betAction = spot.heroBetAction
        val facingRange = if (villainActsAfter) m.villain else m.villainFirstAction.fold
        val check = arrayOfNulls<TurnEvTerms>(Combos.COUNT)
        for (h in heroIdx) check[h] = m.check(h, pot, villainActsAfter)
        val spr = spot.effectiveStackBb / pot
        val elasticity = TurnSizingOptimizer.elasticity({ f -> m.villainModel.continueShare(pot, f * pot) }, p.smallBet)
        val geometric = TurnSizingOptimizer.geometricFraction(spr)
        val sizes = TurnSizingOptimizer.candidates(spr, m.nutAdvantage, m.villainCapped, p)
        fun base(freq: Map<TurnAction, FloatArray>, terms: Map<TurnAction, Array<TurnEvTerms?>>, size: Double?, bet: Double?,
                 capped: BooleanArray = BooleanArray(Combos.COUNT)) = TurnAnalysis(
            spot, m.impact, spot.nodeType, m.rangeAdvantage, m.nutAdvantage, m.villainCapped,
            m.heroVsVillain.rangeEquity(m.hero), size, bet, null, null, false, freq, terms, m.hero, capped,
            sizes, elasticity, geometric, null,
        )
        if (sizes.isEmpty()) {
            val fc = FloatArray(Combos.COUNT); for (h in heroIdx) fc[h] = 1f
            return base(mapOf(TurnAction.CHECK to fc), mapOf(TurnAction.CHECK to check), null, null)
        }
        var best: SizeResult? = null
        for (size in sizes) {
            val b = minOf(size * pot, spot.effectiveStackBb)
            val resp = m.villainModel.respondToBet(facingRange, pot, b)
            val xResp = m.extraModel?.respondToBet(m.extraNodeRange!!, pot, b)
            val bets = arrayOfNulls<TurnEvTerms>(Combos.COUNT)
            val fc = FloatArray(Combos.COUNT); val fb = FloatArray(Combos.COUNT)
            for (h in heroIdx) {
                val t = m.aggressive(h, pot, b, 0.0, 0.0, resp, level = 1, extraResp = xResp)
                bets[h] = t
                val mix = FlopEngine.mix(Double.NEGATIVE_INFINITY, check[h]!!.ev, t.ev, p.mixBandBb)
                fc[h] = mix[1]; fb[h] = mix[2]
            }
            val foldAll = resp.foldTotal / resp.priorTotal * (xResp?.let { it.foldTotal / it.priorTotal } ?: 1.0)
            val ratio = TurnBluffCap.ratio(false, b / pot, foldAll, m.villainModel.mdf(pot, b), m.responseFoldLocked, p)
            // A locked fold is a read: value is measured vs his whole range so an over-folder does not shrink it.
            val cont = if (m.responseFoldLocked) resp.prior else resp.continueRange
            val valueEq = if (m.station) p.thinValueEquity else 0.5f
            val capped = TurnBluffCap.apply(
                heroIdx, m.hero, ratio, m.station, fb, fc, null,
                aggEv = { bets[it]!!.ev }, passiveEv = { check[it]!!.ev },
                isValue = { m.eqVs(cont).equity(it) >= valueEq || m.isNut(it) },
                priority = { m.semiBluffPriority(it, cont) }, foldAllowed = { false },
            )
            var total = 0.0
            for (h in heroIdx) total += m.hero[h] * (fb[h] * bets[h]!!.ev + fc[h] * check[h]!!.ev)
            if (best == null || total > best.total) best = SizeResult(size, b, total, bets, fc, fb, capped)
        }
        val c = best!!
        return base(mapOf(TurnAction.CHECK to c.fc, betAction to c.fb), mapOf(TurnAction.CHECK to check, betAction to c.bets), c.size, c.bet, c.capped)
    }

    private class SizeResult(
        val size: Double, val bet: Double, val total: Double, val bets: Array<TurnEvTerms?>,
        val fc: FloatArray, val fb: FloatArray, val capped: BooleanArray,
    )

    private fun enforceMdf(heroIdx: List<Int>, w: FloatArray, maxFold: Double, fold: FloatArray, call: FloatArray, callEv: (Int) -> Double) {
        var total = 0.0; var folded = 0.0
        for (h in heroIdx) { total += w[h]; folded += w[h] * fold[h] }
        var excess = folded - maxFold * total
        if (total <= 0.0 || excess <= 1e-12) return
        for (h in heroIdx.filter { fold[it] > 0f }.sortedByDescending(callEv)) {
            if (excess <= 1e-12) break
            val cut = minOf(fold[h].toDouble() * w[h], excess)
            val moved = minOf(fold[h], (cut / w[h]).toFloat())
            fold[h] -= moved; call[h] += moved
            excess -= cut
        }
    }

    private fun facingBet(m: TurnMathEngine, ctx: TurnContext, heroIdx: List<Int>): TurnAnalysis {
        val p = ctx.policy
        val pot = spot.potBb
        val bet = minOf(spot.facingBetBb, spot.effectiveStackBb)
        val potBefore = pot + bet
        val betRange = m.villainFirstAction.raise
        val minBet = m.isMinBet(bet, pot)
        val call = arrayOfNulls<TurnEvTerms>(Combos.COUNT)
        val raise = arrayOfNulls<TurnEvTerms>(Combos.COUNT)
        val fFold = FloatArray(Combos.COUNT); val fCall = FloatArray(Combos.COUNT); val fRaise = FloatArray(Combos.COUNT)
        val potOdds = bet / (potBefore + bet)
        for (h in heroIdx) {
            val c = m.call(h, potBefore, bet, betRange)
            val r = if (bet < spot.effectiveStackBb) m.raiseVsBet(h, potBefore, bet, betRange) else null
            call[h] = c; raise[h] = r
            val foldAllowed = !(minBet && c.equity >= potOdds)
            // Max-exploit draw rule: below direct pot odds a call is authorised only if direct + implied − RIO > 0.
            val callEv = if (c.drawNeedsImplied && c.ev <= 0.0 && foldAllowed) Double.NEGATIVE_INFINITY else c.ev
            val mix = FlopEngine.mix(if (foldAllowed) 0.0 else Double.NEGATIVE_INFINITY, callEv, r?.ev ?: Double.NEGATIVE_INFINITY, p.mixBandBb)
            fFold[h] = mix[0]; fCall[h] = mix[1]; fRaise[h] = mix[2]
        }
        val raiseResp = m.villainModel.respondToRaise(betRange)
        val raiseSize = m.raiseTo(bet, potBefore) - bet
        val ratio = TurnBluffCap.ratio(true, raiseSize / potBefore, raiseResp.foldTotal / raiseResp.priorTotal,
            m.villainModel.mdf(potBefore, raiseSize), m.raiseFoldLocked, p)
        val cont = if (m.raiseFoldLocked) raiseResp.prior else raiseResp.continueRange
        val capped = TurnBluffCap.apply(
            heroIdx, m.hero, ratio, false, fRaise, fCall, fFold,
            aggEv = { raise[it]?.ev ?: Double.NEGATIVE_INFINITY }, passiveEv = { call[it]!!.ev },
            isValue = { m.eqVs(cont).equity(it) >= 0.5f || m.isNut(it) },
            priority = { m.semiBluffPriority(it, cont) },
            foldAllowed = { !(minBet && call[it]!!.equity >= potOdds) },
        )
        if (!spot.heroInPosition) {
            val connected = m.impact.has(TurnCardCategory.FLUSH_COMPLETING) || m.impact.has(TurnCardCategory.STRAIGHT_COMPLETING)
            TurnBluffCap.capTotal(
                heroIdx, m.hero, if (connected) p.checkRaiseCapConnected else p.checkRaiseCap, fRaise, fCall, fFold, capped,
                aggEv = { raise[it]?.ev ?: Double.NEGATIVE_INFINITY }, passiveEv = { call[it]!!.ev },
                foldAllowed = { !(minBet && call[it]!!.equity >= potOdds) },
            )
        }
        val mdf = pot / (pot + bet)
        val mdfFloor = if (minBet) 1 - mdf else null
        if (mdfFloor != null) enforceMdf(heroIdx, m.hero, mdfFloor, fFold, fCall) { call[it]!!.ev }
        return TurnAnalysis(
            spot, m.impact, TurnNodeType.VS_BET, m.rangeAdvantage, m.nutAdvantage, m.villainCapped,
            m.heroVsVillain.rangeEquity(m.hero), null, null, mdf, potOdds, minBet,
            mapOf(TurnAction.FOLD to fFold, TurnAction.CALL to fCall, TurnAction.RAISE to fRaise),
            mapOf(TurnAction.CALL to call, TurnAction.RAISE to raise), m.hero, capped,
            emptyList(), Double.NaN, TurnSizingOptimizer.geometricFraction(spot.effectiveStackBb / pot), mdfFloor,
        )
    }
}

/** Turn solve output (per combo; class-level aggregation for display). */
class TurnAnalysis(
    val spot: TurnSpot,
    val impact: TurnCardImpact,
    val nodeType: TurnNodeType,
    /** ΔRange_turn: hero range equity − 50% (exact over the 46 rivers). */
    val rangeAdvantage: Double,
    /** ΔNuts_turn: hero share of > 85% equity combos − villain share. */
    val nutAdvantage: Double,
    val villainCapped: Boolean,
    val heroEquity: Double,
    val betSizeFraction: Double?,
    val betSizeBb: Double?,
    val mdf: Double?,
    val potOdds: Double?,
    val minBet: Boolean,
    private val freq: Map<TurnAction, FloatArray>,
    private val terms: Map<TurnAction, Array<TurnEvTerms?>>,
    val heroRange: FloatArray,
    private val capped: BooleanArray,
    val sizeCandidates: List<Double>,
    val elasticity: Double,
    /** 2-street geometric pot fraction (turn + river all-in). */
    val geometricSize: Double,
    val maxFold: Double?,
) {
    fun capped(combo: Int): Boolean = capped[combo]
    val actions: List<TurnAction> get() = freq.keys.toList()
    /** Hero's aggressive first action at this node, when it has one. */
    val betAction: TurnAction? get() = freq.keys.firstOrNull { it != TurnAction.CHECK && it != TurnAction.FOLD && it != TurnAction.CALL && it != TurnAction.RAISE }

    fun frequency(action: TurnAction, combo: Int): Float = freq[action]?.get(combo) ?: 0f

    fun frequency(action: TurnAction): Double {
        val f = freq[action] ?: return 0.0
        var n = 0.0; var d = 0.0
        for (k in 0 until Combos.COUNT) { val w = heroRange[k]; if (w <= 0f) continue; n += w * f[k]; d += w }
        return if (d > 0) n / d else 0.0
    }

    fun terms(action: TurnAction, combo: Int): TurnEvTerms? = terms[action]?.get(combo)

    fun ev(action: TurnAction, combo: Int): Double = when (action) {
        TurnAction.FOLD -> 0.0
        else -> terms(action, combo)?.ev ?: Double.NaN
    }

    fun mix(combo: Int): Map<TurnAction, Float> = freq.mapValues { it.value[combo] }
    fun mix(combo: String): Map<TurnAction, Float> = mix(Combos.parse(combo))

    fun classFrequency(action: TurnAction): FloatArray {
        val f = freq[action] ?: return FloatArray(HandClass.COUNT) { Float.NaN }
        return Combos.toClass(f, heroRange)
    }

    fun rangeEv(): Double {
        var n = 0.0; var d = 0.0
        for (k in 0 until Combos.COUNT) {
            val w = heroRange[k]
            if (w <= 0f) continue
            var v = 0.0
            for ((a, f) in freq) if (f[k] > 0f) v += f[k] * ev(a, k)
            n += w * v; d += w
        }
        return if (d > 0) n / d else 0.0
    }
}
