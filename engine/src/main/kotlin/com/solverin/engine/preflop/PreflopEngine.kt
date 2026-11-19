package com.solverin.engine.preflop

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.eval.EquityTable
import com.solverin.engine.eval.RangeEquity
import com.solverin.engine.math.Blockers
import com.solverin.engine.math.ImpliedOdds
import com.solverin.engine.model.HandRanking
import com.solverin.engine.model.NodeLocks
import com.solverin.engine.model.VillainModel
import com.solverin.engine.model.VillainProfile
import com.solverin.engine.range.RangeRepository
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.range.SpotStrategy

/**
 * Preflop engine: no static charts. Every matrix is computed on demand by [PreflopMathEngine]
 * (master EV equation) from the villain profile, then mapped to frequencies by the stake policy.
 * Bounded LRU cache; snapshot() persists solved matrices.
 */
class PreflopEngine(
    table: EquityTable = EquityTable.get(),
    private val cacheSize: Int = 48,
) {
    private val equity = RangeEquity(table)
    val ranking = HandRanking(table)
    val implied = ImpliedOdds(ranking)

    private val models = HashMap<Pair<VillainProfile, NodeLocks>, VillainModel>()
    private val cache = object : LinkedHashMap<CacheKey, SpotAnalysis>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, SpotAnalysis>?) = size > cacheSize
    }

    private data class CacheKey(val spot: Int, val ctx: PreflopContext)

    fun villainModel(p: VillainProfile, locks: NodeLocks = NodeLocks.NONE): VillainModel =
        synchronized(models) { models.getOrPut(p to locks) { VillainModel(p, ranking, locks) } }

    fun villainModel(ctx: PreflopContext): VillainModel = villainModel(ctx.villain, ctx.locks)

    fun isValid(key: SpotKey, ctx: PreflopContext) = SpotBuilder(ctx, villainModel(ctx)).validate(key)

    /**
     * Hero's range reaching the node (class weights), derived from hero's own solved parent node.
     * Null = every hand reaches (first decision: RFI, facing an open, squeeze spot).
     */
    fun reach(key: SpotKey, ctx: PreflopContext): FloatArray? {
        val h = key.hero; val v = key.villain
        return when (key.scenario) {
            Scenario.RFI, Scenario.VS_RFI, Scenario.SQUEEZE, Scenario.VS_3BET_COLD -> null
            Scenario.VS_3BET -> analyze(SpotKey.of(Scenario.RFI, h), ctx).strategy.raiseWeights()
            Scenario.VS_SQUEEZE_OPENER -> analyze(SpotKey.of(Scenario.RFI, h), ctx).strategy.raiseWeights()
            Scenario.VS_SQUEEZE_CALLER -> {
                val s = analyze(SpotKey.of(Scenario.VS_RFI, h, key.caller!!), ctx).strategy
                FloatArray(HandClass.COUNT) { s.call(it) }
            }
            Scenario.VS_4BET -> {
                val parent = key.caller?.let { SpotKey.of(Scenario.SQUEEZE, h, v, it) } ?: SpotKey.of(Scenario.VS_RFI, h, v)
                analyze(parent, ctx).strategy.raiseWeights()
            }
            Scenario.VS_5BET -> analyze(SpotKey.of(Scenario.VS_3BET, h, v), ctx).strategy.raiseWeights()
        }
    }

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
        val vm = villainModel(ctx)
        val nodes = SpotBuilder(ctx, vm).build(key)
        val children = HashMap<Position, SpotAnalysis>()
        nodes.raise?.opponents?.forEach { o -> o.childKey?.let { children[o.position] = analyze(it, ctx) } }
        val math = PreflopMathEngine(ctx, equity, implied) { o -> children[o.position] }
        val policy = DecisionPolicy(ctx.policy)
        val n = HandClass.COUNT
        val call = nodes.call
        val raiseNode = nodes.raise
        val first = raiseNode?.mainOpponent
        val callTerms = call?.let { c -> Array(n) { h -> math.call(h, c) } }
        val raiseTerms = raiseNode?.let { r -> Array(n) { h -> math.raise(h, r) } }
        val valueEq = FloatArray(n)
        val foldEq = FloatArray(n)
        val surplus = DoubleArray(n)
        val fallbackCall = FloatArray(n)
        val b = SpotStrategy.Builder()
        // Continue discipline: facing a re-raise, and blind defense vs an open, a continue must beat folding by a margin.
        for (h in 0 until n) {
            val extraCall = ctx.policy.callEdge(key.scenario, key.hero, h) - ctx.policy.callEdgeBb
            val extraRaise = ctx.policy.raiseEdge(key.scenario, key.hero, h) - ctx.policy.raiseEdgeBb
            var raiseAdj = Double.NEGATIVE_INFINITY
            if (raiseNode != null) {
                valueEq[h] = math.valueEquity(h, raiseNode)
                raiseAdj = policy.adjustedRaise(raiseTerms!![h].ev) - extraRaise
                // VS_4BET: no 5-bet bluff jam into a 4-bettor folding less than fiveBetBluffMinFold.
                if (key.scenario == Scenario.VS_4BET && ctx.policy.heroDiscipline && valueEq[h] < 0.5f &&
                    raiseTerms[h].pFold < ctx.policy.fiveBetBluffMinFold) raiseAdj = Double.NEGATIVE_INFINITY
            }
            if (first != null) foldEq[h] = Blockers.foldProbability(h, first.response.fold, first.response.continueRange).toFloat()
            val callAdj = callTerms?.let { policy.adjustedCall(it[h].ev) - extraCall } ?: Double.NEGATIVE_INFINITY
            val mix = policy.mix(callAdj, raiseAdj)
            b.raise[h] = mix.raise
            b.call[h] = mix.call
            surplus[h] = raiseAdj - maxOf(0.0, callAdj)
            fallbackCall[h] = policy.mix(callAdj, Double.NEGATIVE_INFINITY).call
        }

        val valueMark = FloatArray(n) { h ->
            if (BluffBudget.isValue(key.scenario, h, valueEq[h], ctx.policy, ctx.effectiveStackBb)) 1f else 0f
        }
        var capExempt: BooleanArray? = null
        if (ctx.policy.bluffCap) {
            val fold = first?.let { BluffBudget.foldShare(it.response) } ?: 1.0
            val p = ctx.policy
            capExempt = raiseTerms?.let { rt -> BooleanArray(n) { h -> BluffBudget.capExempt(key.scenario, rt[h], p) } }
            val priority = raiseTerms?.let { rt -> BooleanArray(n) { h -> BluffBudget.capPriority(key.scenario, rt[h], p) } }
            BluffBudget.apply(
                BluffBudget.ratio(key.scenario, fold), b.raise, b.call, valueMark, surplus, fallbackCall,
                exempt = capExempt, priority = priority,
            )
        }

        var realSum = 0.0; var realW = 0.0
        for (h in 0 until n) {
            b.value[h] = b.raise[h] > 0f && valueMark[h] >= 0.5f
            if (callTerms != null && b.call[h] > 0f) {
                val w = (b.call[h] * HandClass.combos(h)).toDouble()
                realSum += w * callTerms[h].realization
                realW += w
            }
        }
        val strategy = b.build()
        val sprCall = call?.let { c ->
            val to = minOf(c.callTo, ctx.effectiveStackBb)
            (ctx.effectiveStackBb - to) / (c.potBefore + to - c.heroInvested)
        } ?: Double.NaN
        val sprRaise = raiseNode?.let { r ->
            val x = minOf(r.raiseTo, ctx.effectiveStackBb)
            val pot = r.potBefore + (x - r.heroInvested) + (x - (first?.invested ?: 0.0))
            (ctx.effectiveStackBb - x).coerceAtLeast(0.0) / pot
        } ?: Double.NaN
        val bd = if (key.scenario == Scenario.VS_RFI && key.hero.isBlind)
            BlindDefenseReport.of(key.hero, key.villain, ctx, vm, strategy, valueEq, if (realW > 0) realSum / realW else 0.0)
        else null
        val cc = if (call != null && callTerms != null)
            ColdCallModule.analyze(key, call, callTerms, strategy, ctx.policy) {
                SpotAnalysis.frequency(strategy, reach(key, ctx)) { s, h -> s.call(h) }
            }
        else null
        val bluffCap = if (ctx.policy.bluffCap && raiseNode != null) {
            BluffBudget.ratio(key.scenario, first?.let { BluffBudget.foldShare(it.response) } ?: 1.0)
        } else Double.POSITIVE_INFINITY
        return SpotAnalysis(key, strategy, callTerms, raiseTerms, valueEq, foldEq, bd, cc, sprCall, sprRaise,
            capExempt = capExempt, bluffRatio = bluffCap) { reach(key, ctx) }
    }

    /** Persists every cached matrix (for a given context) into a compact repository. */
    fun snapshot(ctx: PreflopContext): RangeRepository {
        val b = RangeRepository.Builder()
        synchronized(cache) { cache.filterKeys { it.ctx == ctx }.forEach { (k, a) -> b.put(SpotKey(k.spot), a.strategy) } }
        return b.build()
    }

    fun clearCache() = synchronized(cache) { cache.clear() }
}
