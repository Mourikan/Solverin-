package com.solverin.engine.flop

/** Fixed-width unsigned integers packed into a LongArray (bits per entry 1..32). */
class BitPackedArray(val size: Int, val bits: Int) {
    init { require(bits in 1..32 && size >= 0) }

    private val data = LongArray(((size.toLong() * bits + 63) / 64).toInt())
    private val mask = (1L shl bits) - 1

    operator fun get(i: Int): Int {
        val bit = i.toLong() * bits
        val w = (bit ushr 6).toInt()
        val o = (bit and 63).toInt()
        var v = data[w] ushr o
        if (o + bits > 64) v = v or (data[w + 1] shl (64 - o))
        return (v and mask).toInt()
    }

    operator fun set(i: Int, value: Int) {
        require(value.toLong() and mask.inv() == 0L) { "Value $value exceeds $bits bits" }
        val bit = i.toLong() * bits
        val w = (bit ushr 6).toInt()
        val o = (bit and 63).toInt()
        data[w] = (data[w] and (mask shl o).inv()) or (value.toLong() shl o)
        if (o + bits > 64) {
            val hi = o + bits - 64
            val hiMask = (1L shl hi) - 1
            data[w + 1] = (data[w + 1] and hiMask.inv()) or (value.toLong() ushr (64 - o))
        }
    }

    /** Bytes held by the packed payload. */
    val bytes: Int get() = data.size * 8
}
