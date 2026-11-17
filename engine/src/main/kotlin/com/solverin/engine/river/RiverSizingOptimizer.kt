package com.solverin.engine.river

/**
 * Terminal sizes: block / thin (25%, 33%), standard polarized (70%), overbets (125%, 150%, 200%) and the all-in jam
 * when ΔNuts_river > overbetNutEdge or villain's range is capped. Every size is capped at the stack (SPR).
 */
object RiverSizingOptimizer {
    fun overbetEnabled(nutAdvantage: Double, villainCapped: Boolean, p: RiverPolicy): Boolean =
        nutAdvantage > p.overbetNutEdge || villainCapped

    fun candidates(spr: Double, nutAdvantage: Double, villainCapped: Boolean, p: RiverPolicy): List<Double> {
        if (spr <= 0.0) return emptyList()
        val raw = mutableListOf(p.blockBet, p.thinBet, p.standardBet)
        if (overbetEnabled(nutAdvantage, villainCapped, p)) { raw += p.overbetLow; raw += p.overbetHigh; raw += p.overbetMax; raw += spr }
        return raw.map { minOf(it, spr) }.map { Math.floor(it * 1000) / 1000.0 }.filter { it > 0.0 }.distinct().sorted()
    }

    fun isBlock(size: Double, p: RiverPolicy): Boolean = size <= p.thinBet + 1e-9
    fun isOverbet(size: Double, p: RiverPolicy): Boolean = size >= p.overbetLow - 1e-9

    /** Nash bluff : value ratio of a polarized bet of [size] pot fractions: s / (1 + s). */
    fun nashBluffRatio(size: Double): Double = size / (1 + size)
}
