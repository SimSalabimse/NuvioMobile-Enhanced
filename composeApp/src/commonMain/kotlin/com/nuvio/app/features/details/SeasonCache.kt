package com.nuvio.app.features.details

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

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

internal object SeasonEpisodeDisk {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = productionOfflineDiskCache(
        namespace = "season-episodes",
        maxEntries = OfflineDetailsCacheLimits.SeasonListMaxEntries,
        maxBytes = OfflineDetailsCacheLimits.SeasonListMaxBytes,
        ttlMs = OfflineDetailsCacheLimits.SeasonListTtlMs,
    )
    private val listSerializer = ListSerializer(StoredMetaVideo.serializer())

    fun save(profileKey: String, requestKey: String, videos: List<StoredMetaVideo>, savedAtMs: Long) {
        if (videos.isEmpty()) return
        val payload = runCatching { json.encodeToString(listSerializer, videos) }.getOrNull() ?: return
        cache.upsert(profileKey, requestKey, payload, savedAtMs)
    }

    fun read(profileKey: String, requestKey: String): List<StoredMetaVideo>? {
        val hit = cache.read(profileKey, requestKey) ?: return null
        return runCatching { json.decodeFromString(listSerializer, hit.payload) }.getOrNull()
    }
}

internal object SeasonRatingsDisk {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = productionOfflineDiskCache(
        namespace = "season-ratings",
        maxEntries = OfflineDetailsCacheLimits.SeasonRatingsMaxEntries,
        maxBytes = OfflineDetailsCacheLimits.SeasonRatingsMaxBytes,
        ttlMs = OfflineDetailsCacheLimits.SeasonRatingsTtlMs,
    )

    fun load(profileKey: String): Map<String, CachedOmdbSeason> {
        val rows = runCatching { cache.readAll(profileKey) }.getOrNull() ?: return emptyMap()
        val decoded = LinkedHashMap<String, CachedOmdbSeason>()
        for (row in rows) {
            val season = runCatching {
                json.decodeFromString(CachedOmdbSeason.serializer(), row.payload)
            }.getOrNull() ?: continue
            decoded[row.key] = season
        }
        return decoded
    }

    fun save(profileKey: String, snapshot: Map<String, CachedOmdbSeason>) {
        val now = Clock.System.now().toEpochMilliseconds()
        val rows = snapshot.mapNotNull { (key, season) ->
            val payload = runCatching {
                json.encodeToString(CachedOmdbSeason.serializer(), season)
            }.getOrNull() ?: return@mapNotNull null
            OfflineDiskEntry(
                key = key,
                savedAtMs = season.fetchedAt.takeIf { it > 0L } ?: now,
                touchedAtMs = now,
                payload = payload,
            )
        }
        cache.replaceAll(profileKey, rows)
    }
}
