package com.nuvio.app.features.details

import com.nuvio.app.core.perf.BoundedCache
import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import com.nuvio.app.core.automation.AutomationStubs
import com.nuvio.app.core.automation.AutomationTestMode
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.core.poster.withCustomPosterUrls
import com.nuvio.app.features.addons.supportsResource
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.offline.downloadedMetaFor
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerUserStateProjection
import com.nuvio.app.features.servers.message
import com.nuvio.app.features.servers.serverFailure
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.TMDB_RECOMMENDATIONS_PAGE_SIZE
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.TraktRelatedRepository
import com.nuvio.app.features.trakt.MoreLikeThisSourcePreference
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.trakt.shouldUseTraktMoreLikeThis
import com.nuvio.app.features.watched.WatchedClock
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal data class MetaFetch(
    val meta: MetaDetails?,
    val episodesComplete: Boolean = true,
)

object MetaDetailsRepository {
    private data class CachedMetaEntry(
        val baseMeta: MetaDetails,
        val metaScreenMeta: MetaDetails? = null,
        val metaScreenSettingsFingerprint: String? = null,
    )

    private data class TmdbFallbackMeta(
        val meta: MetaDetails,
        val episodesComplete: Boolean,
    )

    private val log = Logger.withTag("MetaDetailsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()
    private var activeRequestKey: String? = null
    private val cachedMetaByRequestKey = BoundedCache<String, CachedMetaEntry>(maxEntries = 150)
    private val savedAtLock = SynchronizedObject()
    private val savedAtMsByRequestKey = HashMap<String, Long>()
    private val refreshing = mutableSetOf<String>()
    private var boundProfileKey: String? = null

    private val fixtureMetas = HashMap<String, MetaDetails>()

    internal fun installFixture(meta: MetaDetails) {
        val key = "${meta.type}:${meta.id}"
        fixtureMetas[key] = meta
        _uiState.value = MetaDetailsUiState(isLoading = false, meta = meta, episodesComplete = true)
        activeRequestKey = key
    }

    fun load(type: String, id: String, offlineTitle: String? = null) {
        fixtureMetas["$type:$id"]?.let { meta ->
            activeRequestKey = "$type:$id"
            _uiState.value = MetaDetailsUiState(isLoading = false, meta = meta, episodesComplete = true)
            return
        }
        if (AutomationTestMode.isActive) {
            activeRequestKey = "$type:$id"
            val meta = AutomationStubs.meta(type, id)
            _uiState.value = if (meta == null) {
                MetaDetailsUiState(isLoading = false, errorMessage = "Automation title is not in the stub catalog")
            } else {
                MetaDetailsUiState(isLoading = false, meta = meta, episodesComplete = true)
            }
            return
        }
        bindProfile()
        log.d { "load() called — type=$type id=$id" }
        InAppLogger.info("Metadata/MetaDetailsRepository", "Loading meta type=$type id=$id")
        val requestKey = "$type:$id"
        ServerItemRef.parse(id)?.let { ref ->
            loadServerMeta(requestKey, ref, type, id, offlineTitle)
            return
        }
        val currentState = _uiState.value
        val mdbListSettings = MdbListSettingsRepository.snapshot()
        val metaScreenSettingsFingerprint = buildMetaScreenSettingsFingerprint(mdbListSettings)

        cachedMetaByRequestKey[requestKey]?.let { cachedEntry ->
            refreshIfStale(type, id, requestKey, updateUi = true)
            cachedEntry.metaScreenMeta
                ?.takeIf { cachedEntry.metaScreenSettingsFingerprint == metaScreenSettingsFingerprint }
                ?.let { cachedMeta ->
                    InAppLogger.debug("Metadata/MetaDetailsRepository", "Using cached enriched meta type=$type id=$id")
                    _uiState.value = MetaDetailsUiState(meta = cachedMeta.withUnreleasedFilter())
                    activeRequestKey = requestKey
                    return
                }

            val cachedBaseMeta = cachedEntry.baseMeta
            if (
                currentOfflineDetailsNetwork() == OfflineCacheNetwork.Offline ||
                !shouldEnrichForMetaScreen(cachedBaseMeta, id, mdbListSettings)
            ) {
                InAppLogger.debug("Metadata/MetaDetailsRepository", "Using cached base meta type=$type id=$id")
                _uiState.value = MetaDetailsUiState(meta = cachedBaseMeta.withUnreleasedFilter())
                activeRequestKey = requestKey
                return
            }

            if (currentState.isLoading && activeRequestKey == requestKey) {
                log.d { "Meta screen enrichment already in flight — type=$type id=$id" }
                InAppLogger.debug("Metadata/MetaDetailsRepository", "Meta enrichment already in flight type=$type id=$id")
                return
            }

            activeRequestKey = requestKey
            _uiState.value = MetaDetailsUiState(
                isLoading = true,
                meta = cachedBaseMeta,
            )

            scope.launch {
                val enrichedMeta = withContext(Dispatchers.Default) {
                    enrichForMetaScreen(
                        requestKey = requestKey,
                        meta = cachedBaseMeta,
                        fallbackItemId = id,
                        fallbackItemType = type,
                        settings = mdbListSettings,
                        settingsFingerprint = metaScreenSettingsFingerprint,
                    )
                }
                _uiState.value = MetaDetailsUiState(meta = enrichedMeta.withUnreleasedFilter())
                activeRequestKey = requestKey
            }
            return
        }

        val currentMeta = currentState.meta
        if (
            shouldSkipLoadedMetaReload(
                sameShow = currentMeta != null && currentMeta.type == type && currentMeta.id == id,
                isLoading = currentState.isLoading,
                episodesComplete = currentState.episodesComplete,
            )
        ) {
            log.d { "Skipping reload for cached meta — type=$type id=$id" }
            InAppLogger.debug("Metadata/MetaDetailsRepository", "Skipping reload for current meta type=$type id=$id")
            activeRequestKey = requestKey
            return
        }

        if (currentState.isLoading && activeRequestKey == requestKey) {
            log.d { "Request already in flight — type=$type id=$id" }
            InAppLogger.debug("Metadata/MetaDetailsRepository", "Request already in flight type=$type id=$id")
            return
        }

        activeRequestKey = requestKey
        val visibleIncomplete = currentState.meta?.takeIf {
            it.type == type && it.id == id && !currentState.episodesComplete
        }
        _uiState.value = if (visibleIncomplete != null) {
            currentState.copy(isLoading = true, errorMessage = null)
        } else {
            MetaDetailsUiState(isLoading = true)
        }

        scope.launch {
            val diskHit = readDiskTitle(requestKey)
            if (activeRequestKey == requestKey && diskHit != null) {
                rememberDisk(requestKey, diskHit)
                val network = currentOfflineDetailsNetwork()
                val lookup = if (diskHit.fresh) OfflineCacheLookup.DiskFresh else OfflineCacheLookup.DiskStale
                val decision = offlineCacheDecision(lookup, network)
                _uiState.value = MetaDetailsUiState(
                    isLoading = false,
                    meta = diskHit.meta.withUnreleasedFilter(),
                )
                if (!decision.refreshRequested) {
                    activeRequestKey = requestKey
                    return@launch
                }
                } else if (
                activeRequestKey == requestKey &&
                currentOfflineDetailsNetwork() == OfflineCacheNetwork.Offline
            ) {
                val local = localOfflineMeta(type, id, offlineTitle)
                if (local != null) {
                    _uiState.value = MetaDetailsUiState(meta = local)
                    activeRequestKey = requestKey
                } else {
                    _uiState.value = MetaDetailsUiState(
                        errorMessage = getString(Res.string.network_no_internet_connection),
                    )
                    activeRequestKey = null
                }
                return@launch
            }
            val keepVisibleOnFailure = _uiState.value.meta != null
            val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
            val manifests = findReadyMetaManifests(type = type, id = metaLookupId)
            InAppLogger.info("Metadata/MetaDetailsRepository", "Resolved meta lookup id=$metaLookupId manifests=${manifests.size} originalId=$id")

            if (manifests.isEmpty()) {
                val tmdbMeta = tryFetchTmdbFallbackMeta(type = type, id = id)
                if (tmdbMeta != null) {
                    publishTmdbFallback(
                        requestKey = requestKey,
                        fallback = tmdbMeta,
                        fallbackItemId = id,
                        fallbackItemType = type,
                        mdbListSettings = mdbListSettings,
                        metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
                    )
                    return@launch
                }

                log.w { "No addon provides meta for type=$type id=$id" }
                InAppLogger.warn("Metadata/MetaDetailsRepository", "No addon provides meta type=$type id=$id lookup=$metaLookupId")
                publishUnavailable(requestKey, getString(Res.string.details_no_addon_meta), keepVisibleOnFailure)
                return@launch
            }

            val result = if (keepVisibleOnFailure) {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    withContext(Dispatchers.Default) {
                        fetchFirstAvailableMeta(manifests, type, metaLookupId)
                    }
                }
            } else {
                withContext(Dispatchers.Default) {
                    fetchFirstAvailableMeta(manifests, type, metaLookupId)
                }
            }
            if (result != null) {
                publishLoadedMeta(
                    requestKey = requestKey,
                    meta = result,
                    fallbackItemId = metaLookupId,
                    fallbackItemType = type,
                    mdbListSettings = mdbListSettings,
                    metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
                )
                return@launch
            }

            val tmdbMeta = tryFetchTmdbFallbackMeta(type = type, id = id)
            if (tmdbMeta != null) {
                publishTmdbFallback(
                    requestKey = requestKey,
                    fallback = tmdbMeta,
                    fallbackItemId = id,
                    fallbackItemType = type,
                    mdbListSettings = mdbListSettings,
                    metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
                )
                return@launch
            }

            InAppLogger.error("Metadata/MetaDetailsRepository", "Failed to load meta from all providers type=$type id=$id lookup=$metaLookupId")
            publishUnavailable(requestKey, getString(Res.string.details_load_failed_all_addons), keepVisibleOnFailure)
        }
    }

