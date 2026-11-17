package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.details.OfflineCacheLookup
import com.nuvio.app.features.details.OfflineCacheNetwork
import com.nuvio.app.features.details.OfflineDetailsCacheLimits
import com.nuvio.app.features.details.OfflineDetailsWriteGate
import com.nuvio.app.features.details.currentOfflineDetailsNetwork
import com.nuvio.app.features.details.offlineCacheDecision
import com.nuvio.app.features.details.offlineStampIsFresh
import com.nuvio.app.features.tracking.WatchProgressSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val released: String? = null,
    val hasAired: Boolean = true,
    val lastWatched: Long,
    val sortTimestamp: Long,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
)

@Serializable
data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float? = null,
    val progressKey: String? = null,
)

internal fun CachedInProgressItem.resolvedProgressKey(): String =
    progressKey?.takeIf(String::isNotBlank)
        ?: buildWatchProgressKey(
            contentId = contentId,
            seasonNumber = season,
            episodeNumber = episode,
        )

internal const val ContinueWatchingEnrichmentSchemaVersion = 1

@Serializable
private data class CachedEnrichmentPayload(
    val schemaVersion: Int = ContinueWatchingEnrichmentSchemaVersion,
    val savedAtMs: Long = 0L,
    val profileId: Int = 0,
    val nextUp: List<CachedNextUpItem> = emptyList(),
    val inProgress: List<CachedInProgressItem> = emptyList(),
)

internal fun capContinueWatchingItems(
    nextUp: List<CachedNextUpItem>,
    inProgress: List<CachedInProgressItem>,
    maxItems: Int = OfflineDetailsCacheLimits.ContinueWatchingMaxItems,
): Pair<List<CachedNextUpItem>, List<CachedInProgressItem>> {
    val cappedNextUp = if (nextUp.size <= maxItems) {
        nextUp
    } else {
        nextUp.sortedByDescending { item -> item.sortTimestamp }.take(maxItems)
    }
    val cappedInProgress = if (inProgress.size <= maxItems) {
        inProgress
    } else {
        inProgress.sortedByDescending { item -> item.lastWatched }.take(maxItems)
    }
    return cappedNextUp to cappedInProgress
}

internal object ContinueWatchingEnrichmentCache {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val storageKey = "cw_enrichment_cache"
    private val cacheLock = SynchronizedObject()
    private val cachedPayloads = mutableMapOf<CacheScope, CachedEnrichmentPayload?>()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun getNextUpSnapshot(
        profileId: Int,
        source: WatchProgressSource,
    ): List<CachedNextUpItem> =
        loadPayload(profileId = profileId, source = source)?.nextUp ?: emptyList()

    fun getInProgressSnapshot(
        profileId: Int,
        source: WatchProgressSource,
    ): List<CachedInProgressItem> =
        loadPayload(profileId = profileId, source = source)?.inProgress ?: emptyList()

    fun getSnapshots(
        profileId: Int,
        source: WatchProgressSource,
    ): Pair<List<CachedNextUpItem>, List<CachedInProgressItem>> {
        val payload = loadPayload(profileId = profileId, source = source)
        val nextUp = payload?.nextUp ?: emptyList()
        val inProgress = payload?.inProgress ?: emptyList()
        return nextUp to inProgress
    }

    fun savedAtMs(profileId: Int, source: WatchProgressSource): Long =
        loadPayload(profileId = profileId, source = source)?.savedAtMs ?: 0L

    fun refreshDecision(
        profileId: Int,
        source: WatchProgressSource,
        network: OfflineCacheNetwork = currentOfflineDetailsNetwork(),
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): com.nuvio.app.features.details.OfflineCacheDecision {
        val payload = loadPayload(profileId = profileId, source = source)
        val lookup = when {
            payload == null -> OfflineCacheLookup.Miss
            offlineStampIsFresh(payload.savedAtMs, nowMs, OfflineDetailsCacheLimits.ContinueWatchingTtlMs) ->
                OfflineCacheLookup.DiskFresh
            else -> OfflineCacheLookup.DiskStale
        }
        return offlineCacheDecision(lookup, network)
    }

