package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.analysis.MediaAnalyzerPlatform
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Preferred quality for downloads the app picks on its own (smart downloads, season auto-pick). */
enum class DownloadQualityPreference(val key: String, val maxHeight: Int?) {
    Best("best", null),
    P2160("2160", 2160),
    P1080("1080", 1080),
    P720("720", 720),
    P480("480", 480),
    ;

    companion object {
        fun fromKey(key: String?): DownloadQualityPreference = entries.firstOrNull { it.key == key } ?: Best
    }
}

/** Device-level settings for smart downloads and the download quality preference. */
object SmartDownloadSettings {
    private const val ENABLED_KEY = "smart_downloads_enabled"
    private const val COUNT_KEY = "smart_downloads_count"
    private const val CHARGING_KEY = "smart_downloads_only_charging"
    private const val QUALITY_KEY = "download_quality_preference"

    data class State(
        val enabled: Boolean = false,
        val count: Int = 1,
        val onlyWhileCharging: Boolean = false,
        val quality: DownloadQualityPreference = DownloadQualityPreference.Best,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _state.value = State(
            enabled = DownloadsSettingsStorage.loadFlag(ENABLED_KEY) ?: false,
            count = DownloadsSettingsStorage.loadString(COUNT_KEY)?.toIntOrNull()?.coerceIn(1, 3) ?: 1,
            onlyWhileCharging = DownloadsSettingsStorage.loadFlag(CHARGING_KEY) ?: false,
            quality = DownloadQualityPreference.fromKey(DownloadsSettingsStorage.loadString(QUALITY_KEY)),
        )
    }

    fun snapshot(): State {
        ensureLoaded()
        return _state.value
    }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        _state.value = _state.value.copy(enabled = enabled)
        DownloadsSettingsStorage.saveFlag(ENABLED_KEY, enabled)
        if (enabled) SmartDownloads.retryPending()
    }

    fun setCount(count: Int) {
        ensureLoaded()
        val value = count.coerceIn(1, 3)
        _state.value = _state.value.copy(count = value)
        DownloadsSettingsStorage.saveString(COUNT_KEY, value.toString())
    }

    fun setOnlyWhileCharging(enabled: Boolean) {
        ensureLoaded()
        _state.value = _state.value.copy(onlyWhileCharging = enabled)
        DownloadsSettingsStorage.saveFlag(CHARGING_KEY, enabled)
    }

    fun setQuality(quality: DownloadQualityPreference) {
        ensureLoaded()
        _state.value = _state.value.copy(quality = quality)
        DownloadsSettingsStorage.saveString(QUALITY_KEY, quality.key)
    }
}

/** Height in lines from a quality label such as "1080p" or "4k"; null when unknown. */
internal fun qualityHeight(label: String): Int? {
    val lower = label.lowercase()
    if (lower == "4k") return 2160
    if (lower == "8k") return 4320
    return lower.removeSuffix("p").toIntOrNull()
}

/**
 * Streams ordered for a quality preference: streams at or below the cap first (highest of those
 * first, keeping the input order inside one height), then the rest. "Best" keeps the input order.
 */
internal fun List<StreamItem>.orderedForQuality(preference: DownloadQualityPreference): List<StreamItem> {
    val cap = preference.maxHeight ?: return this
    val withHeight = mapIndexed { index, stream -> Triple(index, stream, qualityHeight(streamQualityLabel(stream))) }
    val fitting = withHeight.filter { (_, _, h) -> h != null && h <= cap }
        .sortedWith(compareByDescending<Triple<Int, StreamItem, Int?>> { it.third }.thenBy { it.first })
    val unknown = withHeight.filter { it.third == null }
    val above = withHeight.filter { (_, _, h) -> h != null && h > cap }.sortedBy { it.third }
    return (fitting + unknown + above).map { it.second }
}

@Serializable
private data class SmartDownloadRequest(
    val parentMetaId: String,
    val parentMetaType: String,
    val season: Int,
    val episode: Int,
)

/**
 * Smart downloads: when an episode of a show you have downloads of is finished, the next
 * [SmartDownloadSettings.State.count] episodes are downloaded with the player's auto-play source rules
 * (preferring the same source and quality as the episode just watched). Deleting the watched file is
 * left to [FinishedDownloadDeletion], which smart downloads switch on, so the same safety applies
 * (never the episode on screen, never a running transfer).
 *
 * Downloads start only on Wi-Fi (and optionally only while charging). A request that cannot run yet
 * is kept and retried every few minutes and at the next launch.
 */
