package com.solverin.engine.river

import com.solverin.engine.math.MdfFloor
import com.solverin.engine.core.HandClass
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopEngine
import com.solverin.engine.turn.TurnBluffCap

/** Terminal river solver: one [RiverSession] per spot (showdown strengths built once); node locks re-solve in ms. */
class RiverEngine {
    fun session(spot: RiverSpot): RiverSession = RiverSession(spot)
    fun analyze(spot: RiverSpot, ctx: RiverContext): RiverAnalysis = session(spot).analyze(ctx)
}

class RiverSession(val spot: RiverSpot) {
    private val shared = RiverMathEngine.Shared(spot)

    init {
        require(shared.hero.any { it > 0f }) { "Hero range is empty on this board" }
        require(shared.villain.any { it > 0f }) { "Villain range is empty on this board" }
        require(shared.extra == null || shared.extra.any { it > 0f }) { "Extra range is empty on this board" }
    }

    fun math(ctx: RiverContext) = RiverMathEngine(spot, ctx, shared)

    fun analyze(ctx: RiverContext): RiverAnalysis {
        val m = math(ctx)
        val p = ctx.policy
        val heroIdx = m.heroIdx.toList()
        return if (spot.nodeType == RiverNodeType.VS_BET) facingBet(m, p, ctx, heroIdx) else betNode(m, p, ctx, heroIdx)
    }

    /** River override threshold: max(exploitOverrideBb, exploitPotShare × pot). */
    private fun exploitThreshold(p: RiverPolicy, pot: Double) = maxOf(p.exploitOverrideBb, p.exploitPotShare * pot)

    /**
     * Override gate: after two streets of calls it stays shut unless the villain over-folds vs MDF at this price on his
     * unconditional population profile (a locked Nit / station read describes his whole range, not what is left of it).
     * Never vs a NIT read: his surviving range is condensed toward strong hands.
     */
    private fun overrideOpen(m: RiverMathEngine, overfolds: Boolean) = overfolds && !m.station && !m.nit && !(spot.condensedVillain && m.betFoldLocked)

    /**
     * Thin value vs a range that called two streets: below thinValueVsCallers vs his calling range, check instead —
     * unless the bet beats checking by thinValueGuardEvBb.
     */
    private fun thinValueBlocked(m: RiverMathEngine, p: RiverPolicy, t: RiverEvTerms, checkEv: Double) =
        spot.villainCalledTwoStreets && !m.station && t.equity >= 0.5f && t.equity < p.thinValueVsCallers && t.ev - checkEv < p.thinValueGuardEvBb

    /** Stack-off guard vs a condensed range: an all-in needs stackOffEquity vs the calling range or a nut hand. */
    private fun stackOffBlocked(m: RiverMathEngine, p: RiverPolicy, h: Int, t: RiverEvTerms): Boolean =
        spot.condensedVillain && !m.station && t.allIn && t.equity < p.stackOffEquity && !(m.eqTotal(h) >= p.overbetValueEquity)

    /** Size gates: showdown protection, block / overbet polarization, four-card shield. */
    private fun sizeAllowed(m: RiverMathEngine, p: RiverPolicy, h: Int, size: Double, t: RiverEvTerms): Boolean {
        val eqT = m.eqTotal(h)
        val value = t.equity >= 0.5f
        if (m.showdownProtected(h, t.equity)) return false
        // An all-in is never a block bet (low SPR caps every size at the stack).
        val block = RiverSizingOptimizer.isBlock(size, p) && !t.allIn
        if (block && !(eqT in p.blockValueLow..p.blockValueHigh || (!value && t.bNut < 1.0))) return false
        if ((RiverSizingOptimizer.isOverbet(size, p) || (t.allIn && size >= 1.0)) && !(eqT >= p.overbetValueEquity || (!value && t.bNut < 1.0))) return false
        if (m.fourCardBoard) {
            if (value && !block && !m.shieldMade(h)) return false
            if (!value && !m.shieldBlocker(h)) return false
        }
        return true
    }

