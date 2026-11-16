package com.solverin.engine.flop

import com.solverin.engine.eval.HandEvaluator

enum class SuitTexture { RAINBOW, TWO_TONE, MONOTONE }
enum class Pairing { UNPAIRED, PAIRED, TRIPS }
enum class Connectivity { LOW, MEDIUM, HIGH }
enum class Height { LOW, MIDDLE, HIGH }

/**
 * Strategic texture of a canonical flop.
 * straightCombos: distinct hole-rank pairs that make a straight on this flop (0 .. ~4).
 * volatility in [0, 1]: how much equities shift on later streets (suits + connectivity, damped by pairing).
 */
data class Texture(
    val flopId: Int,
    val suits: SuitTexture,
    val pairing: Pairing,
    val connectivity: Connectivity,
    val height: Height,
    val straightCombos: Int,
    val volatility: Double,
)

object Textures {
    private val CACHE: Array<Texture> = Array(FlopIso.COUNT) { compute(it) }

    fun of(flopId: Int): Texture = CACHE[flopId]
    fun of(board: IntArray): Texture = CACHE[FlopIso.id(board)]

    private fun compute(id: Int): Texture {
        val r = FlopIso.ranks(id)
        val p = FlopIso.pattern(id)
        val suits = when (p) {
            FlopIso.Pattern.MONOTONE -> SuitTexture.MONOTONE
            FlopIso.Pattern.RAINBOW, FlopIso.Pattern.PAIRED_RAINBOW, FlopIso.Pattern.TRIPS -> SuitTexture.RAINBOW
            else -> SuitTexture.TWO_TONE
        }
        val pairing = when {
            r[0] == r[1] && r[1] == r[2] -> Pairing.TRIPS
            r[0] == r[1] || r[1] == r[2] -> Pairing.PAIRED
            else -> Pairing.UNPAIRED
        }
        val board = (1 shl r[0]) or (1 shl r[1]) or (1 shl r[2])
        var straights = 0
        for (x in 0 until 13) for (y in x + 1 until 13) {
            if (HandEvaluator.straightHigh(board or (1 shl x) or (1 shl y)) >= 0) straights++
        }
        val connectivity = when {
            straights >= 3 -> Connectivity.HIGH
            straights >= 1 -> Connectivity.MEDIUM
            else -> Connectivity.LOW
        }
        val height = when {
            r[0] >= 10 -> Height.HIGH // Q+
            r[0] >= 6 -> Height.MIDDLE // 8..J
            else -> Height.LOW
        }
        val suitScore = when (suits) { SuitTexture.MONOTONE -> 0.9; SuitTexture.TWO_TONE -> 0.55; SuitTexture.RAINBOW -> 0.15 }
        val connScore = when (connectivity) { Connectivity.HIGH -> 1.0; Connectivity.MEDIUM -> 0.5; Connectivity.LOW -> 0.1 }
        var v = 0.5 * suitScore + 0.5 * connScore
        v *= when (pairing) { Pairing.UNPAIRED -> 1.0; Pairing.PAIRED -> 0.6; Pairing.TRIPS -> 0.2 }
        return Texture(id, suits, pairing, connectivity, height, straights, v)
    }
}