    suspend fun cachedMeta(type: String, id: String): MetaDetails? {
        bindProfile()
        peek(type, id)?.let { return it }
        val requestKey = "$type:$id"
        val diskHit = readDiskTitle(requestKey) ?: return null
        rememberDisk(requestKey, diskHit)
        return diskHit.meta.withUnreleasedFilter()
    }

    fun peek(type: String, id: String): MetaDetails? {
        fixtureMetas["$type:$id"]?.let { return it }
        bindProfile()
        val requestKey = "$type:$id"
        val currentMeta = _uiState.value.meta?.takeIf { it.type == type && it.id == id }
        if (currentMeta != null) return currentMeta

        val cachedEntry = cachedMetaByRequestKey[requestKey] ?: return null
        val cachedMeta = cachedEntry.metaScreenMeta
            ?.takeIf {
                cachedEntry.metaScreenSettingsFingerprint ==
                    buildMetaScreenSettingsFingerprint(MdbListSettingsRepository.snapshot())
            }
            ?: cachedEntry.baseMeta
        return cachedMeta.withUnreleasedFilter()
    }

    fun clearCachedMetadata() {
        cachedMetaByRequestKey.clear()
        synchronized(savedAtLock) { savedAtMsByRequestKey.clear() }
    }

    fun onProfileChanged() {
        clear()
        boundProfileKey = offlineDetailsProfileKey() ?: "none"
    }

