package com.nuvio.app.features.details

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class SeasonCacheTest {
    @Test
    fun `evicts the oldest season once the cache is over capacity`() = runTest {
        val cache = SeasonCache(maxEntries = 2)
        cache.put("a", mapOf(1 to 1.0))
        cache.put("b", mapOf(1 to 2.0))
        cache.put("c", mapOf(1 to 3.0))

        assertEquals(2, cache.size())
        assertEquals(null, cache.get("a"))
        assertEquals(mapOf(1 to 2.0), cache.get("b"))
        assertEquals(mapOf(1 to 3.0), cache.get("c"))
    }

    @Test
    fun `put returns a snapshot that later writes do not mutate`() = runTest {
        val cache = SeasonCache(maxEntries = SeasonCache.MaxEntries)
        val snapshot = cache.put("tt1:1", mapOf(1 to 8.0))
        cache.put("tt1:2", mapOf(2 to 9.0))

        assertEquals(mapOf("tt1:1" to mapOf(1 to 8.0)), snapshot)
        assertEquals(setOf("tt1:1", "tt1:2"), cache.snapshot().keys)
    }

    @Test
    fun `concurrent puts across the production cap stay consistent`() = runTest {
        assertEquals(400, SeasonCache.MaxEntries)
        val cache = SeasonCache()
        val failures = mutableListOf<Throwable>()
        val failureLock = Mutex()

        coroutineScope {
            repeat(Workers) { worker ->
                launch(Dispatchers.Default) {
                    try {
                        repeat(OperationsPerWorker) { index ->
                            val key = "tt$worker:$index"
                            val ratings = mapOf(1 to ratingFor(worker, index))
                            val snapshot = cache.put(key, ratings)
                            check(snapshot.size <= SeasonCache.MaxEntries) {
                                "snapshot grew past capacity: ${snapshot.size}"
                            }
                            if (index % 5 == 0) {
                                cache.get(key)?.let { stored ->
                                    check(stored == ratings) { "stored ratings changed for $key" }
                                }
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        failureLock.withLock { failures += error }
                    }
                }
            }
        }

        assertEquals(emptyList(), failures.map { it::class.simpleName to it.message })
        val snapshot = cache.snapshot()
        assertTrue(snapshot.isNotEmpty())
        assertTrue(snapshot.size <= SeasonCache.MaxEntries)
        snapshot.forEach { (key, ratings) ->
            val (worker, index) = key.removePrefix("tt").split(":")
            assertEquals(mapOf(1 to ratingFor(worker.toInt(), index.toInt())), ratings)
        }
    }

    @Test
    fun `unguarded linked hash map eviction fails the same concurrent load`() = runTest {
        repeat(UnguardedAttempts) {
            if (unguardedLoadFails()) return@runTest
        }
        fail("Expected ConcurrentModificationException or a corrupt cache from the unguarded map")
    }

    private suspend fun unguardedLoadFails(): Boolean {
        val cache = UnguardedSeasonCache(maxEntries = SeasonCache.MaxEntries)
        val failures = mutableListOf<Throwable>()
        val failureLock = Mutex()

        coroutineScope {
            repeat(Workers) { worker ->
                launch(Dispatchers.Default) {
                    try {
                        repeat(OperationsPerWorker) { index ->
                            cache.put("tt$worker:$index", mapOf(1 to ratingFor(worker, index)))
                            if (index % 5 == 0) cache.get("tt$worker:$index")
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        failureLock.withLock { failures += error }
                    }
                }
            }
        }

        if (failures.isNotEmpty()) return true
        return runCatching {
            val oversized = cache.size() > SeasonCache.MaxEntries
            val corruptValue = cache.entries().any { (key, ratings) ->
                val parts = key.removePrefix("tt").split(":")
                val worker = parts.getOrNull(0)?.toIntOrNull()
                val index = parts.getOrNull(1)?.toIntOrNull()
                worker == null || index == null || ratings != mapOf(1 to ratingFor(worker, index))
            }
            oversized || corruptValue
        }.getOrElse { true }
    }

    private fun ratingFor(worker: Int, index: Int): Double = worker * 100_000.0 + index

    /**
     * The pre-fix cache: a plain [LinkedHashMap] updated and evicted with no lock.
     * Eviction walks [LinkedHashMap.keys] while other coroutines insert.
     */
    private class UnguardedSeasonCache(
        private val maxEntries: Int,
    ) {
        private val entries = LinkedHashMap<String, Map<Int, Double>>()

        fun get(key: String): Map<Int, Double>? = entries[key]

        fun size(): Int = entries.size

        fun entries(): Map<String, Map<Int, Double>> = entries

        fun put(key: String, ratings: Map<Int, Double>) {
            entries[key] = ratings
            var spins = 0
            while (entries.size > maxEntries) {
                spins += 1
                if (spins > maxEntries + 8) error("eviction failed to shrink the cache")
                val oldestKey = entries.keys.firstOrNull() ?: break
                entries.remove(oldestKey)
            }
        }
    }

    private companion object {
        const val Workers = 8
        const val OperationsPerWorker = 2_000
        const val UnguardedAttempts = 4
    }
}
