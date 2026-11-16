package com.solverin.engine.preflop

import com.solverin.engine.core.HandClass
import com.solverin.engine.eval.EquityTable
import com.solverin.engine.eval.RangeEquity
import com.solverin.engine.math.Blockers
import com.solverin.engine.math.Playability
import com.solverin.engine.math.Realization
import com.solverin.engine.model.HandRanking
import com.solverin.engine.model.VillainModel
import com.solverin.engine.model.VillainProfile
import com.solverin.engine.range.RangeRepository
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.range.SpotStrategy

/**
 * Rule-based preflop engine: no static charts. Every matrix is computed on demand from
 * EV (equity x realization - rake), playability, position, villain profile and blockers.
 * Bounded LRU cache; snapshot() persists solved matrices.
 */
class PreflopEngine(
    table: EquityTable = EquityTable.get(),
    private val cacheSize: Int = 48,
) {
    private val equity = RangeEquity(table)
    val ranking = HandRanking(table)

    private val models = HashMap<VillainProfile, VillainModel>()
    private val cache = object : LinkedHashMap<CacheKey, SpotAnalysis>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, SpotAnalysis>?) = size > cacheSize
    }

    private data class CacheKey(val spot: Int, val ctx: PreflopContext)

    fun villainModel(p: VillainProfile): VillainModel = synchronized(models) { models.getOrPut(p) { VillainModel(p, ranking) } }

    fun isValid(key: SpotKey, ctx: PreflopContext) = SpotBuilder(ctx, villainModel(ctx.villain)).validate(key)

    fun strategy(key: SpotKey, ctx: PreflopContext): SpotStrategy = analyze(key, ctx).strategy

    fun decide(key: SpotKey, hand: String, ctx: PreflopContext): HandDecision = analyze(key, ctx).decision(HandClass.parse(hand))

    fun analyze(key: SpotKey, ctx: PreflopContext): SpotAnalysis {
        val ck = CacheKey(key.packed, ctx)
        synchronized(cache) { cache[ck]?.let { return it } }
        val result = solve(key, ctx)
        synchronized(cache) { cache[ck] = result }
        return result
    }

    private fun solve(key: SpotKey, ctx: PreflopContext): SpotAnalysis {
        val vm = villainModel(ctx.villain)
        val nodes = SpotBuilder(ctx, vm).build(key)
        val calc = EvCalculator(ctx, equity)
        val policy = DecisionPolicy(ctx.policy)
        val n = HandClass.COUNT
        val evCall = FloatArray(n) { Float.NaN }
        val evRaise = FloatArray(n) { Float.NaN }
        val valueEq = FloatArray(n)
        val foldEq = FloatArray(n)
        val b = SpotStrategy.Builder()
        val call = nodes.call
        val raiseNode = nodes.raise
        val first = raiseNode?.opponents?.firstOrNull()
        val callSpr = call?.let {
            (ctx.effectiveStackBb - minOf(it.callTo, ctx.effectiveStackBb)).coerceAtLeast(0.0) / (it.potBefore + it.callTo - it.heroInvested)
        } ?: 0.0
        // All-in calls have no postflop: no OOP penalty / playability floor.
        val oop = call != null && !call.heroInPosition && callSpr > ALL_IN_SPR
        val surplus = DoubleArray(n)
        val fallbackCall = FloatArray(n)

        for (h in 0 until n) {
            var raiseAdj = Double.NEGATIVE_INFINITY
            if (raiseNode != null) {
                val er = calc.raise(h, raiseNode)
                evRaise[h] = er.toFloat()
                valueEq[h] = calc.valueEquity(h, raiseNode)
                raiseAdj = policy.adjustedRaise(er)
            }
            if (first != null) foldEq[h] = Blockers.foldProbability(h, first.response.fold, first.response.continueRange).toFloat()
            var callAdj = Double.NEGATIVE_INFINITY
            if (call != null) {
                val ec = calc.call(h, call)
                evCall[h] = ec.toFloat()
                callAdj = policy.adjustedCall(ec, oop, Playability.score(h, callSpr))
            }
            val mix = policy.mix(callAdj, raiseAdj)
            b.raise[h] = mix.raise
            b.call[h] = mix.call
            surplus[h] = raiseAdj - maxOf(0.0, callAdj)
            fallbackCall[h] = policy.mix(callAdj, Double.NEGATIVE_INFINITY).call
        }

        BluffBudget.apply(BluffBudget.ratio(key.scenario, ctx.villain), b.raise, b.call, valueEq, surplus, fallbackCall)

        var realSum = 0.0; var realW = 0.0
        for (h in 0 until n) {
            b.value[h] = b.raise[h] > 0f && valueEq[h] >= 0.5f
            if (call != null && b.call[h] > 0f) {
                val w = (b.call[h] * HandClass.combos(h)).toDouble()
                realSum += w * Realization.factor(h, callSpr, call.heroInPosition, false, call.villainRanges.size + 1)
                realW += w
            }
        }
        val strategy = b.build()
        val sprCall = if (call != null) callSpr else Double.NaN
        val sprRaise = raiseNode?.let { r ->
            val x = minOf(r.raiseTo, ctx.effectiveStackBb)
            val pot = r.potBefore + (x - r.heroInvested) + (x - (first?.invested ?: 0.0))
            (ctx.effectiveStackBb - x).coerceAtLeast(0.0) / pot
        } ?: Double.NaN
        val bd = if (key.scenario == Scenario.VS_RFI && key.hero.isBlind)
            BlindDefenseReport.of(key.hero, key.villain, ctx, vm, strategy, valueEq, if (realW > 0) realSum / realW else 0.0)
        else null
        return SpotAnalysis(key, strategy, evCall, evRaise, valueEq, foldEq, bd, sprCall, sprRaise)
    }

    /** Persists every cached matrix (for a given context) into a compact repository. */
    fun snapshot(ctx: PreflopContext): RangeRepository {
        val b = RangeRepository.Builder()
        synchronized(cache) { cache.filterKeys { it.ctx == ctx }.forEach { (k, a) -> b.put(SpotKey(k.spot), a.strategy) } }
        return b.build()
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    companion object {
        private const val ALL_IN_SPR = 0.05
    }
}
