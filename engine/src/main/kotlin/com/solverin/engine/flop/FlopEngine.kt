package com.solverin.engine.flop

import com.solverin.engine.core.HandClass

/** Flop solver: master-equation EVs per combo, range / nut advantage, bet sizing and action frequencies. */
class FlopEngine(private val equity: FlopEquity = FlopEquity()) {

    fun analyze(spot: FlopSpot, ctx: FlopContext): FlopAnalysis {
        val m = FlopMathEngine(spot, ctx, equity)
        val p = ctx.policy
        val pot = spot.potBb
        val heroIdx = (0 until Combos.COUNT).filter { m.hero[it] > 0f }
        return when (spot.nodeType) {
            FlopNodeType.VS_BET -> {
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
                    val mix = mix(if (foldAllowed) 0.0 else Double.NEGATIVE_INFINITY, c.ev, r?.ev ?: Double.NEGATIVE_INFINITY, p.mixBandBb)
                    fFold[h] = mix[0]; fCall[h] = mix[1]; fRaise[h] = mix[2]
                }
                val raiseResp = m.villainModel.respondToRaise(betRange)
                val raiseFraction = (m.raiseTo(bet) - bet) / potBefore
                val ratio = FlopBluffCap.ratio(true, raiseFraction, raiseResp.foldTotal / raiseResp.priorTotal,
                    m.villainModel.mdf(potBefore, m.raiseTo(bet) - bet))
                val capped = FlopBluffCap.apply(
                    heroIdx, m.hero, ratio, fRaise, fCall, fFold,
                    aggEv = { raise[it]?.ev ?: Double.NEGATIVE_INFINITY }, passiveEv = { call[it]!!.ev },
                    isValue = { m.eqVs(raiseResp.continueRange).equity(it) >= 0.5f || m.isNut(it) },
                    foldAllowed = { !(minBet && call[it]!!.equity >= potOdds) },
                )
                FlopAnalysis(
                    spot, m.flopId, m.texture, FlopNodeType.VS_BET, m.rangeAdvantage, m.nutAdvantage,
                    m.heroVsVillain.rangeEquity(m.hero), null, null,
                    mdf = pot / (pot + bet), potOdds = potOdds, minBet = minBet, donkRead = if (spot.isDonk) m.donkRead else null,
                    freq = mapOf(FlopAction.FOLD to fFold, FlopAction.CALL to fCall, FlopAction.RAISE to fRaise),
                    terms = mapOf(FlopAction.CALL to call, FlopAction.RAISE to raise), heroRange = m.hero, capped = capped,
                )
            }
            FlopNodeType.VS_CHECK, FlopNodeType.FIRST_TO_ACT -> {
                val villainActsAfter = spot.nodeType == FlopNodeType.FIRST_TO_ACT
                val facingRange = if (villainActsAfter) m.villain else m.villainFirstAction.fold
                val check = arrayOfNulls<FlopEvTerms>(Combos.COUNT)
                for (h in heroIdx) check[h] = m.check(h, pot, villainActsAfter)
                var bestSize = p.betSizes.first()
                var bestValue = Double.NEGATIVE_INFINITY
                var bestBets: Array<FlopEvTerms?> = arrayOfNulls(Combos.COUNT)
                var fCheck = FloatArray(Combos.COUNT); var fBet = FloatArray(Combos.COUNT)
                var bestCapped = BooleanArray(Combos.COUNT)
                for (size in p.betSizes) {
                    val b = minOf(size * pot, spot.effectiveStackBb)
                    val resp = m.villainModel.respondToBet(facingRange, pot, b)
                    val bets = arrayOfNulls<FlopEvTerms>(Combos.COUNT)
                    val fc = FloatArray(Combos.COUNT); val fb = FloatArray(Combos.COUNT)
                    for (h in heroIdx) {
                        val t = m.aggressive(h, pot, b, 0.0, 0.0, resp, level = 1)
                        bets[h] = t
                        val mix = mix(Double.NEGATIVE_INFINITY, check[h]!!.ev, t.ev, p.mixBandBb)
                        fc[h] = mix[1]; fb[h] = mix[2]
                    }
                    val ratio = FlopBluffCap.ratio(false, b / pot, resp.foldTotal / resp.priorTotal, m.villainModel.mdf(pot, b))
                    val cappedHere = FlopBluffCap.apply(
                        heroIdx, m.hero, ratio, fb, fc, null,
                        aggEv = { bets[it]!!.ev }, passiveEv = { check[it]!!.ev },
                        isValue = { m.eqVs(resp.continueRange).equity(it) >= 0.5f || m.isNut(it) }, foldAllowed = { false },
                    )
                    var total = 0.0
                    for (h in heroIdx) total += m.hero[h] * (fb[h] * bets[h]!!.ev + fc[h] * check[h]!!.ev)
                    if (total > bestValue) {
                        bestValue = total; bestSize = size; bestBets = bets; fCheck = fc; fBet = fb; bestCapped = cappedHere
                    }
                }
                FlopAnalysis(
                    spot, m.flopId, m.texture, spot.nodeType, m.rangeAdvantage, m.nutAdvantage,
                    m.heroVsVillain.rangeEquity(m.hero), bestSize, minOf(bestSize * pot, spot.effectiveStackBb),
                    mdf = null, potOdds = null, minBet = false, donkRead = m.donkRead,
                    freq = mapOf(FlopAction.CHECK to fCheck, FlopAction.BET to fBet),
                    terms = mapOf(FlopAction.CHECK to check, FlopAction.BET to bestBets), heroRange = m.hero, capped = bestCapped,
                )
            }
        }
    }

    companion object {
        /** Linear mix of [fold, call(or check), raise(or bet)] within [band] of the best EV. */
        fun mix(fold: Double, passive: Double, aggressive: Double, band: Double): FloatArray {
            val best = maxOf(fold, passive, aggressive)
            fun w(v: Double) = if (v == Double.NEGATIVE_INFINITY) 0.0 else ((band - (best - v)) / band).coerceAtLeast(0.0)
            val wf = w(fold); val wp = w(passive); val wa = w(aggressive)
            val s = wf + wp + wa
            if (s <= 0.0) return floatArrayOf(0f, 1f, 0f)
            return floatArrayOf((wf / s).toFloat(), (wp / s).toFloat(), (wa / s).toFloat())
        }
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
    /** ΔNuts: hero nut-tier share − villain nut-tier share. */
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