    fun clear() {
        boundProfileKey = null
        activeRequestKey = null
        cachedMetaByRequestKey.clear()
        synchronized(savedAtLock) { savedAtMsByRequestKey.clear() }
        refreshing.clear()
        _uiState.value = MetaDetailsUiState()
    }

    /**
     * A TMDB series fallback with no episodes must not be cached: a later open would
     * keep reading that empty list. Movies have no episode list, so they still cache.
     * A show with any failed season is not cached either, even when some episodes arrived.
     */
    internal fun shouldCacheFetchedStandaloneMeta(
        type: String,
        videoCount: Int,
        seasonsComplete: Boolean = true,
    ): Boolean {
        if (!seasonsComplete) return false
        val normalized = type.trim().lowercase()
        val series = normalized == "series" || normalized == "tv" || normalized == "show" || normalized == "tvshow"
        return videoCount > 0 || !series
    }

    /** Episodes already loaded stay visible when a later season fails. */
    internal fun visibleFallbackEpisodes(
        videos: List<MetaVideo>,
        episodesComplete: Boolean,
    ): List<MetaVideo> = if (episodesComplete) videos else videos

    /**
     * A finished show is not fetched again. An incomplete season fill is not finished,
     * so a later load() is allowed through.
     */
    internal fun shouldSkipLoadedMetaReload(
        sameShow: Boolean,
        isLoading: Boolean,
        episodesComplete: Boolean,
    ): Boolean = sameShow && !isLoading && episodesComplete

    suspend fun fetch(
        type: String,
        id: String,
        cacheResult: Boolean = true,
        useCache: Boolean = true,
    ): MetaDetails? = fetchReporting(type, id, cacheResult, useCache).meta

    internal suspend fun fetchReporting(
        type: String,
        id: String,
        cacheResult: Boolean = true,
        useCache: Boolean = true,
    ): MetaFetch {
        bindProfile()
        val requestKey = "$type:$id"
        if (useCache) {
            cachedMetaByRequestKey[requestKey]?.let { cached ->
                refreshIfStale(type, id, requestKey, updateUi = false)
                return MetaFetch(cached.baseMeta)
            }
            val diskHit = readDiskTitle(requestKey)
            if (diskHit != null) {
                rememberDisk(requestKey, diskHit)
                refreshIfStale(type, id, requestKey, updateUi = false)
                return MetaFetch(diskHit.meta)
            }
        }
        ServerItemRef.parse(id)?.let { ref ->
            return MetaFetch(
                runCatching { ServerCatalog.details(ref).meta }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                    ?.also { meta ->
                        if (cacheResult) {
                            cachedMetaByRequestKey[requestKey] = CachedMetaEntry(baseMeta = meta)
                            stampFetched(requestKey, meta)
                        }
                    },
            )
        }

        val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
        val manifests = findReadyMetaManifests(type = type, id = metaLookupId)

        for (manifest in manifests) {
            val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                tryFetchMeta(manifest, type, metaLookupId, includeMdbList = false)
            }
            if (result != null) {
                if (cacheResult) {
                    cachedMetaByRequestKey[requestKey] = CachedMetaEntry(baseMeta = result)
                    stampFetched(requestKey, result)
                }
                return MetaFetch(result)
            }
        }

