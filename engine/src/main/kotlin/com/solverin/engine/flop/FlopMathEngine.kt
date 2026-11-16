package com.solverin.engine.flop

import com.solverin.engine.math.RakeModel
import java.util.IdentityHashMap

/**
 * Master flop EV equation (per hero combo, card-removal exact):
 *
 *  EV(bet/raise/donk) = P(fold)·Pot + P(call)·[EQ(h, callRange)·R_post·PotTotal − Inv − Rake_flop + EV_implied]
 *                     + P(raise)·EV_vsRaise
 *  EV(call)           = EQ(h, betRange)·R_post·PotTotal − Inv − Rake_flop + EV_implied
 *  EV(check)          = (1 − P(villain bets))·[EQ·R_post·Pot − Rake + EV_implied] + P(villain bets)·max(fold, call, raise)
 *  EV(fold)           = 0
 *
 *  R_post = BasePos · Initiative · (1 − K · villainDrawDensity · vulnerability(hand)), SPR → 0 pulls R → 1.
 *
 * Bounded tree (3 bet levels): bet → raise → re-raise all-in → call / fold. Any branch leaving less than
 * commitSpr × pot behind is valued as all-in (raw equity), so the tree never calls off a stack and folds.
 */
class FlopMathEngine(
    private val spot: FlopSpot,
    private val ctx: FlopContext,
    private val equity: EquityProvider,
    shared: Shared? = null,
) {
    /** Board-only data reusable across contexts (node-lock recalculation). */
    class Shared(spot: FlopSpot) {
        private val dead = Combos.mask(spot.board)
        val hero = FloatArray(Combos.COUNT) { if (Combos.isLive(it, dead)) spot.heroRange[it] else 0f }
        val villain = FloatArray(Combos.COUNT) { if (Combos.isLive(it, dead)) spot.villainRange[it] else 0f }
        val flopId = FlopIso.id(spot.board)
        val texture = Textures.of(flopId)
        val strength = BoardStrength(spot.board)
        val outs = IntArray(Combos.COUNT) { if (Combos.isLive(it, dead)) Draws.outs(spot.board, it) else 0 }
    }

    private val s = shared ?: Shared(spot)
    private val p = ctx.policy
    private val stack = spot.effectiveStackBb

    val hero get() = s.hero
    val villain get() = s.villain
    val flopId get() = s.flopId
    val texture get() = s.texture
    val strength get() = s.strength
    val outs get() = s.outs

    private val eqCache = IdentityHashMap<FloatArray, FlopEquity.Result>()
    private val payoffCache = IdentityHashMap<FloatArray, Double>()
    private val drawCache = IdentityHashMap<FloatArray, Double>()

    fun eqVs(range: FloatArray): FlopEquity.Result = eqCache.getOrPut(range) { equity.heroVs(range) }

    val heroVsVillain: FlopEquity.Result = eqVs(villain)
    private val villainVsHero: FlopEquity.Result = equity.villainVs(hero)

    /** ΔRange = hero range equity − 50%. */
    val rangeAdvantage: Double = heroVsVillain.rangeEquity(hero) - 0.5
    /** ΔNuts = hero nut-tier share − villain nut-tier share. */
    val nutAdvantage: Double = strength.share(hero, p.nutFraction) - strength.share(villain, p.nutFraction)

    private val villainLive = (0 until Combos.COUNT).filter { villain[it] > 0f }
    private val byStrength = villainLive.sortedByDescending { villainVsHero.equity(it).let { e -> if (e.isNaN()) 0f else e } }.toIntArray()
    private val byBluff = villainLive.sortedWith(
        compareByDescending<Int> { outs[it] }.thenBy { villainVsHero.equity(it).let { e -> if (e.isNaN()) 1f else e } }
    ).toIntArray()

    val villainModel = FlopVillainModel(
        byStrength, byBluff, ctx.profile, ctx.locks,
        FlopVillainModel.textureFactor(rangeAdvantage, nutAdvantage, texture.volatility),
    )

    val donkRead: DonkRead? = if (spot.isDonk || (spot.nodeType == FlopNodeType.VS_CHECK && spot.villainBetNode == FlopVillainNode.DONK))
        DonkAnalyzer.read(texture, -rangeAdvantage, -nutAdvantage, ctx.locks[FlopVillainNode.DONK]) else null

    /** Villain's first action at this spot (bet range = raise, check range = fold). */
    val villainFirstAction: ComboPartition =
        villainModel.firstAction(villain, spot.villainBetNode, if (spot.villainBetNode == FlopVillainNode.DONK) donkRead?.valueShare else null)

    // ---- sub-functions -------------------------------------------------------------------------------

    /** Weighted share of [range] holding a strong draw (≥ 8 outs). */
    fun drawDensity(range: FloatArray): Double = drawCache.getOrPut(range) {
        var t = 0.0; var d = 0.0
        for (k in range.indices) { val w = range[k]; if (w <= 0f) continue; t += w; if (outs[k] >= 8) d += w }
        if (t > 0) d / t else 0.0
    }

    /** How much hero's hand loses to villain's outs: nuts 0, strong 0.5, vulnerable made hand 1, draws 0.2, air 0.5. */
    fun vulnerability(h: Int): Double = when {
        isNut(h) -> 0.0
        isStrong(h) -> 0.5
        outs[h] > 0 -> 0.2
        strength.category[h] >= 1 -> 1.0
        else -> 0.5
    }

    /** R_post(SPR, Pos) penalized by villain's outs vs hero's vulnerability. */
    fun realization(h: Int, range: FloatArray, aggressor: Boolean, spr: Double): Double {
        val base = (if (spot.heroInPosition) p.realizationIp else p.realizationOop) *
            (if (aggressor) p.initiativeAggressor else p.initiativeCaller)
        val r = base * (1 - p.vulnerabilityK * drawDensity(range) * vulnerability(h))
        val low = ((p.lowSpr - spr) / p.lowSpr).coerceIn(0.0, 1.0)
        return r + (1 - r) * low
    }

    private fun payoff(range: FloatArray): Double = payoffCache.getOrPut(range) { strength.share(range, p.strongFraction) }

    fun implied(h: Int, range: FloatArray, pot: Double, behind: Double): Double {
        if (outs[h] == 0 || behind <= 0.0) return 0.0
        val ext = minOf(behind, pot * if (spot.heroInPosition) p.extractIp else p.extractOop)
        return outs[h] / 47.0 * payoff(range) * ext
    }

    fun isNut(h: Int) = strength.inTop(h, p.nutFraction)
    fun isStrong(h: Int) = strength.inTop(h, p.strongFraction)

    fun isMinBet(bet: Double, potBefore: Double): Boolean = bet <= p.minBetRatio * potBefore || bet <= 1.0

    class Branch(
        val value: Double, val equity: Float, val realization: Float, val potTotal: Double,
        val investment: Double, val rake: Double, val implied: Double, val allIn: Boolean,
    )

    /** Branch where the pot is [pot] after hero invested [invest] (relative to folding before investing). */
    fun called(h: Int, range: FloatArray, pot: Double, invest: Double, behindAfter: Double, aggressor: Boolean): Branch {
        val e = eqVs(range).equity(h).let { if (it.isNaN()) 0f else it }
        val behind = behindAfter.coerceAtLeast(0.0)
        if (behind <= p.commitSpr * pot) {
            val potAll = pot + 2 * behind
            val inv = invest + behind
            val rk = RakeModel.charge(ctx.rake, ctx.rakeAttribution, potAll, 0.0, e.toDouble())
            return Branch(e * potAll - inv - rk, e, 1f, potAll, inv, rk, 0.0, true)
        }
        val r = realization(h, range, aggressor, behind / pot)
        val eqR = minOf(e * r, MAX_EQR)
        val rk = RakeModel.charge(ctx.rake, ctx.rakeAttribution, pot, behind, eqR)
        val imp = implied(h, range, pot, behind)
        return Branch(eqR * pot - invest - rk + imp, e, r.toFloat(), pot, invest, rk, imp, false)
    }

    fun raiseTo(previousTotal: Double): Double = minOf(stack, p.raiseMultiplier * previousTotal)

    /**
     * Hero bets / raises to street total [raiseTo]. [potBefore] includes everything already in the pot
     * (villain's current bet included); [heroInv] / [vInv] = chips already in this street.
     * level 1 = bet (hero may jam over a raise), level 2 = raise (hero calls / folds a re-raise).
     */
    fun aggressive(h: Int, potBefore: Double, raiseTo: Double, heroInv: Double, vInv: Double, resp: ComboPartition, level: Int): FlopEvTerms {
        val x = minOf(raiseTo, stack)
        val a = x - heroInv
        val pr0 = resp.probabilities(h)
        val pf = pr0[0]
        var pc = pr0[1]; var pr = pr0[2]
        if (x >= stack) { pc += pr; pr = 0.0 }
        val potCalled = potBefore + a + (x - vInv)
        val callRange = if (x >= stack) resp.continueRange else resp.call
        val br = called(h, callRange, potCalled, a, stack - x, aggressor = true)
        val evR = if (pr > 0) vsReraise(h, potBefore + a, x, vInv, a, resp.raise, level) else 0.0
        return FlopEvTerms(
            pFold = pf, pCall = pc, pRaise = pr, foldWin = potBefore,
            equity = br.equity, realization = br.realization, potTotal = br.potTotal, investment = br.investment,
            rake = br.rake, implied = br.implied, evVsRaise = evR, calledEv = br.value, allIn = br.allIn,
            ev = pf * potBefore + pc * br.value + pr * evR,
        )
    }

    private fun vsReraise(h: Int, potAfterHero: Double, x: Double, vInv: Double, a: Double, raiseRange: FloatArray, level: Int): Double {
        val y = raiseTo(x)
        val potR = potAfterHero + (y - vInv)
        val toCall = y - x
        val callEv = called(h, raiseRange, potR + toCall, toCall, stack - y, aggressor = false).value
        var jamEv = Double.NEGATIVE_INFINITY
        if (level == 1 && y < stack) {
            val jr = villainModel.respondToJam(raiseRange)
            val pr = jr.probabilities(h)
            val fin = potR + (stack - x) + (stack - y)
            val ej = eqVs(jr.call).equity(h).let { if (it.isNaN()) 0f else it }.toDouble()
            val rk = RakeModel.charge(ctx.rake, ctx.rakeAttribution, fin, 0.0, ej)
            jamEv = pr[0] * potR + (pr[1] + pr[2]) * (ej * fin - (stack - x) - rk)
        }
        return -a + maxOf(0.0, callEv, jamEv)
    }

    /** Hero calls a bet: [potBefore] includes the bet, [toCall] = chips hero adds. */
    fun call(h: Int, potBefore: Double, toCall: Double, betRange: FloatArray): FlopEvTerms {
        val c = minOf(toCall, stack)
        val br = called(h, betRange, potBefore + c, c, stack - c, aggressor = false)
        return FlopEvTerms(
            pFold = 0.0, pCall = 1.0, pRaise = 0.0, foldWin = 0.0,
            equity = br.equity, realization = br.realization, potTotal = br.potTotal, investment = br.investment,
            rake = br.rake, implied = br.implied, evVsRaise = 0.0, calledEv = br.value, allIn = br.allIn, ev = br.value,
        )
    }

    /** Hero raises a bet of [bet] (villain's street total). */
    fun raiseVsBet(h: Int, potBefore: Double, bet: Double, betRange: FloatArray): FlopEvTerms =
        aggressive(h, potBefore, raiseTo(bet), 0.0, bet, villainModel.respondToRaise(betRange), level = 2)

    /**
     * Hero checks with [pot]: villain then bets with his [villainFirstAction] range (prob with card removal)
     * at profile size, else the hand checks through (realized vs his checking range, no initiative).
     */
    fun check(h: Int, pot: Double, villainActsAfter: Boolean): FlopEvTerms {
        val checkRange = villainFirstAction.fold
        val through = called(h, checkRange, pot, 0.0, stack, aggressor = false)
        if (!villainActsAfter) {
            return FlopEvTerms(0.0, 1.0, 0.0, 0.0, through.equity, through.realization, through.potTotal, 0.0,
                through.rake, through.implied, 0.0, through.value, through.allIn, through.value)
        }
        val pb = villainFirstAction.probabilities(h)[2]
        val betRange = villainFirstAction.raise
        val b = minOf(ctx.profile.betSizeFraction * pot, stack)
        val best = if (pb > 0) maxOf(0.0, call(h, pot + b, b, betRange).ev, raiseVsBet(h, pot + b, b, betRange).ev) else 0.0
        return FlopEvTerms(
            pFold = 0.0, pCall = 1 - pb, pRaise = pb, foldWin = 0.0,
            equity = through.equity, realization = through.realization, potTotal = through.potTotal, investment = 0.0,
            rake = through.rake, implied = through.implied, evVsRaise = best, calledEv = through.value,
            allIn = through.allIn, ev = (1 - pb) * through.value + pb * best,
        )
    }

    companion object {
        const val MAX_EQR = 0.98
    }
}
