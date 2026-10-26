package com.nuvio.app.features.details

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Insertion-ordered cache of OMDb season ratings.
 *
 * [put] and [get] take [mutex] only around the map itself. The snapshot returned by [put]
 * is a copy, so disk serialization never iterates the live map and the lock is not held
 * across network or disk I/O.
 */
internal class SeasonCache<V>(
    private val maxEntries: Int = MaxEntries,
) {
    private val mutex = Mutex()
    private val loadMutex = Mutex()
    private val entries = LinkedHashMap<String, V>()
    private var loaded = false

    suspend fun get(key: String): V? = mutex.withLock { entries[key] }

    suspend fun size(): Int = mutex.withLock { entries.size }

    suspend fun snapshot(): Map<String, V> = mutex.withLock { copyOfEntries() }

    /**
     * Inserts [value] and evicts the oldest insertion-order keys until [maxEntries].
     * An existing key is moved to the newest position. Returns a detached copy of
     * the cache for the caller to persist outside the lock.
     */
    suspend fun put(key: String, value: V): Map<String, V> = mutex.withLock {
        entries.remove(key)
        entries[key] = value
        while (entries.size > maxEntries) {
            val oldestKey = entries.keys.firstOrNull() ?: break
            entries.remove(oldestKey)
        }
        copyOfEntries()
    }

    /**
     * Decodes the disk payload at most once, and only after a successful merge.
     * [load] runs outside the map lock. A null result is a failed decode: memory
     * is left as-is and a later call tries again. Keys already in memory win over
     * the disk copy. Concurrent callers wait on [loadMutex] for the in-flight load.
     * Cancellation leaves the cache unloaded and is rethrown.
     */
    suspend fun loadOnce(load: suspend () -> Map<String, V>?) {
        loadMutex.withLock {
            if (mutex.withLock { loaded }) return
            try {
                val decoded = load()
                mutex.withLock {
                    if (decoded != null) {
                        for ((key, value) in decoded) {
                            if (key !in entries) entries[key] = value
                        }
                        loaded = true
                    }
                }
            } catch (error: CancellationException) {
                mutex.withLock { loaded = false }
                throw error
            }
        }
    }

    private fun copyOfEntries(): Map<String, V> = LinkedHashMap(entries)

    companion object {
        const val MaxEntries = 400
    }
}
