package com.nuvio.app.core.perf

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Session cache that keeps the [maxEntries] most recently used entries while capped caches are on,
 * and grows without limit (the old behaviour) while they are off. Null values are stored, so a
 * cached miss stays distinguishable through [containsKey].
 */
internal class BoundedCache<K, V>(
    private val maxEntries: Int,
    private val capEnabled: () -> Boolean = { PerformanceSettings.isEnabled(PerformanceOptimization.CappedCaches) },
    private val onEvicted: (Int) -> Unit = { PerformanceMetrics.count(PerformanceCounter.CacheEvictions, it) },
) {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<K, V>()

    val size: Int get() = synchronized(lock) { entries.size }

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: K): V? = synchronized(lock) {
        if (!entries.containsKey(key)) return null
        val value = entries.remove(key) as V
        entries[key] = value
        value
    }

    operator fun set(key: K, value: V) {
        val evicted = synchronized(lock) {
            entries.remove(key)
            entries[key] = value
            if (capEnabled()) trimLocked() else 0
        }
        if (evicted > 0) onEvicted(evicted)
    }

    fun containsKey(key: K): Boolean = synchronized(lock) { entries.containsKey(key) }

    operator fun contains(key: K): Boolean = containsKey(key)

    fun remove(key: K): V? = synchronized(lock) { entries.remove(key) }

    fun clear() = synchronized(lock) { entries.clear() }

    fun firstValue(matches: (K) -> Boolean): V? = synchronized(lock) {
        entries.entries.firstOrNull { matches(it.key) }?.value
    }

    private fun trimLocked(): Int {
        var evicted = 0
        while (entries.size > maxEntries) {
            val eldest = entries.keys.iterator().next()
            entries.remove(eldest)
            evicted++
        }
        return evicted
    }
}