    fun saveSnapshots(
        profileId: Int,
        source: WatchProgressSource,
        generation: Int,
        nextUp: List<CachedNextUpItem>,
        inProgress: List<CachedInProgressItem>,
        force: Boolean = false,
    ): Boolean = synchronized(cacheLock) {
        if (generation != _generation.value) return@synchronized false

        val capped = capContinueWatchingItems(nextUp.toList(), inProgress.toList())
        val payload = CachedEnrichmentPayload(
            schemaVersion = ContinueWatchingEnrichmentSchemaVersion,
            savedAtMs = Clock.System.now().toEpochMilliseconds(),
            profileId = profileId,
            nextUp = capped.first,
            inProgress = capped.second,
        )
        val scope = CacheScope(profileId = profileId, source = source)
        val existing = cachedPayloads[scope]
        if (
            !force &&
            existing?.nextUp == payload.nextUp &&
            existing.inProgress == payload.inProgress &&
            existing.profileId == payload.profileId
        ) {
            return@synchronized true
        }

        removeLegacyPayload(profileId)
        val encoded = runCatching {
            json.encodeToString(payload)
        }.getOrNull() ?: return@synchronized false
        val storageKey = continueWatchingEnrichmentStorageKey(profileId = profileId, source = source)
        cachedPayloads[scope] = payload
        if (!OfflineDetailsWriteGate.writesAllowed()) {
            OfflineDetailsWriteGate.defer {
                synchronized(cacheLock) {
                    if (generation != _generation.value) return@synchronized
                    if (cachedPayloads[scope] != payload) return@synchronized
                    ContinueWatchingEnrichmentStorage.savePayload(storageKey, encoded)
                }
            }
            return@synchronized true
        }
        ContinueWatchingEnrichmentStorage.savePayload(storageKey, encoded)
        true
    }

    fun invalidate(
        profileId: Int,
        source: WatchProgressSource,
    ) = synchronized(cacheLock) {
        ContinueWatchingEnrichmentStorage.removePayload(
            continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
        )
        removeLegacyPayload(profileId)
        cachedPayloads.remove(CacheScope(profileId = profileId, source = source))
        advanceGeneration()
    }

    fun clearAll(profileId: Int) = synchronized(cacheLock) {
        WatchProgressSource.entries.forEach { source ->
            ContinueWatchingEnrichmentStorage.removePayload(
                continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
            )
            cachedPayloads.remove(CacheScope(profileId = profileId, source = source))
        }
        removeLegacyPayload(profileId)
        advanceGeneration()
    }

    fun clearLocalState() = synchronized(cacheLock) {
        cachedPayloads.clear()
        advanceGeneration()
    }

    fun onProfileChanged() = synchronized(cacheLock) {
        cachedPayloads.clear()
        advanceGeneration()
    }

    private fun loadPayload(
        profileId: Int,
        source: WatchProgressSource,
    ): CachedEnrichmentPayload? = synchronized(cacheLock) {
        val scope = CacheScope(profileId = profileId, source = source)
        if (cachedPayloads.containsKey(scope)) return@synchronized cachedPayloads[scope]
        removeLegacyPayload(profileId)
        val raw = ContinueWatchingEnrichmentStorage.loadPayload(
            continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
        ) ?: run {
            cachedPayloads[scope] = null
            return@synchronized null
        }
        val decoded = decodeStoredEnrichment(raw, profileId)
        if (decoded == null) {
            cachedPayloads[scope] = null
            ContinueWatchingEnrichmentStorage.removePayload(
                continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
            )
            return@synchronized null
        }
        cachedPayloads[scope] = decoded
        decoded
    }

    internal fun storedEnrichmentName(raw: String, profileId: Int): String? =
        decodeStoredEnrichment(raw, profileId)?.nextUp?.firstOrNull()?.name

    private fun decodeStoredEnrichment(raw: String, profileId: Int): CachedEnrichmentPayload? {
        val decoded = runCatching { json.decodeFromString<CachedEnrichmentPayload>(raw) }.getOrNull() ?: return null
        if (decoded.schemaVersion != ContinueWatchingEnrichmentSchemaVersion) return null
        if (decoded.profileId != 0 && decoded.profileId != profileId) return null
        return decoded
    }

    private fun removeLegacyPayload(profileId: Int) {
        ContinueWatchingEnrichmentStorage.removePayload(legacyStorageKey(profileId))
    }

    private fun advanceGeneration() {
        _generation.value += 1
    }

    private data class CacheScope(
        val profileId: Int,
        val source: WatchProgressSource,
    )

    internal fun continueWatchingEnrichmentStorageKey(
        profileId: Int,
        source: WatchProgressSource,
    ): String = ProfileScopedKey.of(
        baseKey = "${storageKey}_${source.name.lowercase()}",
        profileId = profileId,
    )

    internal fun legacyStorageKey(profileId: Int): String =
        ProfileScopedKey.of(storageKey, profileId)
}
