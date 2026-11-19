package com.solverin.engine.model

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import kotlin.math.sqrt

/**
 * Synthesizes villain ranges at every preflop node from a [VillainProfile], with [NodeLocks] overriding
 * any node's frequencies (Max Exploit). Memoized; one instance per (profile, locks).
 */
class VillainModel(
    val profile: VillainProfile,
    private val ranking: HandRanking,
    val locks: NodeLocks = NodeLocks.NONE,
) {
    private val memo = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> m(k: String, f: () -> T): T =
        synchronized(memo) { memo[k] as T? } ?: f().also { synchronized(memo) { memo[k] = it } }

    private val uniform = FloatArray(HandClass.COUNT) { 1f }

    private fun lock(node: VillainNode, villain: Position, versus: Position?) = locks.get(node, villain, versus)

    fun openFrequency(o: Position): Double = lock(VillainNode.OPEN, o, null)?.raise ?: profile.rfi(o)

    fun open(o: Position): FloatArray = m("open$o") {
        Partition.split(uniform, openFrequency(o), 0.0, 1.0, ranking).raise
    }

    /** (3bet, flat) of [p] facing an open from [o], after locks. */
    fun defenseFrequencies(p: Position, o: Position): Pair<Double, Double> {
        val (t, c) = profile.defense(p, o)
        val l = lock(VillainNode.DEFEND_VS_OPEN, p, o)
        return Pair(l?.raise ?: t, l?.call ?: c)
    }

    fun defenseVsOpen(p: Position, o: Position): Partition = m("def$p$o") {
        val (t, c) = defenseFrequencies(p, o)
        val vs = lock(VillainNode.DEFEND_VS_OPEN, p, o)?.valueShare ?: profile.threeBetValueShare
        Partition.split(uniform, t, c, vs, ranking)
    }

    fun openerVs3Bet(o: Position, p: Position): Partition = m("o3b$o$p") {
        val l = lock(VillainNode.VS_3BET, o, p)
        val r = l?.raise ?: profile.fourBet
        val c = l?.call ?: (1 - profile.foldTo3Bet - profile.fourBet)
        Partition.split(open(o), r, c, l?.valueShare ?: profile.fourBetValueShare, ranking)
    }

    fun openerVsSqueeze(o: Position): Partition = m("osq$o") {
        val l = lock(VillainNode.VS_SQUEEZE_OPENER, o, null)
        val fold = (profile.foldTo3Bet + 0.05).coerceAtMost(0.95)
        val r = l?.raise ?: profile.fourBet
        val c = l?.call ?: (1 - fold - profile.fourBet)
        Partition.split(open(o), r, c, l?.valueShare ?: profile.fourBetValueShare, ranking)
    }

    fun threeBettorVs4Bet(p: Position, o: Position): Partition = m("t4b$p$o") {
        val l = lock(VillainNode.VS_4BET, p, o)
        val r = l?.raise ?: profile.fiveBet
        val c = l?.call ?: (1 - profile.foldTo4Bet - profile.fiveBet)
        Partition.split(defenseVsOpen(p, o).raise, r, c, l?.valueShare ?: 1.0, ranking)
    }

    fun fourBettorVsJam(o: Position, p: Position): Partition = m("f5b$o$p") {
        val c = lock(VillainNode.VS_JAM, o, p)?.call ?: (1 - profile.foldTo5Bet)
        Partition.split(openerVs3Bet(o, p).raise, 0.0, c, 1.0, ranking)
    }

    /** The 4-bettor's answer to a jam when hero models his 4-bet range as [fourBetRange] (same calling share). */
    fun fourBettorVsJam(o: Position, p: Position, fourBetRange: FloatArray): Partition {
        val c = lock(VillainNode.VS_JAM, o, p)?.call ?: (1 - profile.foldTo5Bet)
        return Partition.split(fourBetRange, 0.0, c, 1.0, ranking)
    }

    fun callerVsSqueeze(c: Position, o: Position): Partition = m("csq$c$o") {
        val l = lock(VillainNode.VS_SQUEEZE_CALLER, c, o)
        val cold4 = l?.raise ?: 0.04
        val call = l?.call ?: (1 - profile.callerFoldToSqueeze - cold4)
        Partition.split(defenseVsOpen(c, o).call, cold4, call, 1.0, ranking)
    }

    /** Squeeze frequency of [s] facing an open from [o] plus a caller, after locks. */
    fun squeezeFrequency(s: Position, o: Position): Double =
        lock(VillainNode.SQUEEZE, s, o)?.raise
            ?: (profile.squeeze * sqrt(profile.rfi(o) / profile.rfiCo)).coerceIn(0.0, 0.3)

    fun squeezeRange(s: Position, o: Position): FloatArray = m("sq$s$o") {
        val vs = lock(VillainNode.SQUEEZE, s, o)?.valueShare ?: profile.threeBetValueShare
        Partition.split(uniform, squeezeFrequency(s, o), 0.0, vs, ranking).raise
    }

    fun squeezerVs4Bet(s: Position, o: Position): Partition = m("s4b$s$o") {
        val l = lock(VillainNode.VS_4BET, s, o)
        val r = l?.raise ?: profile.fiveBet
        val c = l?.call ?: (1 - profile.foldTo4Bet - profile.fiveBet)
        Partition.split(squeezeRange(s, o), r, c, l?.valueShare ?: 1.0, ranking)
    }

    fun fourBettorVsJamAfterSqueeze(o: Position): Partition = m("f5s$o") {
        val c = lock(VillainNode.VS_JAM, o, null)?.call ?: (1 - profile.foldTo5Bet)
        Partition.split(openerVsSqueeze(o).raise, 0.0, c, 1.0, ranking)
    }

    /** The squeezed opener's answer to a jam when hero models his 4-bet range as [fourBetRange]. */
    fun fourBettorVsJamAfterSqueeze(o: Position, fourBetRange: FloatArray): Partition {
        val c = lock(VillainNode.VS_JAM, o, null)?.call ?: (1 - profile.foldTo5Bet)
        return Partition.split(fourBetRange, 0.0, c, 1.0, ranking)
    }

    /** Player [p] not yet in, facing an open from [o] and a 3bet: cold 4bet / cold call / fold. */
    fun coldVs3Bet(p: Position, o: Position): Partition = m("c3b$p$o") {
        val l = lock(VillainNode.COLD_VS_3BET, p, o)
        val r = l?.raise ?: (profile.cold3Bet * COLD_4BET_FACTOR)
        val c = l?.call ?: (profile.coldCall * COLD_CALL_3BET_FACTOR)
        Partition.split(uniform, r, c, l?.valueShare ?: 1.0, ranking)
    }

    /** Opener [o] whose open was 3bet by [tb] and then cold 4bet: mostly folds. */
    fun openerVsCold4Bet(o: Position, tb: Position): Partition = m("oc4$o$tb") {
        val l = lock(VillainNode.VS_COLD_4BET, o, tb)
        val r = l?.raise ?: (profile.fourBet * COLD_4BET_FACTOR)
        val c = l?.call ?: (profile.fourBet * COLD_4BET_FACTOR)
        Partition.split(open(o), r, c, l?.valueShare ?: 1.0, ranking)
    }

    companion object {
        /** Cold 4bets / cold calls of a 3bet are far rarer than 3bets / flats vs an open. */
        const val COLD_4BET_FACTOR = 0.35
        const val COLD_CALL_3BET_FACTOR = 0.25
    }
}
