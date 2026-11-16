package com.solverin.engine.range

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Immutable spot store. Sorted IntArray keys + binary search: no boxing, no HashMap overhead.
 * Template spots share one SpotStrategy instance (~362 B each).
 */
class RangeRepository private constructor(
    private val keys: IntArray,
    private val values: Array<SpotStrategy>,
) {
    val size: Int get() = keys.size

    operator fun get(key: SpotKey): SpotStrategy? {
        val i = keys.binarySearch(key.packed)
        return if (i >= 0) values[i] else null
    }

    fun require(key: SpotKey): SpotStrategy = get(key) ?: throw NoSuchElementException("No chart for $key")

    fun keys(): List<SpotKey> = keys.map { SpotKey(it) }

    /** Distinct strategy objects (shared templates counted once). */
    fun distinctStrategies(): Int = values.toSet().size

    fun write(out: OutputStream) {
        val unique = LinkedHashMap<SpotStrategy, Int>()
        values.forEach { unique.getOrPut(it) { unique.size } }
        DataOutputStream(out).run {
            writeInt(MAGIC); writeShort(VERSION)
            writeShort(unique.size)
            for (s in unique.keys) {
                write(s.rawRaise()); write(s.rawCall())
                s.rawMask().forEach { writeLong(it) }
            }
            writeShort(keys.size)
            for (i in keys.indices) { writeInt(keys[i]); writeShort(unique.getValue(values[i])) }
            flush()
        }
    }

    class Builder {
        private val map = HashMap<Int, SpotStrategy>()
        fun put(key: SpotKey, s: SpotStrategy) = apply { map[key.packed] = s }
        fun build(): RangeRepository {
            val k = map.keys.toIntArray().also { it.sort() }
            return RangeRepository(k, Array(k.size) { map.getValue(k[it]) })
        }
    }

    companion object {
        private const val MAGIC = 0x534F4C56 // "SOLV"
        private const val VERSION = 1

        fun read(input: InputStream): RangeRepository = DataInputStream(input).run {
            require(readInt() == MAGIC) { "Bad magic" }
            require(readUnsignedShort() == VERSION) { "Unsupported version" }
            val n = readUnsignedShort()
            val pool = Array(n) {
                val r = ByteArray(HandClass.COUNT).also { readFully(it) }
                val c = ByteArray(HandClass.COUNT).also { readFully(it) }
                val m = LongArray(3) { readLong() }
                SpotStrategy(r, c, m)
            }
            val k = readUnsignedShort()
            val b = Builder()
            repeat(k) { b.put(SpotKey(readInt()), pool[readUnsignedShort()]) }
            b.build()
        }

        /** Compiles every 6-max preflop node from [BaselineCharts]. */
        fun baseline(): RangeRepository {
            val b = Builder()
            val open = Position.entries.filter { it != Position.BB }

            BaselineCharts.RFI.forEach { (hero, spec) ->
                b.put(SpotKey.of(Scenario.RFI, hero, Position.BB), spec.compile())
            }
            BaselineCharts.VS_RFI.forEach { (pair, spec) ->
                b.put(SpotKey.of(Scenario.VS_RFI, pair.first, pair.second), spec.compile())
            }

            val t3 = BaselineCharts.VS_3BET.mapValues { it.value.compile() }
            val t4 = BaselineCharts.VS_4BET.mapValues { it.value.compile() }
            val sq = BaselineCharts.SQUEEZE.mapValues { it.value.compile() }

            for (opener in open) for (other in Position.entries) {
                if (other.ordinal <= opener.ordinal) continue
                b.put(SpotKey.of(Scenario.VS_3BET, opener, other), t3.getValue(BaselineCharts.threeBetTemplate(opener, other)))
                b.put(SpotKey.of(Scenario.VS_4BET, other, opener), t4.getValue(BaselineCharts.fourBetTemplate(other, opener)))
            }
            for (opener in open) for (caller in Position.entries) {
                if (caller.ordinal <= opener.ordinal) continue
                for (hero in Position.entries) {
                    if (hero.ordinal <= caller.ordinal) continue
                    b.put(SpotKey.of(Scenario.SQUEEZE, hero, opener, caller), sq.getValue(BaselineCharts.squeezeTemplate(hero)))
                }
            }
            return b.build()
        }
    }
}
