package com.nuvio.app.features.analysis

import com.nuvio.app.core.serialization.LenientJson
import com.nuvio.app.features.downloads.DownloadDiskInfo
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadSubtitles
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.PlaybackPriority
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleSourceMemory
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.fitSubtitleEnergy
import com.nuvio.app.features.player.subtitleSourceKey
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.epochMs
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Free space below this pauses the next-episode pass. Null from the platform does not. */
internal const val ANALYSIS_FREE_SPACE_BRAKE_BYTES: Long = 15_000_000_000L

/** A stream window stays under the 50 second clip limit. */
internal const val NEXT_EPISODE_STREAM_SLICE_MS = 45_000L

private const val NEXT_EPISODE_HOLD_RETRY_MS = 15_000L

internal enum class NextEpisodeAnalysisHold {
    None,
    Disk,
    LowPower,
    LowBattery,
    Thermal,
}

internal data class NextEpisodeAnalysisConditions(
    val videoPlaying: Boolean,
    val freeBytes: Long?,
    val thermal: AnalysisThermal,
    val lowPowerMode: Boolean,
    val lowBattery: Boolean,
)

/**
 * Disk, then Low Power Mode, then a low battery, then serious or critical heat.
 * Playback itself does not hold this pass. That is the queue it is here to cover.
 */
internal fun nextEpisodeAnalysisHold(conditions: NextEpisodeAnalysisConditions): NextEpisodeAnalysisHold = when {
    conditions.freeBytes != null && conditions.freeBytes < ANALYSIS_FREE_SPACE_BRAKE_BYTES -> NextEpisodeAnalysisHold.Disk
    conditions.lowPowerMode -> NextEpisodeAnalysisHold.LowPower
    conditions.lowBattery -> NextEpisodeAnalysisHold.LowBattery
    conditions.thermal == AnalysisThermal.Serious || conditions.thermal == AnalysisThermal.Critical -> NextEpisodeAnalysisHold.Thermal
    else -> NextEpisodeAnalysisHold.None
}

internal fun nextEpisodeHoldToken(hold: NextEpisodeAnalysisHold): String = when (hold) {
    NextEpisodeAnalysisHold.None -> ""
    NextEpisodeAnalysisHold.Disk -> "paused-disk"
    NextEpisodeAnalysisHold.LowPower -> "paused-power"
    NextEpisodeAnalysisHold.LowBattery -> "paused-battery"
    NextEpisodeAnalysisHold.Thermal -> "paused-heat"
}

internal data class NextEpisodeTarget(
    val parentMetaId: String,
    val season: Int,
    val episode: Int,
    val videoId: String? = null,
    val localFileUri: String? = null,
    val streamUrl: String? = null,
    val streamHeaders: Map<String, String> = emptyMap(),
    val cues: List<SubtitleSyncCue> = emptyList(),
    val provider: String? = null,
    val language: String? = null,
    val release: String? = null,
    val fileName: String? = null,
    val durationMs: Long = 0L,
    val downloadId: String? = null,
)

internal data class NextEpisodeAnalysisStatus(
    val downloadId: String? = null,
    val parentMetaId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val token: String = "",
)

internal fun nextEpisodeStatusMatches(
    status: NextEpisodeAnalysisStatus,
    parentMetaId: String,
    season: Int?,
    episode: Int?,
    downloadId: String,
): Boolean {
    if (status.token.isEmpty()) return false
    if (status.downloadId != null && status.downloadId == downloadId) return true
    return status.parentMetaId == parentMetaId &&
        season != null &&
        episode != null &&
        status.season == season &&
        status.episode == episode
}

internal interface NextEpisodeWindows {
    suspend fun localWindow(fileUri: String, windowMs: Long, startMs: Long): RawMediaAnalysis?
    suspend fun streamWindow(url: String, headers: Map<String, String>, startMs: Long, endMs: Long): RawClipFingerprint?
}

internal data class NextEpisodeRunResult(
    val hold: NextEpisodeAnalysisHold,
    val storedKey: String? = null,
    val playbackStarted: Boolean = false,
)

