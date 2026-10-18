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
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
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

/** Device-level settings for automatic downloads (smart downloads, queued and goal episodes, autopilot). */
object SmartDownloadSettings {
    private const val ENABLED_KEY = "smart_downloads_enabled"
    private const val COUNT_KEY = "smart_downloads_count"
    private const val CHARGING_KEY = "smart_downloads_only_charging"
    private const val WIFI_KEY = "smart_downloads_only_wifi"
    private const val QUALITY_KEY = "download_quality_preference"
    private const val PAUSE_DURING_PLAYBACK_KEY = "downloads_pause_during_playback"
    private const val QUEUE_UNAIRED_KEY = "downloads_queue_unaired"
    private const val KEEP_FREE_GB_KEY = "downloads_keep_free_gb"
    private const val GOALS_KEY = "smart_downloads_goals"

    /** Choices for "Keep free" (GB); 0 is off. */
    val KEEP_FREE_CHOICES_GB = listOf(0, 5, 10, 20, 50)

    data class State(
        val enabled: Boolean = false,
        val count: Int = 1,
        val onlyWhileCharging: Boolean = false,
        val onlyOnWifi: Boolean = true,
        val quality: DownloadQualityPreference = DownloadQualityPreference.Best,
        /** Pause every download while the player is open (instead of the usual one-at-a-time throttle). */
        val pauseDuringPlayback: Boolean = false,
        /** Unaired episodes can be queued and download once they air. */
        val queueUnaired: Boolean = true,
        /** Storage autopilot: free watched downloads when free space drops below this (0 = off). */
        val keepFreeGb: Int = 0,
        /** Shows pinned as a Finish a series goal ("type|id"): their next episodes stay downloaded. */
        val goals: Set<String> = emptySet(),
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
            onlyOnWifi = DownloadsSettingsStorage.loadFlag(WIFI_KEY) ?: true,
            quality = DownloadQualityPreference.fromKey(DownloadsSettingsStorage.loadString(QUALITY_KEY)),
            pauseDuringPlayback = DownloadsSettingsStorage.loadFlag(PAUSE_DURING_PLAYBACK_KEY) ?: false,
            queueUnaired = DownloadsSettingsStorage.loadFlag(QUEUE_UNAIRED_KEY) ?: true,
            keepFreeGb = DownloadsSettingsStorage.loadString(KEEP_FREE_GB_KEY)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            goals = DownloadsSettingsStorage.loadString(GOALS_KEY).orEmpty().split('\n').filter { it.isNotBlank() }.toSet(),
        )
    }

    private fun update(transform: (State) -> State) {
        ensureLoaded()
        _state.value = transform(_state.value)
    }

    fun snapshot(): State {
        ensureLoaded()
        return _state.value
    }

    fun setEnabled(enabled: Boolean) {
        update { it.copy(enabled = enabled) }
        DownloadsSettingsStorage.saveFlag(ENABLED_KEY, enabled)
        if (enabled) SmartDownloads.retryPending()
    }

    fun setCount(count: Int) {
        val value = count.coerceIn(1, 3)
        update { it.copy(count = value) }
        DownloadsSettingsStorage.saveString(COUNT_KEY, value.toString())
    }

    fun setOnlyWhileCharging(enabled: Boolean) {
        update { it.copy(onlyWhileCharging = enabled) }
        DownloadsSettingsStorage.saveFlag(CHARGING_KEY, enabled)
        if (!enabled) SmartDownloads.retryPending()
    }

    fun setOnlyOnWifi(enabled: Boolean) {
        update { it.copy(onlyOnWifi = enabled) }
        DownloadsSettingsStorage.saveFlag(WIFI_KEY, enabled)
        if (!enabled) SmartDownloads.retryPending()
    }

    fun setPauseDuringPlayback(enabled: Boolean) {
        update { it.copy(pauseDuringPlayback = enabled) }
        DownloadsSettingsStorage.saveFlag(PAUSE_DURING_PLAYBACK_KEY, enabled)
        PlaybackPriority.reapplyDownloadCap()
    }

    fun setQueueUnaired(enabled: Boolean) {
        update { it.copy(queueUnaired = enabled) }
        DownloadsSettingsStorage.saveFlag(QUEUE_UNAIRED_KEY, enabled)
    }

    fun setKeepFreeGb(gb: Int) {
        update { it.copy(keepFreeGb = gb.coerceAtLeast(0)) }
        DownloadsSettingsStorage.saveString(KEEP_FREE_GB_KEY, gb.coerceAtLeast(0).toString())
        StorageAutopilot.run()
    }

    fun isGoal(type: String, id: String): Boolean = goalKey(type, id) in snapshot().goals

    fun setGoal(type: String, id: String, pinned: Boolean) {
        val key = goalKey(type, id)
        update { it.copy(goals = if (pinned) it.goals + key else it.goals - key) }
        DownloadsSettingsStorage.saveString(GOALS_KEY, _state.value.goals.joinToString("\n").ifEmpty { null })
        if (pinned) SmartDownloads.checkGoal(type, id)
    }

    private fun goalKey(type: String, id: String) = "$type|$id"

    fun setQuality(quality: DownloadQualityPreference) {
        update { it.copy(quality = quality) }
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
    /** Exactly this episode (one queued before it aired), not the ones after it. */
    val exact: Boolean = false,
    /** Episodes after S/E to keep downloaded; null uses the Smart Downloads count. */
    val count: Int? = null,
    /** Nothing to do before this time (the next episode airs then). */
    val notBeforeEpochMs: Long = 0L,
) {
    fun sameTarget(other: SmartDownloadRequest): Boolean =
        parentMetaId == other.parentMetaId && exact == other.exact && count == other.count &&
            (!exact || (season == other.season && episode == other.episode))
}

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
        retryPending()
        SmartDownloadSettings.snapshot().goals.forEach { key ->
            val (type, id) = key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (id.isNotBlank()) checkGoal(type, id)
        }
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
        val season = entry.seasonNumber ?: return
        val episode = entry.episodeNumber ?: return
        if (!handledVideoIds.add(entry.videoId)) return
        if (SmartDownloadSettings.isGoal(entry.parentMetaType, entry.parentMetaId)) {
            // The goal counts from the next episode on, as if this one were watched.
            add(SmartDownloadRequest(entry.parentMetaId, entry.parentMetaType, season, episode, count = GOAL_EPISODES))
        }
        if (!isEnabled()) return
        val hasDownloadsOfShow = DownloadsRepository.uiState.value.items.any { it.parentMetaId == entry.parentMetaId }
        if (!hasDownloadsOfShow) return
        add(SmartDownloadRequest(entry.parentMetaId, entry.parentMetaType, season, episode))
    }

    /** "Queue" on an episode that has not aired yet: it downloads once it airs and a source is found. */
    fun queueUnaired(metaType: String, metaId: String, season: Int, episode: Int) {
        add(SmartDownloadRequest(metaId, metaType, season, episode, exact = true))
        NuvioToastController.show("Queued S${season}E$episode: it downloads once it airs")
    }

    /**
     * Finish a series goal: keeps the [GOAL_EPISODES] episodes from the first unwatched one
     * downloaded. Checked at launch, when pinned and after each watched episode; a finished show
     * is unpinned.
     */
    fun checkGoal(type: String, id: String) {
        scope.launch {
            val meta = MetaDetailsRepository.fetch(type, id) ?: return@launch
            val first = firstUnwatchedEpisode(meta)
            if (first == null) {
                if (meta.videos.none { it.season != null && it.season > 0 && !it.hasAiredForDownload() }) {
                    SmartDownloadSettings.setGoal(type, id, pinned = false)
                    NuvioToastController.show("Finished ${meta.name}: goal done")
                }
                return@launch
            }
            add(SmartDownloadRequest(id, type, first.season ?: return@launch, (first.episode ?: return@launch) - 1, count = GOAL_EPISODES))
        }
    }

    private fun firstUnwatchedEpisode(meta: MetaDetails): MetaVideo? =
        nextEpisodes(meta.videos, season = 0, episode = 0, count = Int.MAX_VALUE)
            .filter { it.hasAiredForDownload() }
            .firstOrNull { video ->
                !com.nuvio.app.features.watched.WatchedRepository.isWatched(
                    id = meta.id,
                    type = meta.type,
                    season = video.season,
                    episode = video.episode,
                )
            }

    private fun add(request: SmartDownloadRequest) {
        scope.launch {
            mutex.withLock { savePending(loadPending().filterNot { it.sameTarget(request) } + request) }
            runPending()
        }
    }

    fun retryPending() {
        scope.launch { runPending() }
    }

    private fun canRunNow(): Boolean {
        val settings = SmartDownloadSettings.snapshot()
        if (settings.onlyOnWifi && !DownloadNetworkGuard.isOnWifi()) return false
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
            val left = pending.mapNotNull { request -> runCatching { fulfil(request) }.getOrDefault(request) }
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

    /** The request to keep for later (maybe with a later start), or null when it is done. */
    private suspend fun fulfil(request: SmartDownloadRequest): SmartDownloadRequest? {
        val settings = SmartDownloadSettings.snapshot()
        // Plain smart downloads follow the switch; queued and goal episodes were asked for directly.
        if (!settings.enabled && !request.exact && request.count == null) return null
        if (request.notBeforeEpochMs > EpisodeReleaseDatePlatform.nowEpochMs()) return request
        DownloadsRepository.ensureLoaded()
        val meta = MetaDetailsRepository.fetch(request.parentMetaType, request.parentMetaId) ?: return request
        val wanted = if (request.exact) {
            meta.videos.filter { it.season == request.season && it.episode == request.episode }.take(1)
        } else {
            nextEpisodes(meta.videos, request.season, request.episode, request.count ?: settings.count)
        }
        val (next, upcoming) = wanted.partition { it.hasAiredForDownload() }
        // Not aired yet: wait for its air date (a day when it has none), then try again.
        val later = upcoming.takeIf { settings.queueUnaired && it.isNotEmpty() }
            ?.let { request.copy(notBeforeEpochMs = nextAirCheckEpochMs(it)) }
        if (next.isEmpty()) return later
        val items = DownloadsRepository.uiState.value.items
        val missing = next.filter { video ->
            val key = downloadLogicalContentKey(meta.id, video.season, video.episode)
            seasonExistingDownload(items.firstOrNull { it.logicalContentKey == key }?.status) == null
        }
        if (missing.isEmpty()) return later
        // Disk nearly full: don't ask the addons for streams that could not be saved anyway.
        val free = DownloadDiskInfo.volumeSpace()?.availableBytes
        if (free != null && free < SMART_DOWNLOAD_MIN_FREE_BYTES) return noSpace(request)
        val reference = items
            .filter { it.parentMetaId == meta.id && it.isEpisode }
            .maxByOrNull { it.updatedAtEpochMs }
        val picked = missing.mapNotNull { video ->
            pickStream(meta, video, reference)?.let { SeasonEnqueueRow(video, it) }
        }
        // No source yet (often right after it airs): look again in an hour.
        if (picked.isEmpty()) return request.copy(notBeforeEpochMs = EpisodeReleaseDatePlatform.nowEpochMs() + HOUR_MS)
        // Only what fits: each file plus its reserve, after what running downloads still need.
        val fit = smartDownloadsThatFit(
            rows = picked,
            sizeOf = { row -> downloadStreamBytes(row.stream) },
            fallbackBytes = typicalEpisodeBytes(meta.id, items),
            freeBytes = DownloadDiskInfo.volumeSpace()?.availableBytes,
            inProgressRemainingBytes = items
                .filter { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }
                .sumOf { item -> item.totalBytes?.takeIf { it > 0L }?.let { (it - item.downloadedBytes).coerceAtLeast(0L) } ?: 0L },
        )
        if (fit.isEmpty()) return noSpace(request)
        val result = enqueueSeasonDownloads(meta, fit)
        if (result.queued > 0) {
            NuvioToastController.show(smartDownloadsQueuedMessage(result.queued, meta.name))
        }
        // Kept pending while some episodes did not fit, so it runs again once space frees.
        return if (fit.size == missing.size && picked.size == missing.size) later else request
    }

    private val noSpaceShown = mutableSetOf<String>()

    /**
     * Nothing fits: kept pending (a delete or freed space runs it again). The watched file that is
     * about to go is waited for; otherwise the autopilot may free room and the user hears it once.
     */
    private fun noSpace(request: SmartDownloadRequest): SmartDownloadRequest {
        if (FinishedDownloadDeletion.hasPendingDeletion()) return request
        StorageAutopilot.run()
        val key = "${request.parentMetaId}:${request.season}:${request.episode}"
        if (noSpaceShown.add(key)) NuvioToastController.show(SMART_DOWNLOADS_NO_SPACE)
        return request
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

private const val GOAL_EPISODES = 2
private const val HOUR_MS = 60 * 60_000L
private const val DAY_MS = 24 * HOUR_MS

/** Below this much free space Smart Downloads don't even look for streams. */
private const val SMART_DOWNLOAD_MIN_FREE_BYTES = 1_000_000_000L

/** When to look again for queued unaired episodes: the next air date, else tomorrow. */
internal fun nextAirCheckEpochMs(upcoming: List<MetaVideo>, now: Long = EpisodeReleaseDatePlatform.nowEpochMs()): Long {
    return upcoming.mapNotNull { com.nuvio.app.core.time.parseEpisodeReleaseEpochMs(it.released) }
        .filter { it > now }
        .minOrNull() ?: (now + DAY_MS)
}

internal const val SMART_DOWNLOADS_NO_SPACE = "No space for the next episode"

/**
 * The leading [rows] (in episode order) that fit in [freeBytes]: each needs its size plus the usual
 * reserve, after [inProgressRemainingBytes] that running and queued downloads still need. A row
 * without a size uses [fallbackBytes] (a typical episode of the show), or a 2 GB guess. Unknown free
 * space lets everything through (the running download checks again).
 */
internal fun <T> smartDownloadsThatFit(
    rows: List<T>,
    sizeOf: (T) -> Long?,
    fallbackBytes: Long?,
    freeBytes: Long?,
    inProgressRemainingBytes: Long,
): List<T> {
    if (freeBytes == null) return rows
    var budget = freeBytes - inProgressRemainingBytes.coerceAtLeast(0L)
    val out = mutableListOf<T>()
    for (row in rows) {
        val size = sizeOf(row)?.takeIf { it > 0L } ?: fallbackBytes ?: GUESSED_EPISODE_BYTES
        val needed = size + downloadSpaceReserve(size)
        if (needed > budget) break
        out += row
        budget -= size
    }
    return out
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
