package com.solverin.engine.flop

/**
 * Villain flop responses synthesized from his range ordering (equity vs hero's range), draw outs,
 * [FlopProfile] and [FlopNodeLocks].
 * Facing a bet: continue = MDF(size) × TendencyMultiplier × textureFactor,
 * TM = (1 − foldToBet) / MDF(½ pot); textureFactor > 1 when villain's range connects with the board
 * (hero range / nut disadvantage) or the board is volatile (draws continue), < 1 on dry boards that miss him.
 * A node-locked fold frequency replaces the whole product.
 */
class FlopVillainModel(
    private val byStrength: IntArray,
    private val byBluff: IntArray,
    private val profile: FlopProfile,
    private val locks: FlopNodeLocks,
    private val textureFactor: Double = 1.0,
    /** Weak made hands first (ascending equity), used as the non-value part of a weakness / block-bet donk. */
    private val byWeakMade: IntArray = byBluff,
    /** Bets below this pot fraction are min-bets (fold capped at profile.microBetMaxFold). */
    private val microBetRatio: Double = 0.2,
) {
    private val memo = java.util.IdentityHashMap<FloatArray, HashMap<Pair<Int, Double>, ComboPartition>>()

    private fun memo(tag: Int, range: FloatArray, a: Double, f: () -> ComboPartition): ComboPartition =
        memo.getOrPut(range) { HashMap() }.getOrPut(tag to a) { f() }

    fun mdf(potBefore: Double, bet: Double): Double = if (potBefore + bet <= 0) 0.0 else potBefore / (potBefore + bet)

    /** Villain's continue share facing a bet of [bet] into [potBefore] (unblocked, before range split). */
    fun continueShare(potBefore: Double, bet: Double, node: FlopVillainNode = FlopVillainNode.VS_BET): Double {
        val l = locks[node]
        val fold = if (node == FlopVillainNode.VS_DONK) profile.foldToDonk else profile.foldToBet
        val tm = (1 - fold) / mdf(1.0, 0.5)
        l?.fold?.let { return 1 - it }
        val cont = (mdf(potBefore, bet) * tm * textureFactor).coerceIn(0.02, 0.98)
        return if (bet < microBetRatio * potBefore) maxOf(cont, 1 - profile.microBetMaxFold) else cont
    }

    /** Villain facing a bet (or a donk lead when [node] = VS_DONK) of [bet] into [potBefore] with range [range]. */
    fun respondToBet(range: FloatArray, potBefore: Double, bet: Double, node: FlopVillainNode = FlopVillainNode.VS_BET): ComboPartition =
        memo(if (node == FlopVillainNode.VS_DONK) 10 else 0, range, bet / potBefore) {
            val l = locks[node]
            val cont = continueShare(potBefore, bet, node)
            val raise = minOf(l?.raise ?: profile.raiseVsBet, cont)
            ComboPartition.split(range, raise, cont - raise, l?.valueShare ?: profile.raiseValueShare, byStrength, byBluff)
        }

    /** Villain (who bet) facing hero's raise. */
    fun respondToRaise(betRange: FloatArray): ComboPartition = memo(1, betRange, 0.0) {
        val l = locks[FlopVillainNode.VS_RAISE]
        val fold = l?.fold ?: profile.foldToRaise
        val re = minOf(l?.raise ?: profile.reraiseVsRaise, 1 - fold)
        ComboPartition.split(betRange, re, 1 - fold - re, l?.valueShare ?: 1.0, byStrength, byBluff)
    }

    /** Villain facing hero's all-in with [range]. */
    fun respondToJam(range: FloatArray): ComboPartition = memo(2, range, 0.0) {
        val call = locks[FlopVillainNode.VS_JAM]?.fold?.let { 1 - it } ?: profile.callJam
        ComboPartition.split(range, 0.0, call, 1.0, byStrength, byBluff)
    }

    /** Villain's first action: raise = betting range, fold = checking range. */
    fun firstAction(range: FloatArray, node: FlopVillainNode, valueShareOverride: Double? = null): ComboPartition =
        memo(3 + node.ordinal, range, valueShareOverride ?: -1.0) {
            val l = locks[node]
            val freq = l?.frequency ?: when (node) {
                FlopVillainNode.CBET -> profile.cbet
                FlopVillainNode.STAB -> profile.stabVsCheck
                FlopVillainNode.DONK -> profile.donk
                else -> throw IllegalArgumentException("$node is not a first action")
            }
            val vs = l?.valueShare ?: valueShareOverride ?: profile.betValueShare
            // A weakness donk mixes block / protection bets (weak made hands) with semi-bluffs (draws).
            if (node == FlopVillainNode.DONK && vs < 0.5) {
                ComboPartition.splitFirstAction(range, freq, vs, byStrength, byBluff,
                    altBluff = byWeakMade, altBluffShare = profile.weakDonkMadeShare)
            } else ComboPartition.splitFirstAction(range, freq, vs, byStrength, byBluff)
        }

    companion object {
        const val RANGE_K = 2.0
        const val NUTS_K = 1.0
        const val VOLATILITY_K = 0.3
        const val VOLATILITY_REF = 0.4

        /** Villain continue multiplier from hero's ΔRange / ΔNuts and board volatility. */
        fun textureFactor(heroRangeAdvantage: Double, heroNutAdvantage: Double, volatility: Double): Double =
            ((1 - RANGE_K * heroRangeAdvantage - NUTS_K * heroNutAdvantage) *
                (1 + VOLATILITY_K * (volatility - VOLATILITY_REF))).coerceIn(0.5, 1.6)
    }
}
