package com.solverin.engine.flop

import com.solverin.engine.core.HandClass

/**
 * Flop solver: master-equation EVs per combo, range / nut advantage, geometric + elastic bet sizing,
 * action frequencies. One [FlopSession] per spot builds the board equity matrix once; re-analysis with new
 * node locks / profile only rebuilds villain responses (milliseconds).
 */
class FlopEngine(private val stride: Int = FlopEquity.FAST_STRIDE) {

    fun session(spot: FlopSpot): FlopSession = FlopSession(spot, stride)

    fun analyze(spot: FlopSpot, ctx: FlopContext): FlopAnalysis = session(spot).analyze(ctx)

    companion object {
        /** Linear mix of [fold, call(or check), raise(or bet)] within [band] of the best EV. */
        fun mix(fold: Double, passive: Double, aggressive: Double, band: Double): FloatArray {
            val best = maxOf(fold, passive, aggressive)
            fun w(v: Double) = if (v == Double.NEGATIVE_INFINITY) 0.0 else ((band - (best - v)) / band).coerceAtLeast(0.0)
            val wf = w(fold); val wp = w(passive); val wa = w(aggressive)
            val sum = wf + wp + wa
            if (sum <= 0.0) return floatArrayOf(0f, 1f, 0f)
            return floatArrayOf((wf / sum).toFloat(), (wp / sum).toFloat(), (wa / sum).toFloat())
        }
    }
}

/** Spot-bound solver state: board data + equity matrix shared by every [analyze] call. */
class FlopSession(val spot: FlopSpot, stride: Int = FlopEquity.FAST_STRIDE) {
    private val shared = FlopMathEngine.Shared(spot)
    val matrix: FlopEquityMatrix by lazy { FlopEquityMatrix(spot.board, shared.hero, shared.villain, stride) }

    fun analyze(ctx: FlopContext): FlopAnalysis {
        val m = FlopMathEngine(spot, ctx, matrix, shared)
        val p = ctx.policy
        val pot = spot.potBb
        val heroIdx = (0 until Combos.COUNT).filter { m.hero[it] > 0f }
        return when (spot.nodeType) {
            FlopNodeType.VS_BET -> facingBet(m, ctx, heroIdx)
            FlopNodeType.VS_CHECK, FlopNodeType.FIRST_TO_ACT -> {
                val villainActsAfter = spot.nodeType == FlopNodeType.FIRST_TO_ACT
                val donk = villainActsAfter && !spot.heroIsAggressor
                val betAction = if (donk) FlopAction.DONK else FlopAction.BET
                val respNode = if (donk) FlopVillainNode.VS_DONK else FlopVillainNode.VS_BET
                val facingRange = if (villainActsAfter) m.villain else m.villainFirstAction.fold
                val check = arrayOfNulls<FlopEvTerms>(Combos.COUNT)
                for (h in heroIdx) check[h] = m.check(h, pot, villainActsAfter)
                val spr = spot.effectiveStackBb / pot
                val elasticity = BetSizingOptimizer.elasticity({ f -> m.villainModel.continueShare(pot, f * pot, respNode) })
                val sizes = BetSizingOptimizer.candidates(spr, m.rangeAdvantage, m.nutAdvantage, elasticity, p)
                var best: SizeResult? = null
                for (size in sizes) {
                    val b = minOf(size * pot, spot.effectiveStackBb)
                    val resp = m.villainModel.respondToBet(facingRange, pot, b, respNode)
                    val bets = arrayOfNulls<FlopEvTerms>(Combos.COUNT)
                    val fc = FloatArray(Combos.COUNT); val fb = FloatArray(Combos.COUNT)
                    for (h in heroIdx) {
                        val t = m.aggressive(h, pot, b, 0.0, 0.0, resp, level = 1)
                        bets[h] = t
                        val mix = FlopEngine.mix(Double.NEGATIVE_INFINITY, check[h]!!.ev, t.ev, p.mixBandBb)
                        fc[h] = mix[1]; fb[h] = mix[2]
                    }
                    val ratio = FlopBluffCap.ratio(false, b / pot, resp.foldTotal / resp.priorTotal, m.villainModel.mdf(pot, b),
                        locked = ctx.locks[respNode]?.fold != null)
                    val capped = FlopBluffCap.apply(
                        heroIdx, m.hero, ratio, fb, fc, null,
                        aggEv = { bets[it]!!.ev }, passiveEv = { check[it]!!.ev },
                        isValue = { m.eqVs(resp.continueRange).equity(it) >= 0.5f || m.isNut(it) }, foldAllowed = { false },
                    )
                    var total = 0.0
                    for (h in heroIdx) total += m.hero[h] * (fb[h] * bets[h]!!.ev + fc[h] * check[h]!!.ev)
                    if (best == null || total > best.total) best = SizeResult(size, b, total, bets, fc, fb, capped)
                }
                val chosen = best!!
                FlopAnalysis(
                    spot, m.flopId, m.texture, spot.nodeType, m.rangeAdvantage, m.nutAdvantage,
                    m.heroVsVillain.rangeEquity(m.hero), chosen.size, chosen.bet,
                    mdf = null, potOdds = null, minBet = false, donkRead = m.donkRead,
                    freq = mapOf(FlopAction.CHECK to chosen.fc, betAction to chosen.fb),
                    terms = mapOf(FlopAction.CHECK to check, betAction to chosen.bets), heroRange = m.hero, capped = chosen.capped,
                    sizeCandidates = sizes, elasticity = elasticity, geometricSize = BetSizingOptimizer.geometric(spr, p.streetsToRiver),
                )
            }
        }
    }

