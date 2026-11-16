package com.solverin.engine.flop

enum class DonkType { STRENGTH, WEAKNESS, BALANCED }

/** Read of a donk bet (OOP lead into the preflop aggressor). Values from the donker's perspective. */
data class DonkRead(
    val type: DonkType,
    val valueShare: Double,
    val villainRangeAdvantage: Double,
    val villainNutAdvantage: Double,
)

/**
 * Donk classification: a lead without initiative is value-heavy when the leader holds the nut advantage
 * (or the board is low and connected, where the caller's range hits hardest), weak/protection-heavy when
 * the leader's range is clearly behind, balanced otherwise. A node-locked value share overrides.
 */
object DonkAnalyzer {
    const val NUT_EDGE = 0.02
    const val RANGE_DEFICIT = -0.05

    fun read(texture: Texture, villainDeltaRange: Double, villainDeltaNuts: Double, lock: FlopNodeLock?): DonkRead {
        lock?.valueShare?.let { vs ->
            val t = when { vs >= 0.65 -> DonkType.STRENGTH; vs <= 0.45 -> DonkType.WEAKNESS; else -> DonkType.BALANCED }
            return DonkRead(t, vs, villainDeltaRange, villainDeltaNuts)
        }
        val lowConnected = texture.height == Height.LOW && texture.connectivity == Connectivity.HIGH
        return when {
            villainDeltaNuts >= NUT_EDGE || lowConnected -> DonkRead(DonkType.STRENGTH, 0.75, villainDeltaRange, villainDeltaNuts)
            villainDeltaRange <= RANGE_DEFICIT -> DonkRead(DonkType.WEAKNESS, 0.40, villainDeltaRange, villainDeltaNuts)
            else -> DonkRead(DonkType.BALANCED, 0.55, villainDeltaRange, villainDeltaNuts)
        }
    }
}
