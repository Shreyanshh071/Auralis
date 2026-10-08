package com.auralis.music.util

/**
 * A thread-safe, size-bounded cache for pure functions: the least recently used entry is dropped
 * once [maxEntries] is reached. Only for functions whose result depends on the key alone.
 */
class BoundedMemo<K : Any, V : Any>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    fun getOrPut(key: K, compute: () -> V): V {
        synchronized(map) { map[key]?.let { return it } }
        // Computed outside the lock: two threads may both compute a miss, which is harmless for a
        // pure function and keeps one slow call from stalling every other caller.
        val value = compute()
        synchronized(map) { map[key] = value }
        return value
    }
}
