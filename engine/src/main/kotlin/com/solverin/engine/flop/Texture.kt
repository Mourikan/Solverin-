package com.solverin.engine.flop

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
        val e = FlopIsomorphismEngine
        val suits = e.suits(id); val pairing = e.pairing(id); val connectivity = e.connectivity(id)
        val suitScore = when (suits) { SuitTexture.MONOTONE -> 0.9; SuitTexture.TWO_TONE -> 0.55; SuitTexture.RAINBOW -> 0.15 }
        val connScore = when (connectivity) { Connectivity.HIGH -> 1.0; Connectivity.MEDIUM -> 0.5; Connectivity.LOW -> 0.1 }
        var v = 0.5 * suitScore + 0.5 * connScore
        v *= when (pairing) { Pairing.UNPAIRED -> 1.0; Pairing.PAIRED -> 0.6; Pairing.TRIPS -> 0.2 }
        return Texture(id, suits, pairing, connectivity, e.height(id), e.straightCombos(id), v)
    }
}