    private fun betNode(m: RiverMathEngine, p: RiverPolicy, ctx: RiverContext, heroIdx: List<Int>): RiverAnalysis {
        val pot = spot.potBb
        val after = spot.nodeType == RiverNodeType.FIRST_TO_ACT
        val check = arrayOfNulls<RiverEvTerms>(Combos.COUNT)
        for (h in heroIdx) check[h] = m.check(h, after)
        val spr = spot.effectiveStackBb / pot
        val sizes = RiverSizingOptimizer.candidates(spr, m.nutAdvantage, m.villainCapped, p)
        val fc = FloatArray(Combos.COUNT); val fb = FloatArray(Combos.COUNT)
        val betTerms = arrayOfNulls<RiverEvTerms>(Combos.COUNT)
        val chosen = DoubleArray(Combos.COUNT) { Double.NaN }
        val capped = BooleanArray(Combos.COUNT)
        val fired = BooleanArray(Combos.COUNT)
        if (sizes.isEmpty()) {
            for (h in heroIdx) fc[h] = 1f
            return analysis(m, mapOf(RiverAction.CHECK to fc), mapOf(RiverAction.CHECK to check), chosen, capped, sizes, null, null, false, null, null)
        }
        for (h in heroIdx) {
            var best: RiverEvTerms? = null; var bestSize = Double.NaN; var any: RiverEvTerms? = null
            for (size in sizes) {
                val t = m.bet(h, size)
                if (any == null || t.ev > any.ev) any = t
                if (sizeAllowed(m, p, h, size, t) && !stackOffBlocked(m, p, h, t) && !thinValueBlocked(m, p, t, check[h]!!.ev) && (best == null || t.ev > best.ev)) { best = t; bestSize = size }
            }
            if (best == null) { betTerms[h] = any; fc[h] = 1f; continue }
            betTerms[h] = best; chosen[h] = bestSize
            val mix = FlopEngine.mix(Double.NEGATIVE_INFINITY, check[h]!!.ev, best.ev, p.mixBandBb)
            fc[h] = mix[1]; fb[h] = mix[2]
        }
        // Range consistency per size bucket: bluffs ≤ value × ratio(size).
        for (size in sizes) {
            val bucket = heroIdx.filter { chosen[it] == size && fb[it] > 0f }
            if (bucket.isEmpty()) continue
            val agg = FloatArray(Combos.COUNT); val pas = FloatArray(Combos.COUNT)
            for (h in bucket) { agg[h] = fb[h]; pas[h] = fc[h] }
            val foldAll = 1 - m.defenseShare(size)
            val ref = size / (1 + size)
            val ratio = (RiverSizingOptimizer.nashBluffRatio(size) * (foldAll / ref).let { it * it })
                .coerceIn(p.minBluffRatio, if (m.betFoldLocked) p.maxBluffRatioLocked else p.maxBluffRatio) *
                (if (m.tightened) p.condensedBluffScale else 1.0)
            val overfolds = foldAll >= 1 - 1 / (1 + size) + p.overrideOverfoldMargin
            val valueEq = if (m.station) 0.45f else 0.5f
            val cut = TurnBluffCap.apply(
                bucket, m.hero, ratio, m.station, agg, pas, null,
                aggEv = { betTerms[it]!!.ev }, passiveEv = { check[it]!!.ev },
                isValue = { betTerms[it]!!.equity >= valueEq }, priority = { betTerms[it]!!.bNut < 1.0 && betTerms[it]!!.bFold >= 1.0 },
                foldAllowed = { false },
                exempt = { h -> (overrideOpen(m, overfolds) && betTerms[h]!!.bNut < p.exploitBlocker && betTerms[h]!!.ev - check[h]!!.ev >= exploitThreshold(p, pot))
                    .also { if (it) fired[h] = true } },
            )
            for (h in bucket) { fb[h] = agg[h]; fc[h] = pas[h]; if (cut[h]) capped[h] = true }
        }
        val act = spot.heroBetAction
        return analysis(m, mapOf(RiverAction.CHECK to fc, act to fb), mapOf(RiverAction.CHECK to check, act to betTerms), chosen, capped,
            sizes, null, null, false, null, null, fired = fired)
    }

