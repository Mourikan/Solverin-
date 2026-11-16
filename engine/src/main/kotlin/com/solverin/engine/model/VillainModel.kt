package com.solverin.engine.model

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position

/** Synthesizes villain ranges at every preflop node from a [VillainProfile]. Memoized. */
class VillainModel(val profile: VillainProfile, private val ranking: HandRanking) {
    private val memo = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> m(k: String, f: () -> T): T = synchronized(memo) { memo[k] as T? } ?: f().also { synchronized(memo) { memo[k] = it } }

    private val uniform = FloatArray(HandClass.COUNT) { 1f }

    fun open(o: Position): FloatArray = m("open$o") {
        Partition.split(uniform, profile.rfi(o), 0.0, 1.0, ranking).raise
    }

    fun defenseVsOpen(p: Position, o: Position): Partition = m("def$p$o") {
        val (t, c) = profile.defense(p, o)
        Partition.split(uniform, t, c, profile.threeBetValueShare, ranking)
    }

    fun openerVs3Bet(o: Position, p: Position): Partition = m("o3b$o") {
        Partition.split(open(o), profile.fourBet, 1 - profile.foldTo3Bet - profile.fourBet, profile.fourBetValueShare, ranking)
    }

    fun openerVsSqueeze(o: Position): Partition = m("osq$o") {
        val fold = (profile.foldTo3Bet + 0.05).coerceAtMost(0.95)
        Partition.split(open(o), profile.fourBet, 1 - fold - profile.fourBet, profile.fourBetValueShare, ranking)
    }

    fun threeBettorVs4Bet(p: Position, o: Position): Partition = m("t4b$p$o") {
        Partition.split(defenseVsOpen(p, o).raise, profile.fiveBet, 1 - profile.foldTo4Bet - profile.fiveBet, 1.0, ranking)
    }

    fun fourBettorVsJam(o: Position, p: Position): Partition = m("f5b$o$p") {
        Partition.split(openerVs3Bet(o, p).raise, 0.0, 1 - profile.foldTo5Bet, 1.0, ranking)
    }

    fun callerVsSqueeze(c: Position, o: Position): Partition = m("csq$c$o") {
        val cold4 = 0.04
        Partition.split(defenseVsOpen(c, o).call, cold4, 1 - profile.callerFoldToSqueeze - cold4, 1.0, ranking)
    }
}