        val fallback = tryFetchTmdbFallbackMeta(
            type = type,
            id = id,
            bypassSeasonCache = !useCache,
        )
        if (
            fallback != null &&
            cacheResult &&
            shouldCacheFetchedStandaloneMeta(
                type = fallback.meta.type.ifBlank { type },
                videoCount = fallback.meta.videos.size,
                seasonsComplete = fallback.episodesComplete,
            )
        ) {
            cachedMetaByRequestKey[requestKey] = CachedMetaEntry(baseMeta = fallback.meta)
            stampFetched(requestKey, fallback.meta)
        }
        return MetaFetch(fallback?.meta, episodesComplete = fallback?.episodesComplete ?: true)
    }

    private val backgroundBackoffUntilByHost = mutableMapOf<String, Long>()

    internal suspend fun fetchLightweight(type: String, id: String): MetaLookupOutcome {
        val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
        val manifests = findReadyMetaManifests(type = type, id = metaLookupId)
        if (manifests.isEmpty()) {
            return tryFetchTmdbFallbackMeta(type = type, id = id)
                ?.meta
                ?.let { meta -> MetaLookupOutcome.Loaded(meta) }
                ?: MetaLookupOutcome.Unavailable
        }

        var throttledUntil: Long? = null
        for (manifest in manifests) {
            val host = manifest.transportUrl.addonHost()
            val now = WatchedClock.nowEpochMs()
            val blockedUntil = backgroundBackoffUntilByHost[host]
            if (blockedUntil != null && blockedUntil > now) {
                throttledUntil = minOf(throttledUntil ?: blockedUntil, blockedUntil)
                continue
            }
            val url = buildAddonResourceUrl(
                manifestUrl = manifest.transportUrl,
                resource = "meta",
                type = type,
                id = metaLookupId,
            )
            val response = try {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    httpRequestRaw(
                        method = "GET",
                        url = url,
                        headers = mapOf("Accept" to "application/json"),
                        body = "",
                        maxResponseBodyBytes = LIGHTWEIGHT_META_MAX_BYTES,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w(error) { "Lightweight meta request failed url=$url" }
                null
            }
            if (response == null) continue

            if (response.status == 429 || response.status == 503) {
                val until = now + response.retryAfterMs()
                backgroundBackoffUntilByHost[host] = until
                InAppLogger.warn(
                    "Metadata/AddonFetch",
                    "Background lookups paused host=$host status=${response.status} for ${(until - now) / 1000}s",
                )
                throttledUntil = minOf(throttledUntil ?: until, until)
                continue
            }
            if (response.status !in 200..299 || response.body.isBlank()) continue

            val meta = try {
                withContext(Dispatchers.Default) { MetaDetailsParser.parse(response.body) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w(error) { "Failed to parse lightweight meta url=$url" }
                null
            }
            if (meta != null) return MetaLookupOutcome.Loaded(meta)
        }

        return throttledUntil?.let { until -> MetaLookupOutcome.Throttled(until) } ?: MetaLookupOutcome.Failed
    }

    private fun String.addonHost(): String =
        substringAfter("://").substringBefore('/').substringBefore('?').lowercase()

    private fun com.nuvio.app.features.addons.RawHttpResponse.retryAfterMs(): Long {
        val seconds = headers["retry-after"]?.trim()?.toLongOrNull()
        val delayMs = seconds?.times(1_000L) ?: DEFAULT_BACKGROUND_BACKOFF_MS
        return delayMs.coerceIn(MIN_BACKGROUND_BACKOFF_MS, MAX_BACKGROUND_BACKOFF_MS)
    }

    private const val LIGHTWEIGHT_META_MAX_BYTES = 8 * 1024 * 1024
    private const val DEFAULT_BACKGROUND_BACKOFF_MS = 2L * 60_000L
    private const val MIN_BACKGROUND_BACKOFF_MS = 30_000L
    private const val MAX_BACKGROUND_BACKOFF_MS = 15L * 60_000L

    private fun loadServerMeta(
        requestKey: String,
        ref: ServerItemRef,
        type: String,
        id: String,
        offlineTitle: String?,
    ) {
        if (_uiState.value.isLoading && activeRequestKey == requestKey) return
        activeRequestKey = requestKey
        _uiState.value = MetaDetailsUiState(
            isLoading = true,
            meta = cachedMetaByRequestKey[requestKey]?.baseMeta,
        )
        scope.launch {
            if (cachedMetaByRequestKey[requestKey] == null) {
                val diskHit = readDiskTitle(requestKey)
                if (activeRequestKey == requestKey && diskHit != null) {
                    rememberDisk(requestKey, diskHit)
                    val lookup = if (diskHit.fresh) OfflineCacheLookup.DiskFresh else OfflineCacheLookup.DiskStale
                    val decision = offlineCacheDecision(lookup, currentOfflineDetailsNetwork())
                    _uiState.value = MetaDetailsUiState(
                        isLoading = decision.refreshRequested,
                        meta = diskHit.meta.withUnreleasedFilter(),
                    )
                    if (!decision.refreshRequested) return@launch
                } else if (currentOfflineDetailsNetwork() == OfflineCacheNetwork.Offline) {
                    val local = localOfflineMeta(type, id, offlineTitle)
                    if (local != null) {
                        _uiState.value = MetaDetailsUiState(meta = local)
                        activeRequestKey = requestKey
                    } else {
                        _uiState.value = MetaDetailsUiState(
                            errorMessage = getString(Res.string.network_no_internet_connection),
                        )
                        activeRequestKey = null
                    }
                    return@launch
                }
            }
            val visible = _uiState.value.meta
            val result = if (visible != null) {
                val details = withTimeoutOrNull(FETCH_TIMEOUT_MS) { ServerCatalog.details(ref) }
                if (details == null) {
                    Result.failure(IllegalStateException("timeout"))
                } else {
                    Result.success(details)
                }
            } else {
                runCatching { ServerCatalog.details(ref) }
            }
            if (activeRequestKey != requestKey) return@launch
            result.fold(
                onSuccess = { details ->
                    val meta = details.meta
                    ServerUserStateProjection.apply(details)
                    cachedMetaByRequestKey[requestKey] = CachedMetaEntry(baseMeta = meta)
                    stampFetched(requestKey, meta)
                    _uiState.value = MetaDetailsUiState(meta = meta)
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    log.w { "Server details failed: ${error.serverFailure()}" }
                    val cached = cachedMetaByRequestKey[requestKey]?.baseMeta
                    _uiState.value = MetaDetailsUiState(
                        meta = cached,
                        errorMessage = if (cached == null) getString(error.serverFailure().message()) else null,
                    )
                    if (cached == null) activeRequestKey = null
                },
            )
        }
    }

    private const val FETCH_TIMEOUT_MS = 5_000L
    private const val ADDON_META_TIMEOUT_MS = 10_000L
    private const val METADATA_PROVIDER_READY_TIMEOUT_MS = 10_000L
    private const val TMDB_ENRICH_TIMEOUT_MS = 5_000L
    private const val MDBLIST_ENRICH_TIMEOUT_MS = 5_000L

    private suspend fun fetchFirstAvailableMeta(
        manifests: List<AddonManifest>,
        type: String,
        id: String,
    ): MetaDetails? = coroutineScope {
        TmdbSettingsRepository.ensureLoaded()
        val tmdbWarmup = async {
            runCatching {
                TmdbMetadataService.prefetchEnrichment(
                    type = type,
                    id = id,
                    settings = TmdbSettingsRepository.snapshot(),
                )
            }.onFailure { if (it is CancellationException) throw it }
        }
        val attempts = manifests.map { manifest ->
            async {
                withTimeoutOrNull(ADDON_META_TIMEOUT_MS) {
                    fetchAddonMeta(manifest, type, id)
                }
            }
        }
        var selected: Pair<AddonManifest, MetaDetails>? = null
        for ((index, attempt) in attempts.withIndex()) {
            val meta = attempt.await() ?: continue
            selected = manifests[index] to meta
            break
        }
        attempts.forEach { it.cancel() }
        val (manifest, meta) = selected ?: run {
            tmdbWarmup.cancel()
            return@coroutineScope null
        }
        val enriched = enrichFetchedMeta(manifest, meta, id, includeMdbList = false, tmdbWarmup = tmdbWarmup)
        tmdbWarmup.cancel()
        enriched
    }

    private suspend fun tryFetchMeta(
        manifest: AddonManifest,
        type: String,
        id: String,
        includeMdbList: Boolean,
    ): MetaDetails? {
        val meta = fetchAddonMeta(manifest, type, id) ?: return null
        return enrichFetchedMeta(manifest, meta, id, includeMdbList)
    }

    private suspend fun fetchAddonMeta(
        manifest: AddonManifest,
        type: String,
        id: String,
    ): MetaDetails? {
        val url = buildAddonResourceUrl(
            manifestUrl = manifest.transportUrl,
            resource = "meta",
            type = type,
            id = id,
        )

        return try {
            log.d { "Fetching meta from: $url" }
            InAppLogger.info("Metadata/AddonFetch", "Fetching meta type=$type id=$id url=${InAppLogger.redactUrl(url)}")
            val payload = fetchAddonResponseText(url)
            log.d { "Raw payload length=${payload.length}, first 500 chars: ${payload.take(500)}" }
            InAppLogger.debug("Metadata/AddonFetch", "Meta payload length=${payload.length} type=$type id=$id")
            withContext(Dispatchers.Default) { MetaDetailsParser.parse(payload) }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.e(e) { "Failed to fetch/parse meta from $url (manifest=${manifest.transportUrl})" }
            InAppLogger.warn("Metadata/AddonFetch", "Failed meta source=${manifest.name} url=${InAppLogger.redactUrl(url)} error=${InAppLogger.throwableSummary(e)}")
            null
        }
    }

    private suspend fun enrichFetchedMeta(
        manifest: AddonManifest,
        result: MetaDetails,
        id: String,
        includeMdbList: Boolean,
        tmdbWarmup: Deferred<*>? = null,
    ): MetaDetails {
        TmdbSettingsRepository.ensureLoaded()
        val tmdbEnriched = runCatching {
            withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
                tmdbWarmup?.await()
                TmdbMetadataService.enrichMeta(
                    meta = result,
                    fallbackItemId = id,
                    settings = TmdbSettingsRepository.snapshot(),
                )
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: result
        val enriched = if (includeMdbList) {
            MdbListSettingsRepository.ensureLoaded()
            runCatching {
                withTimeoutOrNull(MDBLIST_ENRICH_TIMEOUT_MS) {
                    MdbListMetadataService.enrichMeta(
                        meta = tmdbEnriched,
                        fallbackItemId = id,
                        settings = MdbListSettingsRepository.snapshot(),
                    )
                }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: tmdbEnriched
        } else {
            tmdbEnriched
        }
        log.d { "Parsed meta: type=${enriched.type}, name=${enriched.name}, videos=${enriched.videos.size}" }
        InAppLogger.info(
            "Metadata/AddonFetch",
            "Parsed meta source=${manifest.name} type=${enriched.type} id=${enriched.id} name=${enriched.name} videos=${enriched.videos.size}",
        )
        if (enriched.videos.isNotEmpty()) {
            val first = enriched.videos.first()
            log.d { "First video: id=${first.id} title=${first.title} s=${first.season} e=${first.episode} embeddedStreams=${first.streams.size}" }
            InAppLogger.debug("Metadata/AddonFetch", "First video id=${first.id} title=${first.title} s=${first.season} e=${first.episode} embeddedStreams=${first.streams.size}")
        }
        return enriched
    }

    private suspend fun findReadyMetaManifests(type: String, id: String): List<AddonManifest> {
        AddonRepository.initialize()

        findMetaManifests(AddonRepository.uiState.value, type, id).takeIf { it.isNotEmpty() }?.let { return it }

        if (!AddonRepository.uiState.value.hasPendingEnabledAddonManifests()) {
            return emptyList()
        }

        val readyState = withTimeoutOrNull(METADATA_PROVIDER_READY_TIMEOUT_MS) {
            AddonRepository.uiState.first { state ->
                findMetaManifests(state, type, id).isNotEmpty() ||
                    !state.hasPendingEnabledAddonManifests()
            }
        } ?: AddonRepository.uiState.value

        return findMetaManifests(readyState, type, id)
    }

    private fun findMetaManifests(state: com.nuvio.app.features.addons.AddonsUiState, type: String, id: String): List<AddonManifest> =
        state.addons
            .enabledAddons()
            .mapNotNull { it.manifest }
            .filter { manifest -> manifest.supportsResource("meta", type, id) }

    private fun com.nuvio.app.features.addons.AddonsUiState.hasPendingEnabledAddonManifests(): Boolean =
        addons.enabledAddons().any { addon -> addon.manifest == null && addon.isRefreshing }

    private suspend fun resolveMetaLookupId(itemId: String, itemType: String): String {
        val tmdbId = itemId
            .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toIntOrNull()
            ?: return itemId

        val resolved = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            TmdbService.tmdbToImdb(tmdbId = tmdbId, mediaType = itemType)
        }
            ?.takeIf { it.isNotBlank() }
            ?: itemId
        InAppLogger.debug("Metadata/MetaDetailsRepository", "TMDB lookup tmdb:$tmdbId mediaType=$itemType resolved=$resolved")
        return resolved
    }

    private suspend fun tryFetchTmdbFallbackMeta(
        type: String,
        id: String,
        bypassSeasonCache: Boolean = false,
    ): TmdbFallbackMeta? {
        InAppLogger.info("Metadata/TMDB", "Fetching standalone fallback meta type=$type id=$id bypassSeasonCache=$bypassSeasonCache")
        val result = withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
            TmdbMetadataService.loadStandaloneMeta(
                type = type,
                id = id,
                settings = TmdbSettingsRepository.snapshot(),
                bypassSeasonCache = bypassSeasonCache,
            )
        }
        if (result == null) {
            InAppLogger.warn("Metadata/TMDB", "Standalone fallback returned empty type=$type id=$id")
        } else {
            InAppLogger.info(
                "Metadata/TMDB",
                "Standalone fallback loaded type=${result.meta.type} id=${result.meta.id} name=${result.meta.name} " +
                    "episodes=${result.meta.videos.size} complete=${result.episodesComplete}",
            )
        }
        return result?.let { TmdbFallbackMeta(it.meta, it.episodesComplete) }
    }

    private suspend fun publishTmdbFallback(
        requestKey: String,
        fallback: TmdbFallbackMeta,
        fallbackItemId: String,
        fallbackItemType: String,
        mdbListSettings: com.nuvio.app.features.mdblist.MdbListSettings,
        metaScreenSettingsFingerprint: String,
    ) {
        val visible = fallback.meta.copy(
            videos = visibleFallbackEpisodes(fallback.meta.videos, fallback.episodesComplete),
        )
        if (!fallback.episodesComplete) {
            _uiState.value = MetaDetailsUiState(
                isLoading = false,
                meta = visible.withUnreleasedFilter(),
                episodesComplete = false,
            )
            activeRequestKey = null
            return
        }
        publishLoadedMeta(
            requestKey = requestKey,
            meta = visible,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType,
            mdbListSettings = mdbListSettings,
            metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
        )
    }

    private suspend fun publishLoadedMeta(
        requestKey: String,
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
        mdbListSettings: com.nuvio.app.features.mdblist.MdbListSettings,
        metaScreenSettingsFingerprint: String,
    ) {
        val cachedEntry = CachedMetaEntry(baseMeta = meta)
        cachedMetaByRequestKey[requestKey] = cachedEntry
        stampFetched(requestKey, meta)

        if (!shouldEnrichForMetaScreen(meta, fallbackItemId, mdbListSettings)) {
            _uiState.value = MetaDetailsUiState(meta = meta.withUnreleasedFilter())
            activeRequestKey = requestKey
            return
        }

        _uiState.value = MetaDetailsUiState(
            isLoading = true,
            meta = meta,
        )
        val enrichedMeta = withContext(Dispatchers.Default) {
            enrichForMetaScreen(
                requestKey = requestKey,
                meta = meta,
                fallbackItemId = fallbackItemId,
                fallbackItemType = fallbackItemType,
                settings = mdbListSettings,
                settingsFingerprint = metaScreenSettingsFingerprint,
            )
        }
        cachedMetaByRequestKey[requestKey] = cachedEntry.copy(
            metaScreenMeta = enrichedMeta,
            metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
        )
        stampFetched(requestKey, enrichedMeta)
        _uiState.value = MetaDetailsUiState(meta = enrichedMeta.withUnreleasedFilter())
        activeRequestKey = requestKey
    }

    private suspend fun enrichForMetaScreen(
        requestKey: String,
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
        settingsFingerprint: String,
    ): MetaDetails {
        val mdbListEnrichedMeta = withTimeoutOrNull(MDBLIST_ENRICH_TIMEOUT_MS) {
            MdbListMetadataService.enrichMeta(
                meta = meta,
                fallbackItemId = fallbackItemId,
                settings = settings,
            )
        } ?: meta
        // Ratings are optional. This runs inside a Main-dispatcher launch with no
        // exception handler, so a cache or network failure must not escape and abort.
        val imdbRatedMeta = try {
            OmdbEpisodeRatingsService.applyTo(
                meta = mdbListEnrichedMeta,
                fallbackItemId = fallbackItemId,
                settings = TmdbSettingsRepository.snapshot(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "Failed to apply OMDb episode ratings: ${error.message}" }
            mdbListEnrichedMeta
        }
        val enrichedMeta = applyMoreLikeThisSource(
            meta = imdbRatedMeta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType,
        )

        cachedMetaByRequestKey[requestKey] = cachedMetaByRequestKey[requestKey]
            ?.copy(
                metaScreenMeta = enrichedMeta,
                metaScreenSettingsFingerprint = settingsFingerprint,
            )
            ?: CachedMetaEntry(
                baseMeta = meta,
                metaScreenMeta = enrichedMeta,
                metaScreenSettingsFingerprint = settingsFingerprint,
            )
        stampFetched(requestKey, enrichedMeta)

        return enrichedMeta
    }

    private suspend fun applyMoreLikeThisSource(
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
    ): MetaDetails {
        TrackingSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()

        val trackingSettings = TrackingSettingsRepository.uiState.value
        val isTraktAuthenticated = TraktAuthRepository.uiState.value.mode == TraktConnectionMode.CONNECTED
        val tmdbSettings = TmdbSettingsRepository.snapshot()

        // Simkl source
        if (shouldUseSimklMoreLikeThis(trackingSettings.moreLikeThisSource) &&
            supportsMoreLikeThis(meta, fallbackItemType)
        ) {
            val items = runCatching {
                com.nuvio.app.features.simkl.SimklRelatedRepository.getRelated(
                    meta = meta,
                    fallbackItemId = fallbackItemId,
                    fallbackItemType = fallbackItemType,
                )
            }.onFailure { error ->
                log.w { "Failed to load Simkl related titles for ${meta.id}: ${error.message}" }
            }.getOrDefault(emptyList())

            return meta.copy(
                moreLikeThis = items,
                moreLikeThisSource = MoreLikeThisSource.SIMKL.takeIf { items.isNotEmpty() },
            )
        }

        // Trakt source
        val shouldUseTrakt = shouldUseTraktMoreLikeThis(
            isAuthenticated = isTraktAuthenticated,
            source = trackingSettings.moreLikeThisSource,
        ) && supportsMoreLikeThis(meta, fallbackItemType)

        if (shouldUseTrakt) {
            val page = runCatching {
                TraktRelatedRepository.getRelated(
                    itemId = meta.id,
                    itemType = meta.type,
                    fallbackItemId = fallbackItemId,
                    fallbackItemType = fallbackItemType,
                )
            }.onFailure { error ->
                log.w { "Failed to load Trakt related titles for ${meta.id}: ${error.message}" }
                InAppLogger.warn("Metadata/Trakt", "Failed related titles id=${meta.id} error=${InAppLogger.throwableSummary(error)}")
            }.getOrDefault(MoreLikeThisPage())
            InAppLogger.info("Metadata/Trakt", "Related titles id=${meta.id} count=${page.items.size} hasMore=${page.hasMore}")

            if (page.items.isNotEmpty()) {
                return meta.copy(
                    moreLikeThis = page.items,
                    moreLikeThisSource = MoreLikeThisSource.TRAKT,
                    moreLikeThisHasMore = page.hasMore,
                )
            }
        }

        if (!tmdbSettings.enabled || !tmdbSettings.useMoreLikeThis) {
            return meta.copy(
                moreLikeThis = emptyList(),
                moreLikeThisSource = null,
                moreLikeThisHasMore = false,
            )
        }

        InAppLogger.info(
            "Metadata/TMDB",
            "More Like This fallback id=${meta.id} count=${meta.moreLikeThis.size}",
        )
        return meta.copy(
            moreLikeThisSource = MoreLikeThisSource.TMDB.takeIf { meta.moreLikeThis.isNotEmpty() },
            moreLikeThisHasMore = meta.moreLikeThis.size >= TMDB_RECOMMENDATIONS_PAGE_SIZE,
        )
    }

    private fun shouldFetchMdbListOnMetaScreen(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): Boolean = MdbListMetadataService.shouldFetchForMeta(
        meta = meta,
        fallbackItemId = fallbackItemId,
        settings = settings,
    )

    private fun shouldEnrichForMetaScreen(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): Boolean {
        if (shouldFetchMdbListOnMetaScreen(meta, fallbackItemId, settings)) return true
        TmdbSettingsRepository.ensureLoaded()
        if (OmdbEpisodeRatingsService.shouldApply(meta, TmdbSettingsRepository.snapshot())) return true
        return shouldApplyMoreLikeThisSource(meta)
    }

    private fun shouldApplyMoreLikeThisSource(meta: MetaDetails): Boolean {
        TrackingSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()

        val trackingSettings = TrackingSettingsRepository.uiState.value
        val isTraktAuthenticated = TraktAuthRepository.uiState.value.mode == TraktConnectionMode.CONNECTED
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        return shouldUseTraktMoreLikeThis(
            isAuthenticated = isTraktAuthenticated,
            source = trackingSettings.moreLikeThisSource,
        ) || !tmdbSettings.enabled || !tmdbSettings.useMoreLikeThis || meta.moreLikeThisSource == null && meta.moreLikeThis.isNotEmpty()
    }

    private fun buildMetaScreenSettingsFingerprint(
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): String {
        TrackingSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        val providers = settings.enabledProvidersInPriorityOrder().joinToString(",")
        val trackingSettings = TrackingSettingsRepository.uiState.value
        val traktAuthMode = TraktAuthRepository.uiState.value.mode
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        return buildString {
            append("${settings.enabled}:${settings.apiKey.trim()}:$providers")
            append("|mdblist_account=${settings.accountScope.takeUnless { settings.hasApiKey }}")
            append("|more_like=${trackingSettings.moreLikeThisSource}:$traktAuthMode")
            append("|tmdb=${tmdbSettings.enabled}:${tmdbSettings.useMoreLikeThis}:${tmdbSettings.language}")
            append("|omdb=${OmdbEpisodeRatingsService.hasApiKey}:${tmdbSettings.useEpisodeRatings}")
        }
    }

    private fun supportsMoreLikeThis(meta: MetaDetails, fallbackItemType: String): Boolean =
        normalizeMoreLikeThisType(meta.type) != null || normalizeMoreLikeThisType(fallbackItemType) != null

    private fun shouldUseSimklMoreLikeThis(source: MoreLikeThisSourcePreference): Boolean {
        if (source != MoreLikeThisSourcePreference.SIMKL) return false
        com.nuvio.app.features.simkl.SimklAuthRepository.ensureLoaded()
        return com.nuvio.app.features.simkl.SimklAuthRepository.isAuthenticated.value
    }

    private fun normalizeMoreLikeThisType(value: String?): String? =
        when (value?.trim()?.lowercase()) {
            "movie", "film" -> "movie"
            "series", "show", "tv", "tvshow" -> "series"
            else -> null
        }

    private fun MetaDetails.withUnreleasedFilter(): MetaDetails {
        val posterPattern = com.nuvio.app.core.poster.CustomPosterUrlRepository.let {
            it.ensureLoaded()
            it.patternForScreen(com.nuvio.app.core.poster.CustomPosterScreen.DETAILS)
        }
        val base = withCustomPosterUrls(posterPattern)
        if (!HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) return base
        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val releasedMoreLikeThis = base.moreLikeThis.filterReleasedItems(todayIsoDate)
        return base.copy(
            moreLikeThis = releasedMoreLikeThis,
            moreLikeThisSource = base.moreLikeThisSource.takeIf { releasedMoreLikeThis.isNotEmpty() },
            collectionItems = base.collectionItems.filterReleasedItems(todayIsoDate),
        )
    }

   
    private fun bindProfile() {
        val key = offlineDetailsProfileKey() ?: "none"
        if (boundProfileKey == key) return
        val previous = boundProfileKey
        boundProfileKey = key
        if (previous == null) return
        activeRequestKey = null
        cachedMetaByRequestKey.clear()
        synchronized(savedAtLock) {
            savedAtMsByRequestKey.clear()
            refreshing.clear()
        }
        _uiState.value = MetaDetailsUiState()
    }

    private fun savedAtMs(requestKey: String): Long = synchronized(savedAtLock) {
        savedAtMsByRequestKey[requestKey] ?: 0L
    }

    private fun localOfflineMeta(type: String, id: String, offlineTitle: String?): MetaDetails? {
        downloadedMetaFor(type, id)?.let { return it }
        val title = offlineTitle?.trim().orEmpty()
        if (title.isEmpty()) return null
        return MetaDetails(id = id, type = type, name = title)
    }

    private fun rememberDisk(requestKey: String, hit: OpenedTitleHit) {
        cachedMetaByRequestKey[requestKey] = CachedMetaEntry(baseMeta = hit.meta)
        synchronized(savedAtLock) { savedAtMsByRequestKey[requestKey] = hit.savedAtMs }
    }

    private suspend fun readDiskTitle(requestKey: String): OpenedTitleHit? {
        val profileKey = offlineDetailsProfileKey() ?: return null
        return OpenedTitleDisk.read(profileKey, requestKey)
    }

    private fun stampFetched(requestKey: String, meta: MetaDetails) {
        val savedAtMs = Clock.System.now().toEpochMilliseconds()
        synchronized(savedAtLock) { savedAtMsByRequestKey[requestKey] = savedAtMs }
        val profileKey = offlineDetailsProfileKey() ?: return
        scope.launch(Dispatchers.Default) {
            OpenedTitleDisk.save(profileKey, requestKey, meta, savedAtMs)
        }
    }

    private fun publishUnavailable(requestKey: String, message: String, keepVisibleOnFailure: Boolean) {
        if (keepVisibleOnFailure && _uiState.value.meta != null) {
            _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = null)
            activeRequestKey = requestKey
            return
        }
        _uiState.value = MetaDetailsUiState(errorMessage = message)
        activeRequestKey = null
    }

    private fun refreshIfStale(
        type: String,
        id: String,
        requestKey: String,
        updateUi: Boolean,
    ) {
        val savedAtMs = savedAtMs(requestKey)
        if (!offlineStampIsFresh(savedAtMs, Clock.System.now().toEpochMilliseconds(), OfflineDetailsCacheLimits.MetaTtlMs) &&
            savedAtMs > 0L
        ) {
            val decision = offlineCacheDecision(OfflineCacheLookup.DiskStale, currentOfflineDetailsNetwork())
            if (!decision.refreshRequested) return
        } else {
            return
        }
        val started = synchronized(savedAtLock) { refreshing.add(requestKey) }
        if (!started) return
        scope.launch {
            try {
                val fetched = fetchReporting(type, id, cacheResult = true, useCache = false)
                val meta = fetched.meta ?: return@launch
                if (!updateUi || activeRequestKey != requestKey) return@launch
                val settings = MdbListSettingsRepository.snapshot()
                publishLoadedMeta(
                    requestKey = requestKey,
                    meta = meta,
                    fallbackItemId = id,
                    fallbackItemType = type,
                    mdbListSettings = settings,
                    metaScreenSettingsFingerprint = buildMetaScreenSettingsFingerprint(settings),
                )
            } finally {
                synchronized(savedAtLock) { refreshing.remove(requestKey) }
            }
        }
    }

    fun findEmbeddedStreams(videoId: String): List<com.nuvio.app.features.streams.StreamItem> {
        val meta = _uiState.value.meta ?: return emptyList()
        val videosWithStreams = meta.videos.filter { it.streams.isNotEmpty() }
        if (videosWithStreams.isEmpty()) return emptyList()

        val directMatch = videosWithStreams.firstOrNull { it.id == videoId }
        if (directMatch != null) return directMatch.streams

        val parts = videoId.split(":")
        if (parts.size >= 3) {
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()
            if (season != null && episode != null) {
                val episodeMatch = videosWithStreams.firstOrNull { it.season == season && it.episode == episode }
                if (episodeMatch != null) return episodeMatch.streams
            }
        }

        val prefixMatch = videosWithStreams.firstOrNull { it.id.startsWith("$videoId:") }
        if (prefixMatch != null) return prefixMatch.streams

        if (videoId == meta.id && videosWithStreams.size == 1) {
            return videosWithStreams.first().streams
        }

        if (videoId == meta.id && videosWithStreams.isNotEmpty()) {
            return videosWithStreams.flatMap { it.streams }
        }

        return emptyList()
    }
}

internal sealed interface MetaLookupOutcome {
    class Loaded(val meta: MetaDetails) : MetaLookupOutcome

    class Throttled(val retryAtEpochMs: Long) : MetaLookupOutcome

    object Unavailable : MetaLookupOutcome

    object Failed : MetaLookupOutcome
}