    private fun facingBet(m: RiverMathEngine, p: RiverPolicy, ctx: RiverContext, heroIdx: List<Int>): RiverAnalysis {
        val pot = spot.potBb
        val bet = minOf(spot.facingBetBb, spot.effectiveStackBb)
        val potOdds = bet / (pot + 2 * bet)
        val minBet = m.isMinBet(bet, pot)
        val donk = m.donkType(spot.facingBetBb / pot)
        val call = arrayOfNulls<RiverEvTerms>(Combos.COUNT)
        val raise = arrayOfNulls<RiverEvTerms>(Combos.COUNT)
        val fF = FloatArray(Combos.COUNT); val fC = FloatArray(Combos.COUNT); val fR = FloatArray(Combos.COUNT)
        fun foldAllowed(h: Int) = !(minBet && (call[h]!!.equity >= potOdds || call[h]!!.ev > 0.0))
        for (h in heroIdx) {
            val c = m.call(h, bet)
            val r = m.raise(h, bet)
            call[h] = c; raise[h] = r
            var raiseEv = r?.ev ?: Double.NEGATIVE_INFINITY
            if (r != null) {
                val value = r.equity >= 0.5f
                if (m.fourCardBoard && ((value && !m.shieldMade(h)) || (!value && !m.shieldBlocker(h)))) raiseEv = Double.NEGATIVE_INFINITY
                if (donk == RiverDonkType.POLARIZED && !value && r.bNut >= 1.0) raiseEv = Double.NEGATIVE_INFINITY
                if (stackOffBlocked(m, p, h, r)) raiseEv = Double.NEGATIVE_INFINITY
            }
            val mix = FlopEngine.mix(if (foldAllowed(h)) 0.0 else Double.NEGATIVE_INFINITY, c.ev, raiseEv, p.mixBandBb)
            fF[h] = mix[0]; fC[h] = mix[1]; fR[h] = mix[2]
        }
        val to = m.raiseTo(bet, pot + bet)
        val raiseSize = (to - bet) / (pot + bet)
        val foldR = m.raiseFold(bet)
        val ref = raiseSize / (1 + raiseSize)
        val ratio = (RiverSizingOptimizer.nashBluffRatio(raiseSize) * (foldR / ref.coerceAtLeast(0.05)).let { it * it })
            .coerceIn(p.minBluffRatio, if (ctx.locks[RiverVillainNode.VS_RAISE]?.fold != null) p.maxBluffRatioLocked else p.maxBluffRatio) *
            (if (m.tightened) p.condensedBluffScale else 1.0)
        val raiseOverfolds = foldR >= ref + p.overrideOverfoldMargin
        val firedR = BooleanArray(Combos.COUNT)
        val capped = TurnBluffCap.apply(
            heroIdx, m.hero, ratio, false, fR, fC, fF,
            aggEv = { raise[it]?.ev ?: Double.NEGATIVE_INFINITY }, passiveEv = { call[it]!!.ev },
            isValue = { (raise[it]?.equity ?: 0f) >= 0.5f }, priority = { raise[it]?.let { r -> r.bNut < 1.0 && r.bFold >= 1.0 } ?: false },
            foldAllowed = ::foldAllowed,
            exempt = { h -> (raise[h]?.let { r -> overrideOpen(m, raiseOverfolds) && r.bNut < p.exploitBlocker &&
                r.ev - maxOf(call[h]!!.ev, if (foldAllowed(h)) 0.0 else Double.NEGATIVE_INFINITY) >= exploitThreshold(p, pot) } ?: false)
                .also { if (it) firedR[h] = true } },
        )
        if (!spot.heroInPosition) {
            TurnBluffCap.capTotal(heroIdx, m.hero, p.checkRaiseCap, fR, fC, fF, capped,
                aggEv = { raise[it]?.ev ?: Double.NEGATIVE_INFINITY }, passiveEv = { call[it]!!.ev }, foldAllowed = ::foldAllowed,
                protect = { (raise[it]?.equity ?: 0f) >= 0.5f })
        }
        val mdf = pot / (pot + bet)
        val floor = when {
            minBet -> 1 - mdf
            donk == RiverDonkType.WEAK -> minOf(1.0, 1 - mdf + p.weakDonkMdfSlack)
            else -> null
        }
        val effFloor = floor?.let { fl -> MdfFloor.enforce(heroIdx, m.hero, fl, fF, fC, bet, minBet, { call[it]!!.equity }) { call[it]!!.ev } }
        val bluff = m.bluffShare(bet); val alpha = m.alpha(bet)
        return analysis(m, mapOf(RiverAction.FOLD to fF, RiverAction.CALL to fC, RiverAction.RAISE to fR),
            mapOf(RiverAction.CALL to call, RiverAction.RAISE to raise), DoubleArray(Combos.COUNT) { Double.NaN }, capped,
            emptyList(), mdf, potOdds, minBet, effFloor, donk, bluff, alpha, firedR)
    }

    private fun analysis(
        m: RiverMathEngine, freq: Map<RiverAction, FloatArray>, terms: Map<RiverAction, Array<RiverEvTerms?>>, sizes: DoubleArray,
        capped: BooleanArray, candidates: List<Double>, mdf: Double?, potOdds: Double?, minBet: Boolean, maxFold: Double?,
        donk: RiverDonkType?, bluffShare: Double = Double.NaN, alpha: Double = Double.NaN, fired: BooleanArray = BooleanArray(Combos.COUNT),
    ) = RiverAnalysis(
        spot, m.impact, spot.nodeType, m.rangeAdvantage, m.nutAdvantage, m.villainCapped,
        RiverSizingOptimizer.overbetEnabled(m.nutAdvantage, m.villainCapped, m.policy),
        mdf, potOdds, minBet, maxFold, donk, bluffShare, alpha, freq, terms, m.hero, sizes, capped, candidates, fired,
    )
}

