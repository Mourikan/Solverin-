package com.solverin.engine.math

/**
 * Per-thread pool of primitive scratch buffers (DoubleArray / IntArray) keyed by length, so the hot loops of a
 * hand (1326-combo walks, 46-river projections, river response partitions) reuse arrays instead of allocating
 * short-lived garbage on ART. Buffers are zero-filled on acquire; [stats] counts fresh allocations vs reuses.
 */
object PipelineBufferPool {
    class Stats { var allocated = 0L; var reused = 0L }

    private class Pool {
        val doubles = HashMap<Int, ArrayDeque<DoubleArray>>()
        val ints = HashMap<Int, ArrayDeque<IntArray>>()
        val stats = Stats()
    }

    private val local = ThreadLocal.withInitial { Pool() }

    /** Max buffers retained per length (bounded footprint). */
    const val MAX_PER_SIZE = 8

    fun acquireDoubles(size: Int): DoubleArray {
        val p = local.get()
        val q = p.doubles[size]
        val b = q?.removeLastOrNull()
        return if (b != null) { p.stats.reused++; b.fill(0.0); b } else { p.stats.allocated++; DoubleArray(size) }
    }

    fun release(b: DoubleArray) {
        val q = local.get().doubles.getOrPut(b.size) { ArrayDeque() }
        if (q.size < MAX_PER_SIZE) q.addLast(b)
    }

    fun acquireInts(size: Int): IntArray {
        val p = local.get()
        val b = p.ints[size]?.removeLastOrNull()
        return if (b != null) { p.stats.reused++; b.fill(0); b } else { p.stats.allocated++; IntArray(size) }
    }

    fun release(b: IntArray) {
        val q = local.get().ints.getOrPut(b.size) { ArrayDeque() }
        if (q.size < MAX_PER_SIZE) q.addLast(b)
    }

    inline fun <T> withDoubles(size: Int, block: (DoubleArray) -> T): T {
        val b = acquireDoubles(size)
        try { return block(b) } finally { release(b) }
    }

    inline fun <T> withInts(size: Int, block: (IntArray) -> T): T {
        val b = acquireInts(size)
        try { return block(b) } finally { release(b) }
    }

    /** This thread's counters (allocated, reused). */
    fun stats(): Stats = local.get().stats

    fun resetStats() { local.get().stats.let { it.allocated = 0; it.reused = 0 } }

    /** Buffers currently retained by this thread's pool. */
    fun retained(): Int = local.get().let { p -> p.doubles.values.sumOf { it.size } + p.ints.values.sumOf { it.size } }
}