internal object SmartDownloads {
    private const val PENDING_KEY = "smart_downloads_pending"
    private const val RETRY_MS = 5 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val handledVideoIds = mutableSetOf<String>()
    private var retryJob: Job? = null

    fun isEnabled(): Boolean = SmartDownloadSettings.snapshot().enabled

    fun appDidLaunch() {
        if (!isEnabled()) return
        retryPending()
    }

    /** Credits start the player knows for an episode (own mark > TheIntroDB > on-device). */
    private val liveCreditsStartMs = mutableMapOf<String, Long>()

    fun reportCredits(videoId: String, creditsStartMs: Long?) {
        if (creditsStartMs == null) liveCreditsStartMs.remove(videoId) else liveCreditsStartMs[videoId] = creditsStartMs
    }

    /**
     * Every progress report while watching: starts the next episodes early, about 3 minutes before
     * the credits (or 8 minutes before the end / 85 %, whichever is first), or at half-way when the
     * episode is large. Once per episode; downloads run throttled while playback is on screen.
     */
    fun onProgress(entry: WatchProgressEntry, positionMs: Long, durationMs: Long, analysedCreditsStartMs: Long?) {
        if (!isEnabled()) return
        if (entry.seasonNumber == null || entry.episodeNumber == null) return
        if (entry.videoId in handledVideoIds) return
        val credits = liveCreditsStartMs[entry.videoId] ?: analysedCreditsStartMs
        val showDownloads = DownloadsRepository.uiState.value.items.filter { it.parentMetaId == entry.parentMetaId }
        if (showDownloads.isEmpty()) return
        val large = showDownloads.maxByOrNull { it.updatedAtEpochMs }
            ?.totalBytes?.let { it >= PlaybackEndRules.SMART_LARGE_EPISODE_BYTES } == true
        val at = PlaybackEndRules.smartDownloadTriggerMs(durationMs, credits, large) ?: return
        if (positionMs >= at) onNearEnd(entry)
    }

    /** The episode on screen is near its end (credits or last minutes, see PlaybackEndRules). */
    fun onNearEnd(entry: WatchProgressEntry) {
        if (!isEnabled()) return
        val season = entry.seasonNumber ?: return
        val episode = entry.episodeNumber ?: return
        if (!handledVideoIds.add(entry.videoId)) return
        val hasDownloadsOfShow = DownloadsRepository.uiState.value.items.any { it.parentMetaId == entry.parentMetaId }
        if (!hasDownloadsOfShow) return
        val request = SmartDownloadRequest(entry.parentMetaId, entry.parentMetaType, season, episode)
        scope.launch {
            mutex.withLock { savePending(loadPending().filterNot { it.parentMetaId == request.parentMetaId } + request) }
            runPending()
        }
    }

    fun retryPending() {
        scope.launch { runPending() }
    }

    private fun canRunNow(): Boolean {
        val settings = SmartDownloadSettings.snapshot()
        if (!settings.enabled) return false
        if (!DownloadNetworkGuard.isOnWifi()) return false
        if (settings.onlyWhileCharging && !MediaAnalyzerPlatform.isCharging()) return false
        return true
    }

    private suspend fun runPending() {
        mutex.withLock {
            val pending = loadPending()
            if (pending.isEmpty()) return
            if (!canRunNow()) {
                scheduleRetry()
                return
            }
            val left = mutableListOf<SmartDownloadRequest>()
            for (request in pending) {
                val done = runCatching { fulfil(request) }.getOrElse { false }
                if (!done) left += request
            }
            savePending(left)
            if (left.isNotEmpty()) scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(RETRY_MS)
            runPending()
        }
    }

