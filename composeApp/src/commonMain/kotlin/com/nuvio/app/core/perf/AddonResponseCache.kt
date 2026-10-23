package com.nuvio.app.core.perf

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlin.time.TimeSource

private val cacheClockOrigin = TimeSource.Monotonic.markNow()

/**
 * Addon GET responses: identical requests in flight share one network call, and bodies stay
 * reusable for a short time. Prefetched bodies (next episode streams) live longer and are served
 * even while request dedupe is off. TheIntroDB URLs always go straight to the network.
 */
internal class AddonResponseCache(
    private val dedupeEnabled: () -> Boolean = { PerformanceSettings.isEnabled(PerformanceOptimization.RequestDedupe) },
    private val nowMs: () -> Long = { cacheClockOrigin.elapsedNow().inWholeMilliseconds },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val responseTtlMs: Long = 30_000L,
    private val maxEntries: Int = 48,
    private val maxTotalChars: Long = 4_000_000L,
    private val maxEntryChars: Int = 1_500_000,
) {
    private class Entry(val body: String, val expiresAtMs: Long, val prefetched: Boolean)

    private class Flight(val prefetch: Boolean) {
        lateinit var deferred: Deferred<String>
        var waiters = 0
    }

    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, Entry>()
    private val flights = HashMap<String, Flight>()
    private var totalChars = 0L

    suspend fun fetch(url: String, forceRefresh: Boolean, load: suspend () -> String): String {
        if (isAlwaysFresh(url)) return load()
        val dedupe = dedupeEnabled()
        if (forceRefresh) {
            return load().also { body -> if (dedupe) store(url, body, responseTtlMs, prefetched = false) }
        }
        cachedBody(url, includeShortLived = dedupe)?.let { return it }
        var joined = false
        val flight = synchronized(lock) {
            val existing = flights[url]?.takeIf { dedupe || it.prefetch }
            joined = existing != null
            (existing ?: if (dedupe) startFlightLocked(url, prefetch = false, ttlMs = responseTtlMs, load = load) else null)
                ?.also { it.waiters++ }
        } ?: return load()
        if (joined) PerformanceMetrics.count(PerformanceCounter.AddonRequestsShared)
        return await(url, flight)
    }

    /** Warms [url] in the background; false when it is already cached, in flight, or must stay fresh. */
    fun prefetch(url: String, ttlMs: Long, load: suspend () -> String): Boolean {
        if (isAlwaysFresh(url)) return false
        if (cachedBody(url, includeShortLived = true, countHit = false) != null) return false
        return synchronized(lock) {
            if (flights.containsKey(url)) return@synchronized false
            startFlightLocked(url, prefetch = true, ttlMs = ttlMs, load = load)
            true
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        totalChars = 0L
    }

    private fun startFlightLocked(url: String, prefetch: Boolean, ttlMs: Long, load: suspend () -> String): Flight {
        val flight = Flight(prefetch)
        flight.deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                load().also { body -> store(url, body, ttlMs, prefetched = prefetch) }
            } finally {
                synchronized(lock) { if (flights[url] === flight) flights.remove(url) }
            }
        }
        flights[url] = flight
        flight.deferred.start()
        return flight
    }

    private suspend fun await(url: String, flight: Flight): String {
        try {
            return flight.deferred.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                flight.waiters--
                if (flight.waiters <= 0 && !flight.prefetch && flight.deferred.isActive) {
                    flight.deferred.cancel()
                    if (flights[url] === flight) flights.remove(url)
                }
            }
            throw cancelled
        }
    }

    private fun cachedBody(url: String, includeShortLived: Boolean, countHit: Boolean = true): String? {
        val entry = synchronized(lock) {
            val entry = entries[url] ?: return null
            if (entry.expiresAtMs <= nowMs()) {
                entries.remove(url)
                totalChars -= entry.body.length
                return null
            }
            if (!entry.prefetched && !includeShortLived) return null
            entries.remove(url)
            entries[url] = entry
            entry
        }
        if (countHit) {
            PerformanceMetrics.count(
                if (entry.prefetched) PerformanceCounter.NextEpisodePrefetchHits else PerformanceCounter.AddonCacheHits,
            )
        }
        return entry.body
    }

    private fun store(url: String, body: String, ttlMs: Long, prefetched: Boolean) {
        if (body.length > maxEntryChars || isAlwaysFresh(url)) return
        synchronized(lock) {
            entries.remove(url)?.let { totalChars -= it.body.length }
            entries[url] = Entry(body, nowMs() + ttlMs, prefetched)
            totalChars += body.length
            while (entries.size > maxEntries || totalChars > maxTotalChars) {
                val eldest = entries.keys.iterator().next()
                entries.remove(eldest)?.let { totalChars -= it.body.length }
            }
        }
    }

    companion object {
        /** TheIntroDB (submit, preview and lookups) must never be served from or join a cached request. */
        fun isAlwaysFresh(url: String): Boolean {
            val host = url.substringAfter("://", url).substringBefore('/').substringBefore('?')
                .substringAfterLast('@').substringBefore(':').lowercase()
            return "introdb" in host
        }
    }
}
