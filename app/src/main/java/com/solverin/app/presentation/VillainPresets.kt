package com.solverin.app.presentation

import com.solverin.engine.core.Position
import com.solverin.engine.flop.FlopNodeLock
import com.solverin.engine.flop.FlopNodeLocks
import com.solverin.engine.flop.FlopVillainNode
import com.solverin.engine.model.NodeLock
import com.solverin.engine.model.NodeLocks
import com.solverin.engine.model.VillainNode
import com.solverin.engine.pipeline.HandLocks
import com.solverin.engine.river.RiverNodeLock
import com.solverin.engine.river.RiverNodeLocks
import com.solverin.engine.river.RiverVillainNode
import com.solverin.engine.turn.TurnNodeLock
import com.solverin.engine.turn.TurnNodeLocks
import com.solverin.engine.turn.TurnVillainNode

/** Villain profile selector of the top bar → node locks on every street (POPULATION = no lock). */
enum class VillainProfilePreset(val label: String) {
    POPULATION("Population"), STATION("Station"), NIT("Nit"), MANIAC("Maniac"), OVERFOLDER("Overfolder");

    val locks: HandLocks by lazy {
        when (this) {
            POPULATION -> HandLocks()
            STATION -> build(defendCall = 0.70, defendRaise = 0.04, foldBet = 0.12, raiseBet = 0.04, foldRaise = 0.20, reraise = 0.04,
                foldJam = 0.25, betFreq = 0.30, valueShare = 0.85, elasticity = 0.95, riverBluff = 0.15)
            NIT -> build(defendCall = 0.15, defendRaise = 0.03, foldBet = 0.65, raiseBet = 0.05, foldRaise = 0.75, reraise = 0.05,
                foldJam = 0.80, betFreq = 0.30, valueShare = 0.95, elasticity = 0.30, riverBluff = 0.05)
            MANIAC -> build(defendCall = 0.45, defendRaise = 0.25, foldBet = 0.20, raiseBet = 0.35, foldRaise = 0.20, reraise = 0.35,
                foldJam = 0.10, betFreq = 0.90, valueShare = 0.40, elasticity = 0.80, riverBluff = 0.60)
            OVERFOLDER -> build(defendCall = 0.12, defendRaise = 0.02, foldBet = 0.75, raiseBet = 0.04, foldRaise = 0.80, reraise = 0.03,
                foldJam = 0.85, betFreq = 0.35, valueShare = 0.90, elasticity = 0.35, riverBluff = 0.20)
        }
    }

    private companion object {
        fun build(
            defendCall: Double, defendRaise: Double, foldBet: Double, raiseBet: Double, foldRaise: Double, reraise: Double,
            foldJam: Double, betFreq: Double, valueShare: Double, elasticity: Double, riverBluff: Double,
        ): HandLocks {
            var pre = NodeLocks.NONE
            for (p in Position.entries) pre = pre.with(VillainNode.DEFEND_VS_OPEN, p, NodeLock(raise = defendRaise, call = defendCall))
            val flop = FlopNodeLocks.NONE
                .with(FlopVillainNode.VS_BET, FlopNodeLock(fold = foldBet, raise = raiseBet))
                .with(FlopVillainNode.VS_DONK, FlopNodeLock(fold = foldBet, raise = raiseBet))
                .with(FlopVillainNode.VS_RAISE, FlopNodeLock(fold = foldRaise, raise = reraise))
                .with(FlopVillainNode.VS_JAM, FlopNodeLock(fold = foldJam))
                .with(FlopVillainNode.CBET, FlopNodeLock(frequency = betFreq, valueShare = valueShare))
                .with(FlopVillainNode.STAB, FlopNodeLock(frequency = betFreq, valueShare = valueShare))
                .with(FlopVillainNode.DONK, FlopNodeLock(frequency = betFreq / 2, valueShare = valueShare))
            var turn = TurnNodeLocks.NONE
            for (n in listOf(TurnVillainNode.VS_SECOND_BARREL, TurnVillainNode.VS_DELAYED_CBET, TurnVillainNode.VS_PROBE, TurnVillainNode.VS_DONK, TurnVillainNode.VS_BET))
                turn = turn.with(n, TurnNodeLock(fold = foldBet, raise = raiseBet))
            turn = turn.with(TurnVillainNode.VS_TURN_RAISE, TurnNodeLock(fold = foldRaise, raise = reraise))
                .with(TurnVillainNode.VS_JAM, TurnNodeLock(fold = foldJam))
            for (n in listOf(TurnVillainNode.BARREL, TurnVillainNode.DELAYED_CBET, TurnVillainNode.PROBE, TurnVillainNode.STAB, TurnVillainNode.DONK))
                turn = turn.with(n, TurnNodeLock(frequency = betFreq, valueShare = valueShare))
            turn = turn.with(TurnVillainNode.RIVER_ELASTICITY, TurnNodeLock(frequency = elasticity))
            val river = RiverNodeLocks.NONE
                .with(RiverVillainNode.VS_BET, RiverNodeLock(fold = foldBet, raise = raiseBet))
                .with(RiverVillainNode.VS_OVERBET, RiverNodeLock(fold = minOf(0.95, foldBet + 0.1)))
                .with(RiverVillainNode.VS_RAISE, RiverNodeLock(fold = foldRaise))
                .with(RiverVillainNode.VS_JAM, RiverNodeLock(fold = foldJam))
                .with(RiverVillainNode.BET, RiverNodeLock(frequency = betFreq, valueShare = 1 - riverBluff))
                .with(RiverVillainNode.DONK, RiverNodeLock(frequency = betFreq / 2, valueShare = 1 - riverBluff))
                .with(RiverVillainNode.RIVER_ELASTICITY, RiverNodeLock(frequency = elasticity))
            return HandLocks(pre, flop, turn, river)
        }
    }
}
