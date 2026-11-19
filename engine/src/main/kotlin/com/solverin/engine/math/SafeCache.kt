package com.solverin.engine.math

import java.util.IdentityHashMap

/**
 * Thread-safe memo keyed by array identity: lookups and inserts are synchronized, the value is computed outside the
 * lock (nested memo calls never deadlock; a concurrent duplicate computation keeps the first stored value).
 */
class IdentityMemo<V : Any> {
    private val map = IdentityHashMap<Any, V>()

    fun get(key: Any, compute: () -> V): V {
        synchronized(map) { map[key]?.let { return it } }
        val v = compute()
        synchronized(map) { return map.putIfAbsent(key, v) ?: v }
    }
}

/** Thread-safe memo keyed by value (same contract as [IdentityMemo]). */
class KeyMemo<K : Any, V : Any> {
    private val map = HashMap<K, V>()

    fun get(key: K, compute: () -> V): V {
        synchronized(map) { map[key]?.let { return it } }
        val v = compute()
        synchronized(map) { return map.putIfAbsent(key, v) ?: v }
    }
}
