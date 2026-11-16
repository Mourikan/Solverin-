package com.solverin.engine.eval

import com.solverin.engine.core.HandClass
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.util.SplittableRandom
import java.util.stream.IntStream

/**
 * 169x169 all-in preflop equity (row vs col, ties = 1/2), quantized to 16 bits: 57 KB.
 * Loaded from classpath resource [RESOURCE] (bundled into the APK as a Java resource).
 */
class EquityTable private constructor(private val q: ShortArray) {

    fun equity(hero: Int, villain: Int): Float = (q[hero * HandClass.COUNT + villain].toInt() and 0xFFFF) * INV

    /** Equity vs a uniformly random hand (combo weighted). */
    val vsRandom: FloatArray = FloatArray(HandClass.COUNT) { h ->
        var s = 0.0; var w = 0.0
        for (v in 0 until HandClass.COUNT) {
            val c = CardRemoval.freeCombos(h, v).toDouble()
            s += c * equity(h, v); w += c
        }
        (s / w).toFloat()
    }

    fun write(file: File) = DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeInt(MAGIC); out.writeShort(HandClass.COUNT)
        for (v in q) out.writeShort(v.toInt())
    }

    companion object {
        const val RESOURCE = "/solverin/equity169.bin"
        private const val MAGIC = 0x45513639 // "EQ69"
        private const val INV = 1f / 65535f
        private const val SIZE = HandClass.COUNT * HandClass.COUNT

        @Volatile private var instance: EquityTable? = null

        fun get(): EquityTable = instance ?: synchronized(this) {
            instance ?: (EquityTable::class.java.getResourceAsStream(RESOURCE)
                ?: error("Missing $RESOURCE: run ./gradlew :engine:generateEquityTable")).use { read(it) }.also { instance = it }
        }

        fun read(input: InputStream): EquityTable = DataInputStream(input.buffered()).run {
            require(readInt() == MAGIC) { "Bad equity table" }
            require(readUnsignedShort() == HandClass.COUNT)
            EquityTable(ShortArray(SIZE) { readShort() })
        }

        /** Monte Carlo generation (deterministic seed). ~[samples] boards per matchup. */
        fun generate(samples: Int = 20_000, seed: Long = 0x5017E1L): EquityTable {
            val q = ShortArray(SIZE)
            val masks = HandClass.COMBO_MASKS
            val off = HandClass.OFFSETS
            IntStream.range(0, HandClass.COUNT).parallel().forEach { i ->
                val ev = HandEvaluator()
                val rnd = SplittableRandom(seed + i * 7919L)
                val a = IntArray(7); val b = IntArray(7)
                for (j in i until HandClass.COUNT) {
                    var win = 0L; var tie = 0L; var n = 0L
                    while (n < samples) {
                        val ma = masks[off[i] + rnd.nextInt(off[i + 1] - off[i])]
                        val mb = masks[off[j] + rnd.nextInt(off[j + 1] - off[j])]
                        if (ma and mb != 0L) continue
                        var used = ma or mb
                        a[0] = java.lang.Long.numberOfTrailingZeros(ma); a[1] = 63 - java.lang.Long.numberOfLeadingZeros(ma)
                        b[0] = java.lang.Long.numberOfTrailingZeros(mb); b[1] = 63 - java.lang.Long.numberOfLeadingZeros(mb)
                        var k = 2
                        while (k < 7) {
                            val c = rnd.nextInt(52)
                            if (used and (1L shl c) != 0L) continue
                            used = used or (1L shl c)
                            a[k] = c; b[k] = c; k++
                        }
                        val ea = ev.evaluate(a, 7); val eb = ev.evaluate(b, 7)
                        if (ea > eb) win++ else if (ea == eb) tie++
                        n++
                    }
                    val eq = (win + tie * 0.5) / n
                    val e = if (i == j) 0.5 else eq
                    q[i * HandClass.COUNT + j] = Math.round(e * 65535).toInt().toShort()
                    q[j * HandClass.COUNT + i] = Math.round((1 - e) * 65535).toInt().toShort()
                }
            }
            return EquityTable(q)
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val out = File(args.getOrElse(0) { "src/main/resources$RESOURCE" })
            val samples = args.getOrNull(1)?.toInt() ?: 20_000
            out.parentFile.mkdirs()
            val t = System.nanoTime()
            generate(samples).write(out)
            println("Equity table -> $out (${out.length()} B) in ${(System.nanoTime() - t) / 1_000_000} ms")
        }
    }
}