    /**
     * Strict MDF vs min-bets: if the range folds more than [maxFold], the folded hands with the highest call EV
     * are turned into calls until the range folds exactly [maxFold].
     */
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

    private class SizeResult(
        val size: Double, val bet: Double, val total: Double, val bets: Array<FlopEvTerms?>,
        val fc: FloatArray, val fb: FloatArray, val capped: BooleanArray,
    )

    private fun facingBet(m: FlopMathEngine, ctx: FlopContext, heroIdx: List<Int>): FlopAnalysis {
        val p = ctx.policy
        val pot = spot.potBb
        val bet = minOf(spot.facingBetBb, spot.effectiveStackBb)
        val potBefore = pot + bet
        val betRange = if (spot.isDonk) {
            m.villainModel.firstAction(m.villain, FlopVillainNode.DONK, m.donkRead?.valueShare).raise
        } else m.villainFirstAction.raise
        val minBet = m.isMinBet(bet, pot)
        val call = arrayOfNulls<FlopEvTerms>(Combos.COUNT)
        val raise = arrayOfNulls<FlopEvTerms>(Combos.COUNT)
        val fFold = FloatArray(Combos.COUNT); val fCall = FloatArray(Combos.COUNT); val fRaise = FloatArray(Combos.COUNT)
        val potOdds = bet / (potBefore + bet)
        for (h in heroIdx) {
            val c = m.call(h, potBefore, bet, betRange)
            val r = if (bet < spot.effectiveStackBb) m.raiseVsBet(h, potBefore, bet, betRange) else null
            call[h] = c; raise[h] = r
            // Min-bet guard: with pot-odds equity a fold is never allowed.
            val foldAllowed = !(minBet && c.equity >= potOdds)
            val mix = FlopEngine.mix(if (foldAllowed) 0.0 else Double.NEGATIVE_INFINITY, c.ev, r?.ev ?: Double.NEGATIVE_INFINITY, p.mixBandBb)
            fFold[h] = mix[0]; fCall[h] = mix[1]; fRaise[h] = mix[2]
        }
        val raiseResp = m.villainModel.respondToRaise(betRange)
        val raiseSize = m.raiseTo(bet, potBefore) - bet
        val ratio = FlopBluffCap.ratio(true, raiseSize / potBefore, raiseResp.foldTotal / raiseResp.priorTotal,
            m.villainModel.mdf(potBefore, raiseSize), locked = ctx.locks[FlopVillainNode.VS_RAISE]?.fold != null)
        val capped = FlopBluffCap.apply(
            heroIdx, m.hero, ratio, fRaise, fCall, fFold,
            aggEv = { raise[it]?.ev ?: Double.NEGATIVE_INFINITY }, passiveEv = { call[it]!!.ev },
            isValue = { m.eqVs(raiseResp.continueRange).equity(it) >= 0.5f || m.isNut(it) },
            foldAllowed = { !(minBet && call[it]!!.equity >= potOdds) },
        )
        if (!spot.heroInPosition) {
            val connected = m.texture.connectivity == Connectivity.HIGH || m.texture.volatility >= p.connectedVolatility
            FlopBluffCap.capTotal(
                heroIdx, m.hero, if (connected) p.checkRaiseCapConnected else p.checkRaiseCap,
                fRaise, fCall, fFold, capped,
                aggEv = { raise[it]?.ev ?: Double.NEGATIVE_INFINITY }, passiveEv = { call[it]!!.ev },
                foldAllowed = { !(minBet && call[it]!!.equity >= potOdds) },
            )
        }
        val mdf = pot / (pot + bet)
        if (minBet) enforceMdf(heroIdx, m.hero, 1 - mdf, fFold, fCall) { call[it]!!.ev }
        return FlopAnalysis(
            spot, m.flopId, m.texture, FlopNodeType.VS_BET, m.rangeAdvantage, m.nutAdvantage,
            m.heroVsVillain.rangeEquity(m.hero), null, null,
            mdf = mdf, potOdds = potOdds, minBet = minBet, donkRead = if (spot.isDonk) m.donkRead else null,
            freq = mapOf(FlopAction.FOLD to fFold, FlopAction.CALL to fCall, FlopAction.RAISE to fRaise),
            terms = mapOf(FlopAction.CALL to call, FlopAction.RAISE to raise), heroRange = m.hero, capped = capped,
        )
    }
}