internal fun analysisSlices(startMs: Long, endMs: Long, sliceMs: Long = NEXT_EPISODE_STREAM_SLICE_MS): List<Pair<Long, Long>> {
    if (endMs <= startMs || sliceMs <= 0L) return emptyList()
    val out = ArrayList<Pair<Long, Long>>()
    var cursor = startMs
    while (cursor < endMs) {
        val next = (cursor + sliceMs).coerceAtMost(endMs)
        if (next > cursor) out += cursor to next
        cursor = next
    }
    return out
}

internal fun stitchClipFingerprints(parts: List<RawClipFingerprint>): String? {
    if (parts.isEmpty() || parts.any { decodeFingerprint(it.fingerprint).isEmpty() }) return null
    val frames = parts.flatMap { decodeFingerprint(it.fingerprint).asList() }
    if (frames.isEmpty()) return null
    return encodeFingerprint(frames.toIntArray())
}

internal fun stitchClipEnergy(parts: List<RawClipFingerprint>): String? {
    if (parts.isEmpty() || parts.any { decodeEnergyLevels(it.energy).isEmpty() }) return null
    val levels = parts.flatMap { decodeEnergyLevels(it.energy).asList() }
    if (levels.isEmpty()) return null
    return encodeEnergyLevels(levels.toIntArray())
}

/**
 * While one episode is open, measures the next one's intro, credits, and subtitle fit.
 * Uses the downloaded file when there is one, otherwise short in-memory slices of the stream.
 * Stores the result and does not start playback or send anything to TheIntroDB.
 */
internal object NextEpisodeAnalysisScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = LenientJson
    private val jobLock = SynchronizedObject()
    private var job: Job? = null
    private var watched: WatchedEpisode? = null
    private val _status = MutableStateFlow(NextEpisodeAnalysisStatus())
    val status: StateFlow<NextEpisodeAnalysisStatus> = _status.asStateFlow()

    internal var windowsForTest: NextEpisodeWindows? = null
    internal var conditionsForTest: (() -> NextEpisodeAnalysisConditions)? = null
    internal var downloadLookupForTest: ((String) -> NextEpisodeTarget?)? = null
    internal var targetOverrideForTest: ((String, Int, Int, String?) -> NextEpisodeTarget?)? = null
    internal var scopeForTest: CoroutineScope? = null

    private val platformWindows = object : NextEpisodeWindows {
        override suspend fun localWindow(fileUri: String, windowMs: Long, startMs: Long): RawMediaAnalysis? {
            val raw = MediaAnalyzerPlatform.analyzeIntroWindow(fileUri, windowMs, startMs) ?: return null
            return runCatching { json.decodeFromString(RawMediaAnalysis.serializer(), raw) }.getOrNull()
        }

        override suspend fun streamWindow(
            url: String,
            headers: Map<String, String>,
            startMs: Long,
            endMs: Long,
        ): RawClipFingerprint? {
            val raw = MediaAnalyzerPlatform.fingerprintClipDuringPlayback(url, headers, startMs, endMs) ?: return null
            return runCatching { json.decodeFromString(RawClipFingerprint.serializer(), raw) }.getOrNull()
        }
    }

    /**
     * Battery level is a UIKit read, so the live snapshot hops to the main thread.
     * A test hook stays on the caller and does not touch the device.
     */
    suspend fun currentConditions(): NextEpisodeAnalysisConditions {
        conditionsForTest?.let { return it() }
        val power = withContext(Dispatchers.Main.immediate) { AnalysisPower.snapshot() }
        val free = runCatching { DownloadDiskInfo.volumeSpace()?.availableBytes }.getOrNull()
        return NextEpisodeAnalysisConditions(
            videoPlaying = PlaybackPriority.level != PlaybackPriority.Level.Normal,
            freeBytes = free,
            thermal = power.thermal,
            lowPowerMode = power.lowPowerMode,
            lowBattery = power.lowBattery,
        )
    }

    fun watchPlayingNext(parentMetaId: String, season: Int, episode: Int, videoId: String?) {
        if (parentMetaId.isBlank()) return
        synchronized(jobLock) {
            watched = WatchedEpisode(parentMetaId, season, episode, videoId)
        }
        start {
            val target = targetOverrideForTest?.invoke(parentMetaId, season, episode, videoId)
                ?: targetFor(parentMetaId, season, episode, videoId)
                ?: return@start
            finish(target)
        }
    }

    /**
     * A file that finished while the player is open. Idle playback stays on the full-file queue,
     * which [com.nuvio.app.features.downloads.DownloadsRepository] already wakes.
     */
    fun onDownloadCompleted(downloadId: String) {
        start {
            val conditions = currentConditions()
            val playing = conditions.videoPlaying
            val hold = nextEpisodeAnalysisHold(conditions)
            if (!playing && hold == NextEpisodeAnalysisHold.None) return@start
            val target = if (downloadLookupForTest != null) {
                downloadLookupForTest?.invoke(downloadId)
            } else {
                runCatching { targetFromDownload(downloadId) }.getOrNull()
            } ?: return@start
            if (hold != NextEpisodeAnalysisHold.None) {
                publish(target, nextEpisodeHoldToken(hold))
                return@start
            }
            finish(target)
            val resume = synchronized(jobLock) { watched }
            if (
                resume != null &&
                (resume.parentMetaId != target.parentMetaId || resume.season != target.season || resume.episode != target.episode)
            ) {
                val next = targetOverrideForTest?.invoke(resume.parentMetaId, resume.season, resume.episode, resume.videoId)
                    ?: targetFor(resume.parentMetaId, resume.season, resume.episode, resume.videoId)
                if (next != null) finish(next)
            }
        }
    }

    fun cancelWatch() {
        val previous = synchronized(jobLock) {
            watched = null
            val old = job
            job = null
            old
        }
        previous?.cancel()
        _status.value = NextEpisodeAnalysisStatus()
    }

    internal suspend fun runNow(target: NextEpisodeTarget): NextEpisodeRunResult = run(target)

    fun resetForTest() {
        cancelWatch()
        windowsForTest = null
        conditionsForTest = null
        downloadLookupForTest = null
        targetOverrideForTest = null
        scopeForTest = null
    }

    private fun start(block: suspend () -> Unit) {
        val host = scopeForTest
        val launched = if (host != null) host.launch { block() } else scope.launch { block() }
        val previous = synchronized(jobLock) {
            val old = job
            job = launched
            old
        }
        previous?.cancel()
    }

    private suspend fun finish(target: NextEpisodeTarget) {
        if (scopeForTest != null) {
            run(target)
            return
        }
        while (currentCoroutineContext().isActive) {
            val result = run(target)
            if (result.hold == NextEpisodeAnalysisHold.None) return
            delay(NEXT_EPISODE_HOLD_RETRY_MS)
        }
    }

    private suspend fun run(target: NextEpisodeTarget): NextEpisodeRunResult {
        currentCoroutineContext().ensureActive()
        val hold = nextEpisodeAnalysisHold(currentConditions())
        if (hold != NextEpisodeAnalysisHold.None) {
            publish(target, nextEpisodeHoldToken(hold))
            return NextEpisodeRunResult(hold)
        }
        if (target.localFileUri.isNullOrBlank() && target.streamUrl.isNullOrBlank()) {
            publish(target, "")
            return NextEpisodeRunResult(NextEpisodeAnalysisHold.None)
        }
        val read = if (!target.localFileUri.isNullOrBlank()) readLocal(target) else readStream(target)
        return when (read) {
            is Read.Held -> {
                publish(target, nextEpisodeHoldToken(read.hold))
                NextEpisodeRunResult(read.hold)
            }
            Read.Empty -> {
                publish(target, "")
                NextEpisodeRunResult(NextEpisodeAnalysisHold.None)
            }
            is Read.Ready -> {
                currentCoroutineContext().ensureActive()
                val again = nextEpisodeAnalysisHold(currentConditions())
                if (again != NextEpisodeAnalysisHold.None) {
                    publish(target, nextEpisodeHoldToken(again))
                    return NextEpisodeRunResult(again)
                }
                publish(target, "subtitles")
                val stored = store(target, read.draft)
                publish(target, "done")
                NextEpisodeRunResult(NextEpisodeAnalysisHold.None, stored.key)
            }
        }
    }

    private suspend fun readLocal(target: NextEpisodeTarget): Read {
        val uri = target.localFileUri ?: return Read.Empty
        val windows = windowsForTest ?: platformWindows
        val blocked = heldNow()
        if (blocked != null) return Read.Held(blocked)
        publish(target, "intro")
        currentCoroutineContext().ensureActive()
        val intro = windows.localWindow(uri, INTRO_WINDOW_MS, 0L)
        val afterIntro = heldNow()
        if (afterIntro != null) return Read.Held(afterIntro)
        publish(target, "credits")
        currentCoroutineContext().ensureActive()
        val credits = windows.localWindow(uri, CREDITS_WINDOW_MS, WINDOW_AT_END)
        val afterCredits = heldNow()
        if (afterCredits != null) return Read.Held(afterCredits)
        val draft = assemble(target, intro, credits) ?: return Read.Empty
        return Read.Ready(draft)
    }

    private suspend fun readStream(target: NextEpisodeTarget): Read {
        val url = target.streamUrl ?: return Read.Empty
        val windows = windowsForTest ?: platformWindows
        publish(target, "intro")
        val introParts = ArrayList<RawClipFingerprint>()
        for ((start, end) in analysisSlices(0L, INTRO_WINDOW_MS)) {
            currentCoroutineContext().ensureActive()
            val blocked = heldNow()
            if (blocked != null) return Read.Held(blocked)
            val clip = windows.streamWindow(url, target.streamHeaders, start, end) ?: break
            if (!clip.windowComplete || decodeFingerprint(clip.fingerprint).isEmpty()) break
            introParts += clip
        }
        publish(target, "credits")
        val creditParts = ArrayList<RawClipFingerprint>()
        val creditsOrigin = if (target.durationMs > CREDITS_WINDOW_MS) target.durationMs - CREDITS_WINDOW_MS else -1L
        if (creditsOrigin >= 0L) {
            for ((start, end) in analysisSlices(creditsOrigin, target.durationMs)) {
                currentCoroutineContext().ensureActive()
                val blocked = heldNow()
                if (blocked != null) return Read.Held(blocked)
                val clip = windows.streamWindow(url, target.streamHeaders, start, end) ?: break
                if (!clip.windowComplete || decodeFingerprint(clip.fingerprint).isEmpty()) break
                creditParts += clip
            }
        }
        val intro = RawMediaAnalysis(
            durationMs = target.durationMs,
            energy = stitchClipEnergy(introParts),
            energyStepMs = introParts.firstOrNull()?.energyStepMs?.takeIf { it > 0 } ?: 100,
            head = stitchClipFingerprints(introParts),
        )
        val credits = RawMediaAnalysis(
            durationMs = target.durationMs,
            tail = stitchClipFingerprints(creditParts),
            tailStartMs = creditsOrigin.coerceAtLeast(0L),
        )
        val draft = assemble(target, intro, credits) ?: return Read.Empty
        return Read.Ready(draft)
    }

    private suspend fun heldNow(): NextEpisodeAnalysisHold? =
        nextEpisodeAnalysisHold(currentConditions()).takeIf { it != NextEpisodeAnalysisHold.None }

    private fun assemble(
        target: NextEpisodeTarget,
        intro: RawMediaAnalysis?,
        credits: RawMediaAnalysis?,
    ): MediaAnalysisResult? {
        if (intro == null && credits == null) return null
        val energy = intro?.energy
        val head = intro?.head
        val tail = credits?.tail
        if (energy.isNullOrEmpty() && head.isNullOrEmpty() && tail.isNullOrEmpty()) return null
        val duration = maxOf(intro?.durationMs ?: 0L, credits?.durationMs ?: 0L, target.durationMs)
        return MediaAnalysisResult(
            key = MediaAnalysisStore.keyFor(target.parentMetaId, target.season, target.episode),
            analyzedAtMs = epochMs(),
            durationMs = duration,
            fileName = target.fileName,
            energyStepMs = intro?.energyStepMs?.takeIf { it > 0 } ?: 100,
            energy = energy,
            fingerprintStepMs = intro?.fingerprintStepMs?.takeIf { it > 0 }
                ?: credits?.fingerprintStepMs?.takeIf { it > 0 }
                ?: 100,
            headFingerprint = head,
            tailFingerprint = tail,
            tailStartMs = if (tail != null) credits.tailStartMs else 0L,
        )
    }

    /**
     * Same detector and subtitle fit as a finished download. Nothing is submitted upstream.
     */
    private fun store(target: NextEpisodeTarget, draft: MediaAnalysisResult): MediaAnalysisResult {
        val siblings = MediaAnalysisStore.seasonSiblings(target.parentMetaId, target.season, draft.key)
        val detected = SeasonSegmentDetector.detect(draft, siblings)
        var next = draft.applyingDetection(detected)
        if (target.cues.isNotEmpty() && !next.energy.isNullOrEmpty()) {
            val fit = fitSubtitleEnergy(next, target.cues)
            if (fit.confident) {
                val sourceKey = subtitleSourceKey(target.provider, target.language, target.release)
                next = next.copy(
                    subtitleSyncSpeed = fit.subSpeed,
                    subtitleSyncDelayMs = fit.subDelayMs,
                    subtitleSyncSourceKey = sourceKey,
                )
                SubtitleSourceMemory.remember(sourceKey, fit.subSpeed, fit.subDelayMs)
            }
        }
        MediaAnalysisStore.save(next)
        return MediaAnalysisStore.load(next.key) ?: next
    }

    private fun publish(target: NextEpisodeTarget, token: String) {
        _status.value = NextEpisodeAnalysisStatus(
            downloadId = target.downloadId,
            parentMetaId = target.parentMetaId,
            season = target.season,
            episode = target.episode,
            token = token,
        )
    }

    private fun targetFromDownload(downloadId: String): NextEpisodeTarget? {
        val item = DownloadsRepository.uiState.value.items.firstOrNull { it.id == downloadId } ?: return null
        val season = item.seasonNumber ?: return null
        val episode = item.episodeNumber ?: return null
        if (item.status != DownloadStatus.Completed) return null
        return targetFor(item.parentMetaId, season, episode, item.videoId)?.copy(
            downloadId = item.id,
            fileName = item.fileName,
        )
    }

    private fun targetFor(
        parentMetaId: String,
        season: Int,
        episode: Int,
        videoId: String?,
    ): NextEpisodeTarget? {
        if (parentMetaId.isBlank()) return null
        val item = runCatching {
            DownloadsRepository.findPlayableDownload(parentMetaId, season, episode, videoId)
        }.getOrNull()
        val uri = item?.let { runCatching { DownloadsRepository.playableLocalFileUri(it) }.getOrNull() }
        val local = uri?.takeIf { it.startsWith("file:") }
        val track = local?.let { runCatching { DownloadSubtitles.firstTrack(it) }.getOrNull() }
        val cues = track?.let { parsed ->
            runCatching { PlayerSubtitleCueParser.parse(parsed.text, "subs.srt") }.getOrNull()
        }.orEmpty()
        val cached = if (local == null) cachedStream(videoId, parentMetaId, season, episode) else null
        if (local == null && cached == null) return null
        return NextEpisodeTarget(
            parentMetaId = parentMetaId,
            season = season,
            episode = episode,
            videoId = videoId,
            localFileUri = local,
            streamUrl = cached?.first,
            streamHeaders = cached?.second ?: emptyMap(),
            cues = cues,
            provider = track?.name,
            language = track?.language,
            release = track?.name,
            fileName = item?.fileName,
            downloadId = item?.id,
        )
    }

    private fun cachedStream(
        videoId: String?,
        parentMetaId: String,
        season: Int,
        episode: Int,
    ): Pair<String, Map<String, String>>? {
        if (videoId.isNullOrBlank()) return null
        val key = StreamLinkCacheRepository.contentKey("series", videoId, parentMetaId, season, episode)
        val cached = runCatching {
            StreamLinkCacheRepository.getValid(key, 12L * 60L * 60L * 1000L)
        }.getOrNull() ?: return null
        val url = cached.url
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        return url to cached.requestHeaders
    }

    private sealed interface Read {
        data class Ready(val draft: MediaAnalysisResult) : Read
        data class Held(val hold: NextEpisodeAnalysisHold) : Read
        data object Empty : Read
    }

    private data class WatchedEpisode(
        val parentMetaId: String,
        val season: Int,
        val episode: Int,
        val videoId: String?,
    )
}
