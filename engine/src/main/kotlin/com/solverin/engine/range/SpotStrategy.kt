package com.solverin.engine.range

import com.solverin.engine.core.HandClass

/**
 * Mixed strategy for one node over 169 hand classes.
 * Frequencies quantized to 1 byte (0..200 => 0.5% steps): 338 B + 24 B mask per spot.
 * fold = 1 - raise - call (implicit, not stored).
 * valueMask flags hands whose raise portion is for value (rest of raise = bluff).
 */
class SpotStrategy internal constructor(
    private val raiseQ: ByteArray,
    private val callQ: ByteArray,
    private val valueMask: LongArray,
) {
    init {
        require(raiseQ.size == HandClass.COUNT && callQ.size == HandClass.COUNT && valueMask.size == 3)
    }

    fun raise(h: Int): Float = (raiseQ[h].toInt() and 0xFF) * INV_SCALE
    fun call(h: Int): Float = (callQ[h].toInt() and 0xFF) * INV_SCALE
    fun fold(h: Int): Float = 1f - raise(h) - call(h)
    fun isValue(h: Int): Boolean = (valueMask[h ushr 6] ushr (h and 63)) and 1L == 1L

    fun mix(h: Int) = ActionMix(raise(h), call(h))

    /** Combo-weighted frequency of an action over all 1326 combos. */
    fun raiseFrequency(): Double = weighted { raise(it) }
    fun callFrequency(): Double = weighted { call(it) }
    fun vpip(): Double = weighted { raise(it) + call(it) }

    private inline fun weighted(f: (Int) -> Float): Double {
        var s = 0.0
        for (h in 0 until HandClass.COUNT) s += f(h) * HandClass.combos(h)
        return s / HandClass.TOTAL_COMBOS
    }

    /** Range weights (per class, 0..1) of hands continuing (raise + call). */
    fun continueWeights(): FloatArray = FloatArray(HandClass.COUNT) { raise(it) + call(it) }
    fun raiseWeights(): FloatArray = FloatArray(HandClass.COUNT) { raise(it) }
    fun valueRaiseWeights(): FloatArray = FloatArray(HandClass.COUNT) { if (isValue(it)) raise(it) else 0f }

    internal fun rawRaise() = raiseQ
    internal fun rawCall() = callQ
    internal fun rawMask() = valueMask

    override fun equals(other: Any?): Boolean = other is SpotStrategy &&
        raiseQ.contentEquals(other.raiseQ) && callQ.contentEquals(other.callQ) && valueMask.contentEquals(other.valueMask)

    override fun hashCode(): Int = 31 * (31 * raiseQ.contentHashCode() + callQ.contentHashCode()) + valueMask.contentHashCode()

    companion object {
        const val SCALE = 200
        const val INV_SCALE = 1f / SCALE
        const val BYTES = HandClass.COUNT * 2 + 3 * 8

        fun quantize(f: Float): Byte = (Math.round(f.coerceIn(0f, 1f) * SCALE)).toByte()
    }

    /** Mutable float workspace used while building/adjusting; frozen into a compact [SpotStrategy]. */
    class Builder {
        val raise = FloatArray(HandClass.COUNT)
        val call = FloatArray(HandClass.COUNT)
        val value = BooleanArray(HandClass.COUNT)

        fun from(s: SpotStrategy): Builder {
            for (h in 0 until HandClass.COUNT) {
                raise[h] = s.raise(h); call[h] = s.call(h); value[h] = s.isValue(h)
            }
            return this
        }

        fun build(): SpotStrategy {
            val rq = ByteArray(HandClass.COUNT)
            val cq = ByteArray(HandClass.COUNT)
            val mask = LongArray(3)
            for (h in 0 until HandClass.COUNT) {
                val r = raise[h].coerceIn(0f, 1f)
                val c = call[h].coerceIn(0f, 1f - r)
                val rQ = Math.round(r * SCALE)
                val cQ = minOf(Math.round(c * SCALE), SCALE - rQ)
                rq[h] = rQ.toByte(); cq[h] = cQ.toByte()
                if (value[h]) mask[h ushr 6] = mask[h ushr 6] or (1L shl (h and 63))
            }
            return SpotStrategy(rq, cq, mask)
        }
    }
}

/** Action frequencies for one hand. */
data class ActionMix(val raise: Float, val call: Float) {
    val fold: Float get() = (1f - raise - call).coerceAtLeast(0f)
}
