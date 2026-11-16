package com.solverin.engine.math

import com.solverin.engine.core.Position
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey

/** Preflop sizing config (bb / multipliers). */
data class Sizing(
    val openBb: Double = 2.5,
    val sbOpenBb: Double = 3.0,
    val threeBetIpMult: Double = 3.0,
    val threeBetOopMult: Double = 4.0,
    val fourBetMult: Double = 2.25,
    val squeezeBaseMult: Double = 4.0,
    val squeezePerCallerMult: Double = 1.0,
)

/**
 * Projects pot and SPR for each option at a node, assuming heads-up continuation
 * (one caller of hero's raise, or hero calling the current bet).
 */
class SprProjector(private val sizing: Sizing, private val effectiveStackBb: Double) {

    data class Projection(val potBb: Double, val investedBb: Double, val spr: Double)

    fun open(p: Position) = if (p == Position.SB) sizing.sbOpenBb else sizing.openBb

    fun threeBet(opener: Position, threeBettor: Position): Double {
        val mult = if (threeBettor.isInPositionVs(opener)) sizing.threeBetIpMult else sizing.threeBetOopMult
        return open(opener) * mult
    }

    private fun proj(level: Double, players: List<Position>, extraDead: Double = 0.0): Projection {
        val lvl = minOf(level, effectiveStackBb)
        var dead = extraDead
        for (blind in listOf(Position.SB, Position.BB)) if (blind !in players) dead += blind.blindBb
        val pot = lvl * players.size + dead
        val behind = (effectiveStackBb - lvl).coerceAtLeast(0.0)
        return Projection(pot, lvl, if (pot > 0) behind / pot else 0.0)
    }

    /** Projection if hero takes the passive option (call). Null for RFI. */
    fun afterCall(key: SpotKey): Projection? {
        val h = key.hero; val v = key.villain
        return when (key.scenario) {
            Scenario.RFI -> null
            Scenario.VS_RFI -> proj(open(v), listOf(h, v))
            Scenario.VS_3BET -> proj(threeBet(h, v), listOf(h, v))
            Scenario.VS_4BET -> proj(threeBet(v, h) * sizing.fourBetMult, listOf(h, v))
            Scenario.SQUEEZE -> {
                val c = key.caller ?: return proj(open(v), listOf(h, v))
                proj(open(v), listOf(h, v, c))
            }
        }
    }

    /** Projection if hero takes the aggressive option and gets called. */
    fun afterRaise(key: SpotKey): Projection {
        val h = key.hero; val v = key.villain
        return when (key.scenario) {
            Scenario.RFI -> proj(open(h), listOf(h, v))
            Scenario.VS_RFI -> proj(threeBet(v, h), listOf(h, v))
            Scenario.VS_3BET -> proj(threeBet(h, v) * sizing.fourBetMult, listOf(h, v))
            Scenario.VS_4BET -> proj(effectiveStackBb, listOf(h, v))
            Scenario.SQUEEZE -> {
                val callers = if (key.caller != null) 1 else 0
                val size = open(v) * (sizing.squeezeBaseMult + sizing.squeezePerCallerMult * callers)
                val deadCaller = if (key.caller != null) open(v) else 0.0
                proj(size, listOf(h, v), if (key.caller?.isBlind == true) deadCaller - key.caller!!.blindBb else deadCaller)
            }
        }
    }
}
