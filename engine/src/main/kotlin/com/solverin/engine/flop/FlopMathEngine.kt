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
    /** Hero vs third-player equities (3-way pots); required when the spot has an extra range. */
    private val extraEquity: EquityProvider? = null,
) {
    /** Board-only data reusable across contexts (node-lock recalculation). */
    class Shared(spot: FlopSpot) {
        private val dead = Combos.mask(spot.board)
        val hero = FloatArray(Combos.COUNT) { if (Combos.isLive(it, dead)) spot.heroRange[it] else 0f }
        val villain = FloatArray(Combos.COUNT) { if (Combos.isLive(it, dead)) spot.villainRange[it] else 0f }
        val extra: FloatArray? = spot.extraRange?.let { x -> FloatArray(Combos.COUNT) { if (Combos.isLive(it, dead)) x[it] else 0f } }
        val flopId = FlopIso.id(spot.board)
        val texture = Textures.of(flopId)
        val strength = BoardStrength(spot.board)
        val outs = IntArray(Combos.COUNT) { if (Combos.isLive(it, dead)) Draws.outs(spot.board, it) else 0 }
        /** Unpaired hands with two overcards or a backdoor flush draw (3 to a flush with a hole card). */
        val backdoor = BooleanArray(Combos.COUNT) { k ->
            if (!Combos.isLive(k, dead)) return@BooleanArray false
            val top = spot.board.maxOf { it ushr 2 }
            val c1 = Combos.C1[k]; val c2 = Combos.C2[k]
            val over = (c1 ushr 2) > top && (c2 ushr 2) > top
            var bdfd = false
            for (suit in 0 until 4) {
                val hole = (if ((c1 and 3) == suit) 1 else 0) + (if ((c2 and 3) == suit) 1 else 0)
                if (hole > 0 && hole + spot.board.count { (it and 3) == suit } == 3) bdfd = true
            }
            over || bdfd
        }
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
    val backdoor get() = s.backdoor

    private val eqCache = IdentityHashMap<FloatArray, FlopEquity.Result>()
    private val payoffCache = IdentityHashMap<FloatArray, Double>()
    private val drawCache = IdentityHashMap<FloatArray, Double>()

    fun eqVs(range: FloatArray): FlopEquity.Result = eqCache.getOrPut(range) { equity.heroVs(range) }

    val heroVsVillain: FlopEquity.Result = eqVs(villain)
    private val villainVsHero: FlopEquity.Result = equity.villainVs(hero)

    /** ΔRange = hero range equity − 50%. */
    val rangeAdvantage: Double = heroVsVillain.rangeEquity(hero) - 0.5
    /** Weighted share of hero's range with equity > nutEquity vs villain's range. */
    val heroNutShare: Double = highEquityShare(hero, heroVsVillain, p.nutEquity)
    /** Weighted share of villain's range with equity > nutEquity vs hero's range. */
    val villainNutShare: Double = highEquityShare(villain, villainVsHero, p.nutEquity)
    /** ΔNuts = hero share of combos with > 85% equity − villain share (card-removal exact equities). */
    val nutAdvantage: Double = heroNutShare - villainNutShare

    private fun highEquityShare(range: FloatArray, eq: FlopEquity.Result, threshold: Double): Double {
        var t = 0.0; var s = 0.0
        for (k in range.indices) {
            val w = range[k]
            if (w <= 0f) continue
            val e = eq.equity(k)
            if (e.isNaN()) continue
            t += w
            if (e > threshold) s += w
        }
        return if (t > 0) s / t else 0.0
    }

    private val villainLive = (0 until Combos.COUNT).filter { villain[it] > 0f }
    private val byStrength = villainLive.sortedByDescending { villainVsHero.equity(it).let { e -> if (e.isNaN()) 0f else e } }.toIntArray()
    private val byBluff = villainLive.sortedWith(
        compareByDescending<Int> { outs[it] }.thenBy { villainVsHero.equity(it).let { e -> if (e.isNaN()) 1f else e } }
    ).toIntArray()
    private val byWeakMade = villainLive.sortedWith(
        compareByDescending<Int> { if (strength.category[it] >= 1 && !strength.inTop(it, p.strongFraction)) 1 else 0 }
            .thenBy { villainVsHero.equity(it).let { e -> if (e.isNaN()) 1f else e } }
    ).toIntArray()

    val villainModel = FlopVillainModel(
        byStrength, byBluff, ctx.profile, ctx.locks,
        FlopVillainModel.textureFactor(rangeAdvantage, nutAdvantage, texture.volatility),
        byWeakMade, p.minBetRatio,
    )

    val donkRead: DonkRead? = if (spot.isDonk || (spot.nodeType == FlopNodeType.VS_CHECK && spot.villainBetNode == FlopVillainNode.DONK))
        DonkAnalyzer.read(texture, -rangeAdvantage, -nutAdvantage, ctx.locks[FlopVillainNode.DONK]) else null

    /** Villain's first action at this spot (bet range = raise, check range = fold). */
    val villainFirstAction: ComboPartition =
        villainModel.firstAction(villain, spot.villainBetNode, if (spot.villainBetNode == FlopVillainNode.DONK) donkRead?.valueShare else null)

    // ---- third player (3-way pots) -------------------------------------------------------------------

    val extra: FloatArray? get() = s.extra
    private val extraEqCache = IdentityHashMap<FloatArray, FlopEquity.Result>()

    init {
        require(s.extra == null || extraEquity != null) { "3-way spot needs an extra equity provider" }
    }

    fun eqVsExtra(range: FloatArray): FlopEquity.Result = extraEqCache.getOrPut(range) { extraEquity!!.heroVs(range) }

    /** Third player's response model (never the preflop aggressor). */
    val extraModel: FlopVillainModel? = s.extra?.let { x ->
        val xVsHero = extraEquity!!.villainVs(hero)
        val live = (0 until Combos.COUNT).filter { x[it] > 0f }
        val strengthOrder = live.sortedByDescending { xVsHero.equity(it).let { e -> if (e.isNaN()) 0f else e } }.toIntArray()
        val bluffOrder = live.sortedWith(
            compareByDescending<Int> { outs[it] }.thenBy { xVsHero.equity(it).let { e -> if (e.isNaN()) 1f else e } }
        ).toIntArray()
        FlopVillainModel(strengthOrder, bluffOrder, ctx.profile, ctx.locks,
            FlopVillainModel.textureFactor(rangeAdvantage, nutAdvantage, texture.volatility), bluffOrder, p.minBetRatio)
    }

    /** Third player's first action (stab / check) when he acts; null heads-up. */
    val extraFirstAction: ComboPartition? = extraModel?.firstAction(s.extra!!, FlopVillainNode.STAB)

    /** True when the third player acts after hero at this node (sandwich). */
    val extraActsAfterHero: Boolean get() = s.extra != null && spot.nodeType != FlopNodeType.VS_CHECK

    /** Third player's range at hero's decision: his checking range if he already checked, else his full range. */
    val extraNodeRange: FloatArray? = s.extra?.let { if (spot.nodeType == FlopNodeType.VS_CHECK) extraFirstAction!!.fold else it }

    /** n-way equity approximation from pairwise equities (opponent-vs-opponent assumed even). */
    fun fieldEquity(pairwise: List<Float>): Float {
        if (pairwise.size == 1) return pairwise[0]
        var heroShare = 1.0
        for (e in pairwise) heroShare *= e
        var others = 0.0
        for (e in pairwise) others += (1 - e) / pairwise.size
        val s = heroShare + others
        return if (s <= 0.0) 0f else (heroShare / s).toFloat()
    }

    // ---- sub-functions -------------------------------------------------------------------------------

    /** Weighted share of [range] holding a strong draw (≥ 8 outs). */
    fun drawDensity(range: FloatArray): Double = drawCache.getOrPut(range) {
        var t = 0.0; var d = 0.0
        for (k in range.indices) { val w = range[k]; if (w <= 0f) continue; t += w; if (outs[k] >= 8) d += w }
        if (t > 0) d / t else 0.0
    }

    /**
     * How much hero's hand loses to villain's outs: nuts 0, strong 0.5, vulnerable made hand 1, draws 0.2;
     * unpaired: overcards / backdoor equity IP backdoorVulnerabilityIp, otherwise airVulnerability.
     */
    fun vulnerability(h: Int): Double = when {
        isNut(h) -> 0.0
        isStrong(h) -> 0.5
        outs[h] > 0 -> 0.2
        strength.category[h] >= 1 -> 1.0
        spot.heroInPosition && backdoor[h] -> p.backdoorVulnerabilityIp
        else -> p.airVulnerability
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

    /** Backdoor flush draw plus at least one overcard to the board (PostflopMassiveExploitOverride on flop bets). */
    fun backdoorOvercard(h: Int): Boolean {
        val c1 = Combos.C1[h]; val c2 = Combos.C2[h]
        val top = spot.board.maxOf { it ushr 2 }
        if ((c1 ushr 2) <= top && (c2 ushr 2) <= top) return false
        for (suit in 0 until 4) {
            val hole = (if ((c1 and 3) == suit) 1 else 0) + (if ((c2 and 3) == suit) 1 else 0)
            if (hole > 0 && hole + spot.board.count { (it and 3) == suit } == 3) return true
        }
        return false
    }

    fun isNut(h: Int) = strength.inTop(h, p.nutFraction)
    fun isStrong(h: Int) = strength.inTop(h, p.strongFraction)

    fun isMinBet(bet: Double, potBefore: Double): Boolean = bet <= p.minBetRatio * potBefore || bet <= 1.0

    class Branch(
        val value: Double, val equity: Float, val realization: Float, val potTotal: Double,
        val investment: Double, val rake: Double, val implied: Double, val allIn: Boolean,
    )

    /** Branch where the pot is [pot] after hero invested [invest] (relative to folding before investing). */
    fun called(h: Int, range: FloatArray, pot: Double, invest: Double, behindAfter: Double, aggressor: Boolean): Branch =
        calledMulti(h, range, null, pot, invest, behindAfter, aggressor)

    /**
     * Branch against the villain range [vRange] and / or the third player's range [xRange] (null = not in the
     * branch). Several opponents: field equity, realization × MULTIWAY_DECAY per extra opponent, and an all-in
     * projection where every remaining opponent matches hero's remaining stack.
     */
    fun calledMulti(h: Int, vRange: FloatArray?, xRange: FloatArray?, pot: Double, invest: Double, behindAfter: Double, aggressor: Boolean): Branch {
        require(vRange != null || xRange != null)
        val pair = ArrayList<Float>(2)
        vRange?.let { pair += eqVs(it).equity(h).let { e -> if (e.isNaN()) 0f else e } }
        xRange?.let { pair += eqVsExtra(it).equity(h).let { e -> if (e.isNaN()) 0f else e } }
        val e = fieldEquity(pair)
        val opponents = pair.size
        val main = vRange ?: xRange!!
        val behind = behindAfter.coerceAtLeast(0.0)
        if (behind <= p.commitSpr * pot) {
            val potAll = pot + behind * (1 + opponents)
            val inv = invest + behind
            val rk = RakeModel.charge(ctx.rake, ctx.rakeAttribution, potAll, 0.0, e.toDouble())
            return Branch(e * potAll - inv - rk, e, 1f, potAll, inv, rk, 0.0, true)
        }
        var r = realization(h, main, aggressor, behind / pot)
        if (opponents > 1) r *= Math.pow(MULTIWAY_DECAY, (opponents - 1).toDouble())
        val eqR = minOf(e * r, MAX_EQR)
        val rk = RakeModel.charge(ctx.rake, ctx.rakeAttribution, pot, behind, eqR)
        var imp = implied(h, main, pot, behind)
        if (opponents > 1) imp *= MULTIWAY_DECAY
        return Branch(eqR * pot - invest - rk + imp, e, r.toFloat(), pot, invest, rk, imp, false)
    }

    /**
     * Raise size over a street total [previousTotal] with [potWithBet] in the middle (bet included):
     * multiplier × bet, or pot-weighted (prev + raisePotFraction × (potWithBet + prev)) over a min-bet.
     */
    fun raiseTo(previousTotal: Double, potWithBet: Double): Double {
        val base = p.raiseMultiplier * previousTotal
        val potBeforeBet = potWithBet - previousTotal
        val weighted = if (isMinBet(previousTotal, potBeforeBet)) previousTotal + p.raisePotFraction * (potWithBet + previousTotal) else 0.0
        return minOf(stack, maxOf(base, weighted))
    }

    /**
     * Hero bets / raises to street total [raiseTo]. [potBefore] includes everything already in the pot
     * (villain's current bet included); [heroInv] / [vInv] = chips already in this street.
     * level 1 = bet (hero may jam over a raise), level 2 = raise (hero calls / folds a re-raise).
     */
    fun aggressive(
        h: Int, potBefore: Double, raiseTo: Double, heroInv: Double, vInv: Double, resp: ComboPartition, level: Int,
        extraResp: ComboPartition? = null, xInv: Double = 0.0,
    ): FlopEvTerms {
        if (extraResp != null) return aggressiveMulti(h, potBefore, raiseTo, heroInv, vInv, resp, level, extraResp, xInv)
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

    /**
     * 3-way aggression: villain and third player respond independently (card removal per player).
     *  P(all fold) = fV·fX; P(re-raise) = 1 − (1 − rV)(1 − rX) (played heads-up vs the re-raiser);
     *  called cases: only villain / only third player / both, each valued with its own pot and field equity.
     */
    private fun aggressiveMulti(
        h: Int, potBefore: Double, raiseTo: Double, heroInv: Double, vInv: Double, resp: ComboPartition, level: Int,
        extraResp: ComboPartition, xInv: Double,
    ): FlopEvTerms {
        val x = minOf(raiseTo, stack)
        val a = x - heroInv
        val v = resp.probabilities(h)
        val w = extraResp.probabilities(h)
        var fv = v[0]; var cv = v[1]; var rv = v[2]
        var fx = w[0]; var cx = w[1]; var rx = w[2]
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
            val evV = if (wV > 0) vsReraise(h, potBefore + a, x, vInv, a, resp.raise, level) else 0.0
            val evX = if (wX > 0) reraiseResponse(h, potBefore + a, x, xInv, a, extraResp.raise, level, extraModel!!, extra = true).value else 0.0
            (wV * evV + wX * evX) / pr
        } else 0.0
        return FlopEvTerms(
            pFold = pf, pCall = pc, pRaise = pr, foldWin = potBefore,
            equity = bV.equity, realization = bV.realization, potTotal = bV.potTotal, investment = bV.investment,
            rake = bV.rake, implied = bV.implied, evVsRaise = evR, calledEv = calledEv, allIn = bV.allIn,
            ev = pf * potBefore + pc * calledEv + pr * evR,
        )
    }

    /**
     * Hero's decision after villain re-raises hero's street total [x] (hero added [a] this action):
     * fold (−a), call the re-raise (valued all-in when it leaves < commitSpr × pot behind) or, at level 1, jam.
     */
    class ReraiseResponse(
        val reraiseTo: Double,
        val toCall: Double,
        val call: Branch,
        val jamEv: Double,
        val invested: Double,
    ) {
        val callEv: Double get() = call.value
        val best: FlopAction get() = when {
            maxOf(callEv, jamEv) <= 0.0 -> FlopAction.FOLD
            jamEv > callEv -> FlopAction.RAISE
            else -> FlopAction.CALL
        }
        /** Hero continues by committing the whole stack (all-in call or jam). */
        val stacksOff: Boolean get() = best == FlopAction.RAISE || (best == FlopAction.CALL && call.allIn)
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
        if (level == 1 && y < stack) {
            val jr = model.respondToJam(raiseRange)
            val pr = jr.probabilities(h)
            val fin = potR + (stack - x) + (stack - y)
            val ej = (if (extra) eqVsExtra(jr.call) else eqVs(jr.call)).equity(h).let { if (it.isNaN()) 0f else it }.toDouble()
            val rk = RakeModel.charge(ctx.rake, ctx.rakeAttribution, fin, 0.0, ej)
            jamEv = pr[0] * potR + (pr[1] + pr[2]) * (ej * fin - (stack - x) - rk)
            if (ej < 0.5 && jamEv < maxOf(0.0, callBranch.value) + p.stackOffBluffMarginBb) jamEv = Double.NEGATIVE_INFINITY
        }
        return ReraiseResponse(y, toCall, callBranch, jamEv, a)
    }

    /** Hero bets / raises to [raiseTo] into [resp]; returns hero's response to villain's re-raise. */
    fun reraiseResponse(h: Int, potBefore: Double, raiseTo: Double, heroInv: Double, vInv: Double, resp: ComboPartition, level: Int): ReraiseResponse {
        val x = minOf(raiseTo, stack)
        val a = x - heroInv
        return reraiseResponse(h, potBefore + a, x, vInv, a, resp.raise, level)
    }

    private fun vsReraise(h: Int, potAfterHero: Double, x: Double, vInv: Double, a: Double, raiseRange: FloatArray, level: Int): Double =
        reraiseResponse(h, potAfterHero, x, vInv, a, raiseRange, level).value

    /** Hero calls a bet: [potBefore] includes the bet, [toCall] = chips hero adds. */
    fun call(h: Int, potBefore: Double, toCall: Double, betRange: FloatArray): FlopEvTerms {
        val c = minOf(toCall, stack)
        if (extraActsAfterHero && c > 0.0) return callSandwiched(h, potBefore, c, betRange)
        val br = called(h, betRange, potBefore + c, c, stack - c, aggressor = false)
        return FlopEvTerms(
            pFold = 0.0, pCall = 1.0, pRaise = 0.0, foldWin = 0.0,
            equity = br.equity, realization = br.realization, potTotal = br.potTotal, investment = br.investment,
            rake = br.rake, implied = br.implied, evVsRaise = 0.0, calledEv = br.value, allIn = br.allIn, ev = br.value,
        )
    }

    /**
     * Hero calls with the third player still to act (sandwich): he folds (heads-up pot), calls (3-way pot) or
     * squeezes (hero gives up the call).
     */
    private fun callSandwiched(h: Int, potBefore: Double, c: Double, betRange: FloatArray): FlopEvTerms {
        // The third player faces the bet (and hero's call): continue rate from his MDF at that price.
        val xResp = extraModel!!.respondToBet(extraNodeRange!!, potBefore, c)
        val xr = xResp.probabilities(h)
        val hu = called(h, betRange, potBefore + c, c, stack - c, aggressor = false)
        val xCall = xResp.call
        val mw = calledMulti(h, betRange, xCall, potBefore + 2 * c, c, stack - c, aggressor = false)
        val pc = xr[0] + xr[1]
        val calledEv = if (pc > 0) (xr[0] * hu.value + xr[1] * mw.value) / pc else 0.0
        return FlopEvTerms(
            pFold = 0.0, pCall = pc, pRaise = xr[2], foldWin = 0.0,
            equity = hu.equity, realization = hu.realization, potTotal = hu.potTotal, investment = hu.investment,
            rake = hu.rake, implied = hu.implied, evVsRaise = -c, calledEv = calledEv, allIn = hu.allIn,
            ev = pc * calledEv - xr[2] * c,
        )
    }

    /** Hero raises a bet of [bet] (villain's street total); a third player behind responds to the raise too. */
    fun raiseVsBet(h: Int, potBefore: Double, bet: Double, betRange: FloatArray): FlopEvTerms {
        val to = raiseTo(bet, potBefore)
        val xResp = if (extraActsAfterHero) extraModel!!.respondToBet(extraNodeRange!!, potBefore, minOf(to, stack)) else null
        return aggressive(h, potBefore, to, 0.0, bet, villainModel.respondToRaise(betRange), level = 2, extraResp = xResp)
    }

    /**
     * Hero checks with [pot]: villain then bets with his [villainFirstAction] range (prob with card removal)
     * at profile size, else the hand checks through (realized vs his checking range, no initiative).
     */
    fun check(h: Int, pot: Double, villainActsAfter: Boolean): FlopEvTerms {
        if (extra != null) return checkMulti(h, pot, villainActsAfter)
        val checkRange = villainFirstAction.fold
        val through = called(h, checkRange, pot, 0.0, stack, aggressor = false)
        if (!villainActsAfter) {
            return FlopEvTerms(0.0, 1.0, 0.0, 0.0, through.equity, through.realization, through.potTotal, 0.0,
                through.rake, through.implied, 0.0, through.value, through.allIn, through.value)
        }
        val pb = villainFirstAction.probabilities(h)[2]
        val betRange = villainFirstAction.raise
        val b = minOf(ctx.profile.betSizeFraction * pot, stack)
        val best = if (pb > 0 && b > 0.0) {
            val cl = call(h, pot + b, b, betRange)
            val rs = if (b < stack) raiseVsBet(h, pot + b, b, betRange).ev else Double.NEGATIVE_INFINITY
            // Min-bet rule inside the check subtree: with pot-odds equity a fold is not an option.
            val foldEv = if (isMinBet(b, pot) && cl.equity >= b / (pot + 2 * b)) Double.NEGATIVE_INFINITY else 0.0
            maxOf(foldEv, cl.ev, rs)
        } else 0.0
        return FlopEvTerms(
            pFold = 0.0, pCall = 1 - pb, pRaise = pb, foldWin = 0.0,
            equity = through.equity, realization = through.realization, potTotal = through.potTotal, investment = 0.0,
            rake = through.rake, implied = through.implied, evVsRaise = best, calledEv = through.value,
            allIn = through.allIn, ev = (1 - pb) * through.value + pb * best,
        )
    }

    /**
     * 3-way check: when both opponents act after hero, the villain bets first (prob pV) else the third player
     * may stab (prob pX); hero answers the villain's bet with fold / call (sandwiched) / raise and the third
     * player's stab with fold / call. If nobody bets the hand is realized 3-way vs both checking ranges.
     */
    private fun checkMulti(h: Int, pot: Double, opponentsActAfter: Boolean): FlopEvTerms {
        val xCheck = if (opponentsActAfter) extraFirstAction!!.fold else extraNodeRange!!
        val through = calledMulti(h, villainFirstAction.fold, xCheck, pot, 0.0, stack, aggressor = false)
        if (!opponentsActAfter) {
            return FlopEvTerms(0.0, 1.0, 0.0, 0.0, through.equity, through.realization, through.potTotal, 0.0,
                through.rake, through.implied, 0.0, through.value, through.allIn, through.value)
        }
        val pv = villainFirstAction.probabilities(h)[2]
        val px = extraFirstAction!!.probabilities(h)[2]
        val b = minOf(ctx.profile.betSizeFraction * pot, stack)
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
        return FlopEvTerms(
            pFold = 0.0, pCall = 1 - pb, pRaise = pb, foldWin = 0.0,
            equity = through.equity, realization = through.realization, potTotal = through.potTotal, investment = 0.0,
            rake = through.rake, implied = through.implied, evVsRaise = best, calledEv = through.value,
            allIn = through.allIn, ev = (1 - pb) * through.value + pb * best,
        )
    }

    companion object {
        const val MAX_EQR = 0.98
        const val MULTIWAY_DECAY = 0.88
    }
}
