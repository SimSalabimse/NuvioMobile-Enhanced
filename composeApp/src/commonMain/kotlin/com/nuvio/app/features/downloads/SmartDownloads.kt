package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.analysis.MediaAnalysisStore
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
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.isWatchProgressComplete
import com.nuvio.app.features.watchprogress.resolvedProgressKey
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import com.nuvio.app.core.serialization.LenientJson

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
    private val ENABLED = FlagSetting("smart_downloads_enabled", default = false)
    private val COUNT = IntSetting("smart_downloads_count", default = 1) { it.coerceIn(1, 3) }
    private val CHARGING = FlagSetting("smart_downloads_only_charging", default = false)
    private val WIFI = FlagSetting("smart_downloads_only_wifi", default = true)
    private const val QUALITY_KEY = "download_quality_preference"
    private val PAUSE_DURING_PLAYBACK = FlagSetting("downloads_pause_during_playback", default = false)
    private val QUEUE_UNAIRED = FlagSetting("downloads_queue_unaired", default = true)
    private val KEEP_FREE_GB = IntSetting("downloads_keep_free_gb", default = 0) { it.coerceAtLeast(0) }
    private const val GOALS_KEY = "smart_downloads_goals"
    private val GOAL_DOWNLOADS_REST = FlagSetting("smart_downloads_goal_rest", default = true)
    private const val GOAL_ANCHORS_KEY = "smart_downloads_goal_anchors"

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
        /** Finish a series downloads every remaining episode (off: only the Smart Downloads count). */
        val goalDownloadsRest: Boolean = true,
        /** Per goal, the furthest episode counted as watched (season to episode); it never moves back. */
        val goalAnchors: Map<String, Pair<Int, Int>> = emptyMap(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _state.value = State(
            enabled = ENABLED.load(),
            count = COUNT.load(),
            onlyWhileCharging = CHARGING.load(),
            onlyOnWifi = WIFI.load(),
            quality = DownloadQualityPreference.fromKey(DownloadsSettingsStorage.loadString(QUALITY_KEY)),
            pauseDuringPlayback = PAUSE_DURING_PLAYBACK.load(),
            queueUnaired = QUEUE_UNAIRED.load(),
            keepFreeGb = KEEP_FREE_GB.load(),
            goalDownloadsRest = GOAL_DOWNLOADS_REST.load(),
            goals = DownloadsSettingsStorage.loadString(GOALS_KEY).orEmpty().split('\n').filter { it.isNotBlank() }.toSet(),
            goalAnchors = DownloadsSettingsStorage.loadString(GOAL_ANCHORS_KEY).orEmpty().split('\n').mapNotNull { line ->
                val key = line.substringBeforeLast('=', "")
                val season = line.substringAfterLast('=').substringBefore(':').toIntOrNull()
                val episode = line.substringAfterLast(':').toIntOrNull()
                if (key.isBlank() || season == null || episode == null) null else key to (season to episode)
            }.toMap(),
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
        ENABLED.save(enabled)
        if (enabled) SmartDownloads.retryPending()
        recheckGoals()
    }

    fun setCount(count: Int) {
        val value = count.coerceIn(1, 3)
        update { it.copy(count = value) }
        COUNT.save(value)
        recheckGoals()
    }

    fun setOnlyWhileCharging(enabled: Boolean) {
        update { it.copy(onlyWhileCharging = enabled) }
        CHARGING.save(enabled)
        if (!enabled) SmartDownloads.retryPending()
    }

    fun setOnlyOnWifi(enabled: Boolean) {
        update { it.copy(onlyOnWifi = enabled) }
        WIFI.save(enabled)
        if (!enabled) SmartDownloads.retryPending()
    }

    fun setPauseDuringPlayback(enabled: Boolean) {
        update { it.copy(pauseDuringPlayback = enabled) }
        PAUSE_DURING_PLAYBACK.save(enabled)
        PlaybackPriority.reapplyDownloadCap()
    }

    fun setQueueUnaired(enabled: Boolean) {
        update { it.copy(queueUnaired = enabled) }
        QUEUE_UNAIRED.save(enabled)
    }

    fun setKeepFreeGb(gb: Int) {
        update { it.copy(keepFreeGb = gb.coerceAtLeast(0)) }
        KEEP_FREE_GB.save(gb.coerceAtLeast(0))
        StorageAutopilot.run()
    }

    /**
     * Episodes a Finish a series goal keeps downloaded (lowest first, topped up as you watch):
     * with Smart downloads on, exactly its "Episodes to keep ready"; with it off, every remaining
     * episode when "Finish a series downloads the rest" is on, else the same count.
     */
    fun goalCount(): Int = goalCount(snapshot())

    internal fun goalCount(state: State): Int =
        if (!state.enabled && state.goalDownloadsRest) Int.MAX_VALUE else state.count.coerceAtLeast(1)

    fun setGoalDownloadsRest(enabled: Boolean) {
        update { it.copy(goalDownloadsRest = enabled) }
        GOAL_DOWNLOADS_REST.save(enabled)
        recheckGoals()
    }

    /** The goal count changed: every pinned show tops up (or stops) to the new count. */
    private fun recheckGoals() {
        snapshot().goals.forEach { key -> SmartDownloads.checkGoal(key.substringBefore('|'), key.substringAfter('|')) }
    }

    fun isGoal(type: String, id: String): Boolean = isGoal(snapshot(), type, id)

    fun isGoal(state: State, type: String, id: String): Boolean = goalKey(type, id) in state.goals

    fun setGoal(type: String, id: String, pinned: Boolean) {
        val key = goalKey(type, id)
        update { it.copy(goals = if (pinned) it.goals + key else it.goals - key) }
        DownloadsSettingsStorage.saveString(GOALS_KEY, _state.value.goals.joinToString("\n").ifEmpty { null })
        if (!pinned) saveAnchors(_state.value.goalAnchors - key)
        if (pinned) SmartDownloads.checkGoal(type, id, announce = true) else SmartDownloads.dropGoal(id)
    }

    private fun goalKey(type: String, id: String) = "$type|$id"

    /** The furthest episode the goal counts as watched, or null before its first check. */
    fun goalAnchor(type: String, id: String): Pair<Int, Int>? = snapshot().goalAnchors[goalKey(type, id)]

    /** Moves the goal's anchor forward to [season]/[episode]; an earlier episode leaves it alone. */
    fun advanceGoalAnchor(type: String, id: String, season: Int, episode: Int) {
        val key = goalKey(type, id)
        if (key !in snapshot().goals) return
        val current = goalAnchor(type, id)
        if (laterEpisode(current, season to episode) == current) return
        saveAnchors(_state.value.goalAnchors + (key to (season to episode)))
    }

    private fun saveAnchors(anchors: Map<String, Pair<Int, Int>>) {
        update { it.copy(goalAnchors = anchors) }
        DownloadsSettingsStorage.saveString(
            GOAL_ANCHORS_KEY,
            anchors.entries.joinToString("\n") { (key, at) -> "$key=${at.first}:${at.second}" }.ifEmpty { null },
        )
    }

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
    private val json = LenientJson
    private val handledVideoIds = mutableSetOf<String>()
    private var retryJob: Job? = null

    private val _waitReason = MutableStateFlow<String?>(null)
    /** Why waiting automatic downloads (goals, queued, smart) can't start right now; null when they can or none wait. */
    val waitReason: StateFlow<String?> = _waitReason.asStateFlow()

    private val _goalStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    /** What each Finish a series goal is doing ("Waiting for Wi-Fi", ...), by show id; shown on its chip. */
    val goalStatus: StateFlow<Map<String, String>> = _goalStatus.asStateFlow()
    /** Shows just pinned: their first outcome is also a toast. */
    private val announceGoals = mutableSetOf<String>()
    /** Show id to the final episode (season, episode) that is on screen and not finished yet. */
    private val finishingLastEpisode = mutableMapOf<String, Pair<Int, Int>>()
    private val goalPlayback = mutableMapOf<String, GoalPlaybackSample>()
    private val goalSettleJob = mutableMapOf<String, Job>()
    private val goalStateLock = SynchronizedObject()

    private data class GoalPlaybackSample(
        val season: Int,
        val episode: Int,
        val positionMs: Long,
        val durationMs: Long,
        val creditsStartMs: Long?,
    )

    private fun reportGoal(metaId: String, text: String) {
        com.nuvio.app.core.logging.InAppLogger.info("SmartDownloads", "goal $metaId: $text")
        _goalStatus.value = _goalStatus.value + (metaId to text)
        if (announceGoals.remove(metaId)) NuvioToastController.show(text)
    }

    /** Unpinned: no new goal downloads for the show (files already downloading keep going). */
    fun dropGoal(metaId: String) {
        announceGoals.remove(metaId)
        synchronized(goalStateLock) { finishingLastEpisode.remove(metaId) }
        _goalStatus.value = _goalStatus.value - metaId
        scope.launch {
            mutex.withLock { savePending(loadPending().filterNot { it.count != null && it.parentMetaId == metaId }) }
        }
    }

    /** A goal request whose show was unpinned meanwhile (a run can outlast the pin). */
    private fun SmartDownloadRequest.goalDropped(): Boolean =
        count != null && !SmartDownloadSettings.isGoal(parentMetaType, parentMetaId)

    private fun SmartDownloadRequest.report(text: String) {
        if (count != null) reportGoal(parentMetaId, text)
    }

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
        if (positionMs >= at) onNearEnd(entry, positionMs, durationMs, credits)
    }

    /**
     * The episode on screen is near its end (credits or last minutes, see PlaybackEndRules).
     * A goal counts from the next episode on. The final episode does not move the anchor or drop
     * the goal here: that waits until playback has really ended.
     */
    fun onNearEnd(
        entry: WatchProgressEntry,
        positionMs: Long = entry.lastPositionMs,
        durationMs: Long = entry.durationMs,
        creditsStartMs: Long? = null,
    ) {
        val season = entry.seasonNumber ?: return
        val episode = entry.episodeNumber ?: return
        val firstTime = handledVideoIds.add(entry.videoId)
        if (SmartDownloadSettings.isGoal(entry.parentMetaType, entry.parentMetaId)) {
            settleGoalNearEnd(
                entry = entry,
                season = season,
                episode = episode,
                positionMs = positionMs,
                durationMs = durationMs,
                creditsStartMs = creditsStartMs ?: liveCreditsStartMs[entry.videoId],
                firstTime = firstTime,
            )
        }
        if (!isEnabled()) return
        val hasDownloadsOfShow = DownloadsRepository.uiState.value.items.any { it.parentMetaId == entry.parentMetaId }
        if (!hasDownloadsOfShow) return
        add(SmartDownloadRequest(entry.parentMetaId, entry.parentMetaType, season, episode))
    }

    /** Queues unaired episodes (season to episode); each downloads once it airs and a source is found. */
    fun queueUnaired(metaType: String, metaId: String, episodes: List<Pair<Int, Int>>) {
        if (episodes.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val requests = episodes.map { (season, episode) -> SmartDownloadRequest(metaId, metaType, season, episode, exact = true) }
                savePending(loadPending().filterNot { old -> requests.any(old::sameTarget) } + requests)
            }
            runPending()
        }
        NuvioToastController.show(
            if (episodes.size == 1) "Queued S${episodes[0].first}E${episodes[0].second}: it downloads once it airs"
            else "Queued ${episodes.size} episodes: they download once they air",
        )
    }

    /** Takes an episode back out of the queue of unaired episodes. */
    fun unqueue(metaId: String, season: Int?, episode: Int?) {
        scope.launch {
            mutex.withLock {
                savePending(loadPending().filterNot { it.exact && it.parentMetaId == metaId && it.season == season && it.episode == episode })
            }
        }
        NuvioToastController.show("Unqueued S${season}E$episode")
    }

    /** Whether this exact episode is queued to download once it airs. */
    fun isQueued(metaId: String, season: Int?, episode: Int?): Boolean =
        loadPending().any { it.exact && it.parentMetaId == metaId && it.season == season && it.episode == episode }

    /**
     * Finish a series goal: keeps [SmartDownloadSettings.goalCount] episodes (the Smart Downloads
     * count) downloaded after the furthest watched one, never earlier ([goalStart]). Checked at
     * launch, when pinned and near the end of each episode. A show is unpinned only when the last
     * episode is actually finished, not merely marked watched.
     */
    fun checkGoal(type: String, id: String, announce: Boolean = false) {
        if (announce) announceGoals += id
        scope.launch {
            val meta = MetaDetailsRepository.fetch(type, id)
            if (meta == null) {
                reportGoal(id, "Couldn't load the show, trying again at the next launch")
                return@launch
            }
            val watched = com.nuvio.app.features.watched.WatchedRepository
            val episodes = nextEpisodes(meta.videos, season = 0, episode = 0, count = Int.MAX_VALUE)
            val start = if (watched.isFullyWatchedSeries(meta.id, meta.type)) {
                episodes.size
            } else {
                goalStart(episodes, SmartDownloadSettings.goalAnchor(type, id)) { video ->
                    watched.isWatched(meta.id, meta.type, video.season, video.episode)
                }
            }
            val first = episodes.getOrNull(start)
            if (first == null) {
                if (!SmartDownloadSettings.isGoal(type, id)) return@launch
                val resume = episodes.lastOrNull()?.let { lastEpisodeResume(meta.id, it) }
                val decision = decideFinishSeriesGoal(
                    regularEpisodeCount = episodes.size,
                    playingEpisodeIndex = null,
                    nextEpisodeIndex = episodes.size,
                    lastEpisodePositionMs = resume?.positionMs ?: 0L,
                    lastEpisodeDurationMs = resume?.durationMs ?: 0L,
                    lastEpisodeMarkedCompleted = resume?.markedCompleted == true,
                    playbackReachedEnd = false,
                    playbackCompleted = false,
                )
                when (decision.action) {
                    FinishSeriesGoalAction.KeepPinned -> {
                        reportGoal(id, decision.statusText ?: FINISHING_LAST_EPISODE_STATUS)
                        return@launch
                    }
                    FinishSeriesGoalAction.RemoveAndToast -> {
                        unpinFinishedGoal(type, id, meta.name, decision.removalReason ?: "nothing left to download")
                        return@launch
                    }
                    FinishSeriesGoalAction.AdvanceAnchor -> return@launch
                }
            }
            episodes.getOrNull(start - 1)?.let { last ->
                SmartDownloadSettings.advanceGoalAnchor(type, id, last.season ?: return@let, last.episode ?: return@let)
            }
            if (first == null) return@launch
            if (!first.hasAiredForDownload()) {
                reportGoal(id, "Up to date · ${unairedLabel(first.released)}")
                return@launch
            }
            add(SmartDownloadRequest(id, type, first.season ?: return@launch, (first.episode ?: return@launch) - 1, count = SmartDownloadSettings.goalCount()))
        }
    }

    /**
     * Near-end of a pinned show. Earlier episodes move the anchor immediately. The final episode
     * stays pinned until [PlaybackEndRules.reachedEnd] or the 90 % completion rule.
     */
    private fun settleGoalNearEnd(
        entry: WatchProgressEntry,
        season: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        creditsStartMs: Long?,
        firstTime: Boolean,
    ) {
        val type = entry.parentMetaType
        val id = entry.parentMetaId
        val videoId = entry.videoId
        val sample = GoalPlaybackSample(season, episode, positionMs, durationMs, creditsStartMs)
        val heldFinale = synchronized(goalStateLock) {
            goalPlayback[videoId] = sample
            finishingLastEpisode[id]
        }
        if (heldFinale == season to episode) {
            if (episodeReallyFinished(sample)) {
                synchronized(goalStateLock) { finishingLastEpisode.remove(id) }
                scope.launch {
                    val name = MetaDetailsRepository.fetch(type, id)?.name ?: "series"
                    unpinFinishedGoal(type, id, name, finishedEpisodeReason(sample))
                }
            }
            return
        }
        val startJob = synchronized(goalStateLock) {
            goalSettleJob[videoId]?.isActive != true && firstTime
        }
        if (!startJob) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                resolveGoalNearEnd(type, id, videoId)
            } finally {
                synchronized(goalStateLock) { goalSettleJob.remove(videoId) }
            }
        }
        synchronized(goalStateLock) { goalSettleJob[videoId] = job }
        job.start()
    }

    private suspend fun resolveGoalNearEnd(type: String, id: String, videoId: String) {
        val initial = synchronized(goalStateLock) { goalPlayback[videoId] } ?: return
        val meta = MetaDetailsRepository.fetch(type, id)
        val sample = synchronized(goalStateLock) { goalPlayback[videoId] } ?: initial
        val episodes = meta?.let { nextEpisodes(it.videos, season = 0, episode = 0, count = Int.MAX_VALUE) }.orEmpty()
        val index = episodes.indexOfFirst { it.season == sample.season && it.episode == sample.episode }
        if (meta == null || episodes.isEmpty() || index < 0) {
            // Can't tell whether this is the finale. Count it and let checkGoal apply the resume guard.
            SmartDownloadSettings.advanceGoalAnchor(type, id, sample.season, sample.episode)
            checkGoal(type, id)
            return
        }
        val reachedEnd = PlaybackEndRules.reachedEnd(sample.positionMs, sample.durationMs, sample.creditsStartMs)
        val completed = isWatchProgressComplete(sample.positionMs, sample.durationMs, isEnded = false)
        val decision = decideFinishSeriesGoal(
            regularEpisodeCount = episodes.size,
            playingEpisodeIndex = index,
            nextEpisodeIndex = index + 1,
            lastEpisodePositionMs = sample.positionMs,
            lastEpisodeDurationMs = sample.durationMs,
            lastEpisodeMarkedCompleted = completed,
            playbackReachedEnd = reachedEnd,
            playbackCompleted = completed,
        )
        when (decision.action) {
            FinishSeriesGoalAction.AdvanceAnchor -> {
                SmartDownloadSettings.advanceGoalAnchor(type, id, sample.season, sample.episode)
                checkGoal(type, id)
            }
            FinishSeriesGoalAction.KeepPinned -> {
                synchronized(goalStateLock) { finishingLastEpisode[id] = sample.season to sample.episode }
                val latest = synchronized(goalStateLock) { goalPlayback[videoId] } ?: sample
                if (episodeReallyFinished(latest)) {
                    synchronized(goalStateLock) { finishingLastEpisode.remove(id) }
                    unpinFinishedGoal(type, id, meta.name, finishedEpisodeReason(latest))
                } else {
                    reportGoal(id, decision.statusText ?: FINISHING_LAST_EPISODE_STATUS)
                }
            }
            FinishSeriesGoalAction.RemoveAndToast -> {
                unpinFinishedGoal(type, id, meta.name, decision.removalReason ?: finishedEpisodeReason(sample))
            }
        }
    }

    private fun episodeReallyFinished(sample: GoalPlaybackSample): Boolean =
        PlaybackEndRules.reachedEnd(sample.positionMs, sample.durationMs, sample.creditsStartMs) ||
            isWatchProgressComplete(sample.positionMs, sample.durationMs, isEnded = false)

    private fun finishedEpisodeReason(sample: GoalPlaybackSample): String {
        val reachedEnd = PlaybackEndRules.reachedEnd(sample.positionMs, sample.durationMs, sample.creditsStartMs)
        val completed = isWatchProgressComplete(sample.positionMs, sample.durationMs, isEnded = false)
        return decideFinishSeriesGoal(
            regularEpisodeCount = 1,
            playingEpisodeIndex = 0,
            nextEpisodeIndex = 1,
            lastEpisodePositionMs = sample.positionMs,
            lastEpisodeDurationMs = sample.durationMs,
            lastEpisodeMarkedCompleted = completed,
            playbackReachedEnd = reachedEnd,
            playbackCompleted = completed,
        ).removalReason ?: "last episode finished"
    }

    private data class LastEpisodeResume(
        val positionMs: Long,
        val durationMs: Long,
        val markedCompleted: Boolean,
    )

    /** Resume row for [video], preferring one that is still in progress over a stale completed flag. */
    private fun lastEpisodeResume(metaId: String, video: MetaVideo): LastEpisodeResume? {
        val playbackId = buildPlaybackVideoId(
            parentMetaId = metaId,
            seasonNumber = video.season,
            episodeNumber = video.episode,
            fallbackVideoId = video.id,
        )
        val ids = listOfNotNull(video.id.takeIf { it.isNotBlank() }, playbackId).distinct()
        val lookedUp = ids.mapNotNull { videoId ->
            WatchProgressRepository.progressForVideo(
                videoId = videoId,
                parentMetaId = metaId,
                seasonNumber = video.season,
                episodeNumber = video.episode,
            )
        }
        val listed = WatchProgressRepository.uiState.value.entries.filter { entry ->
            entry.parentMetaId == metaId &&
                entry.seasonNumber == video.season &&
                entry.episodeNumber == video.episode
        }
        val entries = (lookedUp + listed).distinctBy { it.resolvedProgressKey() }
        if (entries.isEmpty()) return null
        val chosen = entries
            .filter { resumeStillInProgress(it.lastPositionMs, it.durationMs, it.isEffectivelyCompleted) }
            .maxByOrNull { it.lastUpdatedEpochMs }
            ?: entries.maxBy { it.lastUpdatedEpochMs }
        return LastEpisodeResume(
            positionMs = chosen.lastPositionMs,
            durationMs = chosen.durationMs,
            markedCompleted = chosen.isEffectivelyCompleted,
        )
    }

    /** Unpin a finished show, log why, and toast. No-op when the pin is already gone. */
    private fun unpinFinishedGoal(type: String, id: String, name: String, reason: String) {
        if (!SmartDownloadSettings.isGoal(type, id)) return
        synchronized(goalStateLock) { finishingLastEpisode.remove(id) }
        com.nuvio.app.core.logging.InAppLogger.info("SmartDownloads", "goal $id removed: $reason")
        SmartDownloadSettings.setGoal(type, id, pinned = false)
        MediaAnalysisStore.forgetReferences(id)
        NuvioToastController.show("Finished $name, goal removed")
    }

    private fun add(request: SmartDownloadRequest) {
        scope.launch {
            // Newest first, so a pin runs before older waiting requests.
            mutex.withLock { savePending(listOf(request) + loadPending().filterNot { it.sameTarget(request) }) }
            runPending()
        }
    }

    fun retryPending() {
        scope.launch { runPending() }
    }

    /** Why automatic downloads can't start now, or null when they can. */
    private fun waitReason(): String? {
        val settings = SmartDownloadSettings.snapshot()
        if (settings.onlyOnWifi && !DownloadNetworkGuard.isOnWifi()) return "Waiting for Wi-Fi (Settings > Downloads > Only on Wi-Fi)"
        if (settings.onlyWhileCharging && !MediaAnalyzerPlatform.isCharging()) return "Waiting for charging"
        return null
    }

    private suspend fun runPending() {
        mutex.withLock {
            val pending = loadPending()
            val wait = waitReason().takeIf { pending.isNotEmpty() }
            _waitReason.value = wait
            if (pending.isEmpty()) return
            if (wait != null) {
                pending.forEach { it.report(wait) }
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
        if (request.goalDropped()) return null
        if (request.notBeforeEpochMs > EpisodeReleaseDatePlatform.nowEpochMs()) return request
        DownloadsRepository.ensureLoaded()
        val meta = MetaDetailsRepository.fetch(request.parentMetaType, request.parentMetaId) ?: return request
        val wanted = if (request.exact) {
            meta.videos.filter { it.season == request.season && it.episode == request.episode }.take(1)
        } else {
            // A goal never goes back before its anchor (an older request, a rewatch).
            val anchor = SmartDownloadSettings.goalAnchor(request.parentMetaType, request.parentMetaId)
                .takeIf { request.count != null }
            val (season, episode) = laterEpisode(anchor, request.season to request.episode)
            nextEpisodes(meta.videos, season, episode, request.count ?: settings.count)
        }
        val (next, upcoming) = wanted.partition { it.hasAiredForDownload() }
        // Not aired yet: wait for its air date (a day when it has none), then try again.
        val later = upcoming.takeIf { settings.queueUnaired && it.isNotEmpty() }
            ?.let { request.copy(notBeforeEpochMs = nextAirCheckEpochMs(it)) }
        if (next.isEmpty()) {
            request.report(upcoming.firstOrNull()?.let { "Up to date · ${unairedLabel(it.released)}" } ?: "Up to date")
            return later
        }
        val items = DownloadsRepository.uiState.value.items
        val missing = next.filter { video ->
            val key = downloadLogicalContentKey(meta.id, video.season, video.episode)
            seasonExistingDownload(items.firstOrNull { it.logicalContentKey == key }?.status) == null
        }
        if (missing.isEmpty()) {
            request.report(if (next.size == 1) "The next episode is downloaded" else "The next ${next.size} episodes are downloaded")
            return later
        }
        // Disk nearly full: don't ask the addons for streams that could not be saved anyway.
        val free = DownloadDiskInfo.volumeSpace()?.availableBytes
        if (free != null && free < SMART_DOWNLOAD_MIN_FREE_BYTES) return noSpace(request)
        val reference = items
            .filter { it.parentMetaId == meta.id && it.isEpisode }
            .maxByOrNull { it.updatedAtEpochMs }
        request.report("Looking for sources…")
        val picked = missing.mapNotNull { video ->
            pickStream(meta, video, reference)?.let { SeasonEnqueueRow(video, it) }
        }
        // No source yet (often right after it airs): look again in an hour.
        if (picked.isEmpty()) {
            request.report("No downloadable source found yet, trying again in 1 h")
            return request.copy(notBeforeEpochMs = EpisodeReleaseDatePlatform.nowEpochMs() + HOUR_MS)
        }
        // Only what fits: each file plus its reserve, after what running downloads still need.
        val fit = smartDownloadsThatFit(
            rows = picked,
            sizeOf = { row -> downloadStreamBytes(row.stream) },
            fallbackBytes = typicalEpisodeBytes(meta.id, items),
            freeBytes = DownloadDiskInfo.volumeSpace()?.availableBytes,
            // Queued ones will need their space too; the start gate itself only counts running ones.
            inProgressRemainingBytes = items
                .filter { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }
                .sumOf { item ->
                    val size = item.totalBytes?.takeIf { it > 0L } ?: typicalEpisodeBytes(item.parentMetaId, items) ?: GUESSED_EPISODE_BYTES
                    (size - item.downloadedBytes).coerceAtLeast(0L)
                },
        )
        if (fit.isEmpty()) return noSpace(request)
        if (request.goalDropped()) return null
        val result = enqueueSeasonDownloads(meta, fit)
        if (result.queued > 0) {
            val codes = fit.take(result.queued).joinToString(", ") { "S${it.video.season}E${it.video.episode}" }
            if (request.count != null) {
                announceGoals.remove(request.parentMetaId)
                reportGoal(request.parentMetaId, "Downloading $codes")
            }
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
        request.report("Not enough free space for the next episode")
        if (FinishedDownloadDeletion.hasPendingDeletion()) return request
        StorageAutopilot.run()
        val key = "${request.parentMetaId}:${request.season}:${request.episode}"
        if (noSpaceShown.add(key)) NuvioToastController.show(SMART_DOWNLOADS_NO_SPACE)
        return request
    }

    internal suspend fun pickStream(meta: MetaDetails, video: MetaVideo, reference: DownloadItem?): StreamItem? {
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
        return pickWithoutReference(streams.orderedForQuality(quality), currentAutoPickSettings(meta.id), installedAddonNames())
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

private const val HOUR_MS = 60 * 60_000L
private const val DAY_MS = 24 * HOUR_MS

/** Below this much free space Smart Downloads don't even look for streams. */
internal const val SMART_DOWNLOAD_MIN_FREE_BYTES = 1_000_000_000L

/**
 * The source for an episode with no earlier download to copy: the auto-play pick, else (auto-play
 * set to manual, the default) the first downloadable source in [ordered] quality order. Without
 * that fallback a queued episode never found a source.
 */
internal fun pickWithoutReference(
    ordered: List<StreamItem>,
    settings: SeasonAutoPickSettings,
    installedAddonNames: Set<String>,
): StreamItem? = autoPickSeasonStream(ordered, settings, installedAddonNames)
    ?: ordered.firstOrNull { it.isSeasonDownloadCandidate() }

/**
 * Index in [episodes] (in order) where a Finish a series goal starts: right after the furthest
 * watched one, so an old skipped episode is not fetched. [episodes].size when the last is watched.
 */
/**
 * Index in [episodes] (in order, no specials) where a goal starts: after the furthest watched
 * episode and after [anchor], whichever is later. [episodes].size: the series is done. It never
 * wraps around to the first episode.
 */
internal fun goalStart(episodes: List<MetaVideo>, anchor: Pair<Int, Int>?, isWatched: (MetaVideo) -> Boolean): Int {
    val afterWatched = episodes.indexOfLast(isWatched) + 1
    val afterAnchor = anchor?.let { (season, episode) ->
        episodes.count { laterEpisode(it.season!! to it.episode!!, season to episode) == season to episode }
    } ?: 0
    return maxOf(afterWatched, afterAnchor)
}

/** The later of two season/episode positions (null counts as before everything). */
internal fun laterEpisode(a: Pair<Int, Int>?, b: Pair<Int, Int>): Pair<Int, Int> =
    if (a != null && compareValuesBy(a, b, { it.first }, { it.second }) > 0) a else b

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
