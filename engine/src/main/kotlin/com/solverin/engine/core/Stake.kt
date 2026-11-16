package com.solverin.engine.core

/**
 * Rake model in big blinds. capBb = per-hand cap expressed in bb.
 * Defaults are priors; override per site/room via [RakeStructure].
 */
data class RakeStructure(val rate: Double, val capBb: Double, val noFlopNoDrop: Boolean = true) {
    /** Rake taken from a final pot of [potBb]. */
    fun rakeBb(potBb: Double): Double = minOf(potBb * rate, capBb)

    /** Effective rake rate on a pot of [potBb]. */
    fun effectiveRate(potBb: Double): Double = if (potBb <= 0.0) 0.0 else rakeBb(potBb) / potBb
}

enum class Stake(val bigBlindCents: Int, val defaultRake: RakeStructure) {
    NL2(2, RakeStructure(0.05, 4.0)),
    NL5(5, RakeStructure(0.05, 4.0)),
    NL10(10, RakeStructure(0.05, 3.0)),
    NL25(25, RakeStructure(0.05, 2.5)),
    NL50(50, RakeStructure(0.05, 2.0)),
    NL100(100, RakeStructure(0.05, 1.5)),
    NL200(200, RakeStructure(0.04, 1.0));

    companion object {
        /** Rake environment the baseline charts are assumed to be solved under. */
        val REFERENCE_RAKE = RakeStructure(0.05, 2.0)
    }
}