/** Flop solve output (per combo; class-level aggregation for display). */
class FlopAnalysis(
    val spot: FlopSpot,
    val flopId: Int,
    val texture: Texture,
    val nodeType: FlopNodeType,
    /** ΔRange: hero range equity − 50%. */
    val rangeAdvantage: Double,
    /** ΔNuts: hero share of combos with > 85% equity − villain share. */
    val nutAdvantage: Double,
    val heroEquity: Double,
    /** Chosen bet size (fraction of pot / bb) at check-or-bet nodes. */
    val betSizeFraction: Double?,
    val betSizeBb: Double?,
    /** Facing a bet: minimum defense frequency and pot odds. */
    val mdf: Double?,
    val potOdds: Double?,
    val minBet: Boolean,
    val donkRead: DonkRead?,
    private val freq: Map<FlopAction, FloatArray>,
    private val terms: Map<FlopAction, Array<FlopEvTerms?>>,
    val heroRange: FloatArray,
    /** Combos whose bet / raise frequency was cut by [FlopBluffCap] (range-consistency on bluffs). */
    private val capped: BooleanArray = BooleanArray(Combos.COUNT),
    /** Bet sizes evaluated (fractions of pot) by [BetSizingOptimizer]. */
    val sizeCandidates: List<Double> = emptyList(),
    /** Villain continue elasticity vs size (≤ 0; 0 = inelastic). */
    val elasticity: Double = Double.NaN,
    /** Geometric size to get stacks in by the river. */
    val geometricSize: Double = Double.NaN,
) {
    fun capped(combo: Int): Boolean = capped[combo]

    val actions: List<FlopAction> get() = freq.keys.toList()

    fun frequency(action: FlopAction, combo: Int): Float = freq[action]?.get(combo) ?: 0f

    /** Hero-range-weighted frequency of [action]. */
    fun frequency(action: FlopAction): Double {
        val f = freq[action] ?: return 0.0
        var n = 0.0; var d = 0.0
        for (k in 0 until Combos.COUNT) { val w = heroRange[k]; if (w <= 0f) continue; n += w * f[k]; d += w }
        return if (d > 0) n / d else 0.0
    }

    fun terms(action: FlopAction, combo: Int): FlopEvTerms? = terms[action]?.get(combo)

    fun ev(action: FlopAction, combo: Int): Double = when (action) {
        FlopAction.FOLD -> 0.0
        else -> terms(action, combo)?.ev ?: Double.NaN
    }

    fun mix(combo: Int): Map<FlopAction, Float> = freq.mapValues { it.value[combo] }

    fun mix(combo: String): Map<FlopAction, Float> = mix(Combos.parse(combo))

    /** Per hand class (169) frequency of [action], combo-weighted by hero's range. */
    fun classFrequency(action: FlopAction): FloatArray {
        val f = freq[action] ?: return FloatArray(HandClass.COUNT) { Float.NaN }
        return Combos.toClass(f, heroRange)
    }

    /** Hero-range-weighted EV of the strategy actually played. */
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