/** River solve output (per combo; class-level aggregation for display). */
class RiverAnalysis(
    val spot: RiverSpot,
    val impact: RiverCardImpact,
    val nodeType: RiverNodeType,
    val rangeAdvantage: Double,
    /** ΔNuts_river. */
    val nutAdvantage: Double,
    val villainCapped: Boolean,
    val overbetEnabled: Boolean,
    val mdf: Double?,
    val potOdds: Double?,
    val minBet: Boolean,
    /** Enforced fold ceiling (min-bet MDF / weak donk); null = pure max EV (exploitative over-fold allowed). */
    val maxFold: Double?,
    val donkType: RiverDonkType?,
    /** Villain's bluff share of his bet range (facing a bet) and Nash α = B / (Pot + 2B). */
    val bluffShare: Double,
    val alpha: Double,
    private val freq: Map<RiverAction, FloatArray>,
    private val terms: Map<RiverAction, Array<RiverEvTerms?>>,
    val heroRange: FloatArray,
    private val sizes: DoubleArray,
    private val capped: BooleanArray,
    val sizeCandidates: List<Double>,
    /** Combos whose bluff kept its frequency through PostflopMassiveExploitOverride (exempt from the cap). */
    private val overrideFired: BooleanArray = BooleanArray(Combos.COUNT),
) {
    fun overrideFired(combo: Int): Boolean = overrideFired[combo]
    /** Villain under-bluffs: pure bluff-catchers may over-fold (MDF ignored). */
    val exploitOverFold: Boolean get() = !bluffShare.isNaN() && bluffShare < alpha

    fun capped(combo: Int): Boolean = capped[combo]
    val actions: List<RiverAction> get() = freq.keys.toList()
    val betAction: RiverAction? get() = freq.keys.firstOrNull { it !in setOf(RiverAction.CHECK, RiverAction.FOLD, RiverAction.CALL, RiverAction.RAISE) }

    /** Chosen bet size (pot fraction) of [combo] at a bet node, NaN when it never bets. */
    fun betSize(combo: Int): Double = sizes[combo]

    fun frequency(action: RiverAction, combo: Int): Float = freq[action]?.get(combo) ?: 0f

    fun frequency(action: RiverAction): Double {
        val f = freq[action] ?: return 0.0
        var n = 0.0; var d = 0.0
        for (k in 0 until Combos.COUNT) { val w = heroRange[k]; if (w <= 0f) continue; n += w * f[k]; d += w }
        return if (d > 0) n / d else 0.0
    }

    /** Range share betting each size (pot fractions). */
    fun sizeFrequency(): Map<Double, Double> {
        val act = betAction ?: return emptyMap()
        val f = freq[act]!!
        val out = HashMap<Double, Double>()
        var d = 0.0
        for (k in 0 until Combos.COUNT) {
            val w = heroRange[k]; if (w <= 0f) continue
            d += w
            if (f[k] > 0f && !sizes[k].isNaN()) out[sizes[k]] = (out[sizes[k]] ?: 0.0) + w * f[k]
        }
        return out.mapValues { it.value / d }
    }

    fun terms(action: RiverAction, combo: Int): RiverEvTerms? = terms[action]?.get(combo)

    fun ev(action: RiverAction, combo: Int): Double = when (action) {
        RiverAction.FOLD -> 0.0
        else -> terms(action, combo)?.ev ?: Double.NaN
    }

    fun mix(combo: Int): Map<RiverAction, Float> = freq.mapValues { it.value[combo] }
    fun mix(combo: String): Map<RiverAction, Float> = mix(Combos.parse(combo))

    fun classFrequency(action: RiverAction): FloatArray {
        val f = freq[action] ?: return FloatArray(HandClass.COUNT) { Float.NaN }
        return Combos.toClass(f, heroRange)
    }

    fun rangeEv(): Double {
        var n = 0.0; var d = 0.0
        for (k in 0 until Combos.COUNT) {
            val w = heroRange[k]; if (w <= 0f) continue
            var v = 0.0
            for ((a, f) in freq) if (f[k] > 0f) v += f[k] * ev(a, k)
            n += w * v; d += w
        }
        return if (d > 0) n / d else 0.0
    }
}
