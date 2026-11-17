package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.flop.*
import com.solverin.engine.range.RangeParser

/** Shared postflop test fixtures. */
object FlopBoards {
    const val CO_OPEN = "22+,A2s+,A8o+,K7s+,KTo+,Q9s+,QTo+,J9s+,JTo,T8s+,97s+,86s+,76s,65s"
    const val BB_DEFEND = "22-JJ,A2s-AQs,K2s-KQs,K9o-KQo,Q8s+,QTo+,J8s+,JTo,T7s+,T9o,96s+,98o,85s+,87o,74s+,76o,63s+,65o,53s+,43s"
    const val BTN_FLAT = "22-JJ,A9s-AQs,KTs-KQs,QTs+,J9s+,T8s+,97s+,86s+,75s+,65s,54s,AJo-AQo,KQo"

    fun board(s: String): IntArray = s.chunked(2).map { Card.parse(it) }.toIntArray()
    fun range(notation: String, b: IntArray): FloatArray = Combos.fromClassWeights(RangeParser.parse(notation), Combos.mask(b))

    /** Deterministic representatives of each (suit texture × connectivity × pairing) bucket. */
    fun textureBoards(perBucket: Int): List<IntArray> {
        val buckets = HashMap<Triple<SuitTexture, Connectivity, Pairing>, MutableList<Int>>()
        for (id in 0 until FlopIso.COUNT) {
            val t = Textures.of(id)
            buckets.getOrPut(Triple(t.suits, t.connectivity, t.pairing)) { ArrayList() } += id
        }
        return buckets.toSortedMap(compareBy({ it.first }, { it.second }, { it.third })).values.flatMap { ids ->
            val step = maxOf(1, ids.size / perBucket)
            ids.filterIndexed { i, _ -> i % step == 0 }.take(perBucket).map { FlopIso.representative(it) }
        }
    }

    /** Villain archetypes node-locked at every flop node. */
    fun archetype(foldVsBet: Double, raiseVsBet: Double, valueShare: Double, foldVsRaise: Double, reraise: Double,
                  foldVsJam: Double, betFrequency: Double, betValueShare: Double): FlopNodeLocks = FlopNodeLocks.NONE
        .with(FlopVillainNode.VS_BET, FlopNodeLock(fold = foldVsBet, raise = raiseVsBet, valueShare = valueShare))
        .with(FlopVillainNode.VS_DONK, FlopNodeLock(fold = foldVsBet, raise = raiseVsBet, valueShare = valueShare))
        .with(FlopVillainNode.VS_RAISE, FlopNodeLock(fold = foldVsRaise, raise = reraise))
        .with(FlopVillainNode.VS_JAM, FlopNodeLock(fold = foldVsJam))
        .with(FlopVillainNode.CBET, FlopNodeLock(frequency = betFrequency, valueShare = betValueShare))
        .with(FlopVillainNode.STAB, FlopNodeLock(frequency = betFrequency, valueShare = betValueShare))
        .with(FlopVillainNode.DONK, FlopNodeLock(frequency = betFrequency / 2, valueShare = betValueShare))

    val STATION = archetype(0.12, 0.04, 0.9, 0.20, 0.04, 0.25, 0.30, 0.85)
    val MANIAC = archetype(0.20, 0.35, 0.45, 0.20, 0.35, 0.10, 0.90, 0.40)
    val OVERFOLDER = archetype(0.75, 0.04, 0.95, 0.80, 0.03, 0.85, 0.35, 0.90)
    val NIT = archetype(0.65, 0.05, 0.95, 0.75, 0.05, 0.80, 0.30, 0.95)
}
