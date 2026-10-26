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
internal class SeasonCache(
    private val maxEntries: Int = MaxEntries,
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Map<Int, Double>>()
    private var loaded = false

    suspend fun get(key: String): Map<Int, Double>? = mutex.withLock { entries[key] }

    suspend fun size(): Int = mutex.withLock { entries.size }

    suspend fun snapshot(): Map<String, Map<Int, Double>> = mutex.withLock { copyOfEntries() }

    /**
     * Inserts [ratings] and evicts the oldest insertion-order keys until [maxEntries].
     * Returns a detached copy of the cache for the caller to persist outside the lock.
     */
    suspend fun put(key: String, ratings: Map<Int, Double>): Map<String, Map<Int, Double>> =
        mutex.withLock {
            entries[key] = ratings
            while (entries.size > maxEntries) {
                val oldestKey = entries.keys.firstOrNull() ?: break
                entries.remove(oldestKey)
            }
            copyOfEntries()
        }

    /**
     * Decodes the disk payload at most once. [load] runs outside the lock.
     * A cancellation clears the loaded flag so a later call can try again.
     */
    suspend fun loadOnce(load: suspend () -> Map<String, Map<Int, Double>>?) {
        val shouldLoad = mutex.withLock {
            if (loaded) {
                false
            } else {
                loaded = true
                true
            }
        }
        if (!shouldLoad) return
        try {
            val decoded = load() ?: return
            mutex.withLock { entries.putAll(decoded) }
        } catch (error: CancellationException) {
            mutex.withLock { loaded = false }
            throw error
        }
    }

    private fun copyOfEntries(): Map<String, Map<Int, Double>> = LinkedHashMap(entries)

    companion object {
        const val MaxEntries = 400
    }
}