    /** True when nothing more is to be done for [request]. */
    private suspend fun fulfil(request: SmartDownloadRequest): Boolean {
        DownloadsRepository.ensureLoaded()
        val meta = MetaDetailsRepository.fetch(request.parentMetaType, request.parentMetaId) ?: return false
        val count = SmartDownloadSettings.snapshot().count
        val next = nextEpisodes(meta.videos, request.season, request.episode, count)
            .filter { it.hasAiredForDownload() }
        if (next.isEmpty()) return true
        val items = DownloadsRepository.uiState.value.items
        val missing = next.filter { video ->
            val key = downloadLogicalContentKey(meta.id, video.season, video.episode)
            seasonExistingDownload(items.firstOrNull { it.logicalContentKey == key }?.status) == null
        }
        if (missing.isEmpty()) return true
        val reference = items
            .filter { it.parentMetaId == meta.id && it.isEpisode }
            .maxByOrNull { it.updatedAtEpochMs }
        val rows = missing.mapNotNull { video ->
            pickStream(meta, video, reference)?.let { SeasonEnqueueRow(video, it) }
        }
        if (rows.isEmpty()) return false
        val result = enqueueSeasonDownloads(meta, rows)
        if (result.queued > 0) {
            NuvioToastController.show(smartDownloadsQueuedMessage(result.queued, meta.name))
        }
        return rows.size == missing.size
    }

    private suspend fun pickStream(meta: MetaDetails, video: MetaVideo, reference: DownloadItem?): StreamItem? {
        val videoId = seasonStreamVideoId(meta.id, video)
        val quality = SmartDownloadSettings.snapshot().quality
        // Same source and quality as the episode already downloaded, when that addon has it.
        if (reference?.providerAddonId != null) {
            val choice = SeasonSourceChoice(
                addonId = reference.providerAddonId,
                qualityLabel = reference.sourceQualityLabel ?: "",
                bingeGroup = reference.sourceBingeGroup,
            )
            val streams = SeasonStreamCatalog.loadFrom(reference.providerAddonId, meta.type, videoId, video.season, video.episode)
            val match = matchSeasonStream(choice, streams.orderedForQuality(quality))
            if (match is SeasonStreamMatch.Matched) return match.stream
        }
        val streams = SeasonStreamCatalog.load(meta.type, videoId, video.season, video.episode, annotateDebridCache = true)
        return autoPickSeasonStream(streams.orderedForQuality(quality), currentAutoPickSettings(meta.id), installedAddonNames())
    }

    private fun loadPending(): List<SmartDownloadRequest> =
        DownloadsSettingsStorage.loadString(PENDING_KEY)
            ?.let { runCatching { json.decodeFromString(ListSerializer(SmartDownloadRequest.serializer()), it) }.getOrNull() }
            .orEmpty()

    private fun savePending(list: List<SmartDownloadRequest>) {
        DownloadsSettingsStorage.saveString(
            PENDING_KEY,
            if (list.isEmpty()) null else json.encodeToString(ListSerializer(SmartDownloadRequest.serializer()), list),
        )
    }
}

/** The next [count] regular episodes after S[season]E[episode], in order, specials left out. */
internal fun nextEpisodes(videos: List<MetaVideo>, season: Int, episode: Int, count: Int): List<MetaVideo> =
    videos
        .filter { (it.season ?: 0) > 0 && it.episode != null }
        .sortedWith(compareBy({ it.season }, { it.episode }))
        .filter { v -> (v.season!! > season) || (v.season == season && v.episode!! > episode) }
        .distinctBy { it.season to it.episode }
        .take(count)

internal fun currentAutoPickSettings(metaId: String): SeasonAutoPickSettings {
    PlayerSettingsRepository.ensureLoaded()
    val player = PlayerSettingsRepository.uiState.value
    val debrid = DebridSettingsRepository.snapshot()
    return SeasonAutoPickSettings(
        mode = player.streamAutoPlayMode,
        regexPattern = player.streamAutoPlayRegex,
        source = player.streamAutoPlaySource,
        selectedAddons = player.streamAutoPlaySelectedAddons,
        selectedPlugins = player.streamAutoPlaySelectedPlugins,
        preferredBingeGroup = if (player.streamAutoPlayPreferBingeGroup && player.streamAutoPlayReuseBingeGroup) {
            BingeGroupCacheRepository.get(metaId)
        } else {
            null
        },
        timeoutSeconds = player.streamAutoPlayTimeoutSeconds,
        debridEnabled = debrid.canResolvePlayableLinks,
        activeResolverProviderId = debrid.activeResolverProviderId,
    )
}

internal fun installedAddonNames(): Set<String> =
    AddonRepository.uiState.value.addons.enabledAddons()
        .mapNotNull { addon -> addon.manifest?.let { addon.displayTitle.ifBlank { it.name } } }
        .toSet()

private fun smartDownloadsQueuedMessage(count: Int, title: String): String =
    if (count == 1) "Smart downloads: next episode of $title is downloading"
    else "Smart downloads: next $count episodes of $title are downloading"
