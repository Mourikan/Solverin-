package com.solverin.engine.preflop

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.range.ActionMix
import com.solverin.engine.range.RangeRepository
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.range.SpotStrategy

/**
 * Entry point. Thread-safe, bounded LRU of adjusted spots (each ~362 B).
 */
class PreflopEngine(
    val repository: RangeRepository = RangeRepository.baseline(),
    private val adjuster: ExploitAdjuster = ExploitAdjuster(),
    private val cacheSize: Int = 64,
) {
    private val cache = object : LinkedHashMap<CacheKey, SpotStrategy>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, SpotStrategy>?) = size > cacheSize
    }

    private data class CacheKey(val spot: Int, val ctx: PreflopContext)

    fun baseline(key: SpotKey): SpotStrategy = repository.require(key)

    fun strategy(key: SpotKey, ctx: PreflopContext): SpotStrategy {
        val ck = CacheKey(key.packed, ctx)
        synchronized(cache) { cache[ck]?.let { return it } }
        val result = adjuster.adjust(key, baseline(key), ctx, villainRanges(key))
        synchronized(cache) { cache[ck] = result }
        return result
    }

    fun decide(key: SpotKey, hand: String, ctx: PreflopContext): ActionMix =
        strategy(key, ctx).mix(HandClass.parse(hand))

    /**
     * Villain's range at the node, split into continue vs fold against hero's raise.
     * Built from the repository itself: prior (how villain got here) x response chart.
     */
    fun villainRanges(key: SpotKey): ExploitAdjuster.VillainRanges? {
        val h = key.hero; val v = key.villain
        val prior: FloatArray
        val cont: FloatArray
        when (key.scenario) {
            Scenario.RFI -> {
                val defend = repository[SpotKey.of(Scenario.VS_RFI, Position.BB, h)] ?: return null
                prior = FloatArray(HandClass.COUNT) { 1f }
                cont = defend.continueWeights()
            }
            Scenario.VS_RFI, Scenario.SQUEEZE -> {
                prior = repository[SpotKey.of(Scenario.RFI, v)]?.raiseWeights() ?: return null
                cont = repository[SpotKey.of(Scenario.VS_3BET, v, h)]?.continueWeights() ?: return null
            }
            Scenario.VS_3BET -> {
                prior = repository[SpotKey.of(Scenario.VS_RFI, v, h)]?.raiseWeights() ?: return null
                cont = repository[SpotKey.of(Scenario.VS_4BET, v, h)]?.continueWeights() ?: return null
            }
            Scenario.VS_4BET -> {
                val fourBet = repository[SpotKey.of(Scenario.VS_3BET, v, h)] ?: return null
                prior = fourBet.raiseWeights()
                val valueOnly = fourBet.valueRaiseWeights()
                cont = FloatArray(HandClass.COUNT) { if (prior[it] > 0f) valueOnly[it] / prior[it] else 0f }
            }
        }
        val c = FloatArray(HandClass.COUNT) { prior[it] * cont[it] }
        val f = FloatArray(HandClass.COUNT) { prior[it] * (1f - cont[it]).coerceAtLeast(0f) }
        return ExploitAdjuster.VillainRanges(c, f)
    }

    fun clearCache() = synchronized(cache) { cache.clear() }
}
