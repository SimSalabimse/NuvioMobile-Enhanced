package com.nuvio.app.features.downloads

import com.nuvio.app.core.serialization.LenientDefaultsJson
import com.nuvio.app.features.analysis.ANALYSIS_MAX_ATTEMPTS
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.MediaAnalysisSettings
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.analysis.MediaAnalyzerPlatform
import com.nuvio.app.features.streams.epochMs
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.allowsProgressWrite
import com.nuvio.app.features.watchprogress.ProgressWriteTrace
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Queues removal of a finished download when in-app playback completes, and
 * runs [DownloadsRepository.cancelDownload] only after the player has closed (plus a short grace,
 * see [closeGraceMs]) or the next episode is actually playing, never for the video on screen.
 *
 * A transfer that is still running when completion fires is not removed while it runs.
 * An early-play session that crosses the threshold before the bytes finish is removed when
 * the player leaves it only if the download has finished by then (it used to be kept for good).
 * Scrubbing back inside the same video keeps the file until the player leaves it.
 *
 * Completion is reported from the watch-progress upsert for every completed in-app playback,
 * also when Trakt or MDBList owns watched history (that path used to skip this entirely).
 *
 * An automatic delete of a finished episode waits until its intro fingerprint is stored (or the
 * wait hits [ANALYSIS_HOLD_CAP_MS], or analysis fails for good). The file is not needed after that.
 * A delete the user asked for is not held.
 *
 * Anything played in this process is remembered for storage autopilot. Keep free space may
 * remove older watched downloads, but not one played here, not the one on screen, and not a
 * file that is still transferring. Only "delete when finished" removes the episode just played.
 */
internal object FinishedDownloadDeletion {
    private var session: OpenPlayback? = null
    private var pending: PendingDeletion? = null
    private var pendingJob: Job? = null
    private val playedLock = SynchronizedObject()
    private val playedVideoIds = mutableSetOf<String>()
    private val playedDownloadIds = mutableSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * How long a closed player waits before its file goes. The player screen can be torn down and
     * rebuilt for the same episode (back from the background, a layout change, a reload); the file
     * used to be deleted on that teardown, so the rebuilt player found no download and fell back to
     * a stream (TorBox) mid episode. Reopening the same video within this time keeps the file.
     */
    internal var closeGraceMs: Long = 5_000L

    internal var deletionAnalyserSupported: Boolean? = null
    internal var deletionStoredAnalysis: ((DownloadItem) -> MediaAnalysisResult?)? = null

    internal var analysisHoldCapMs: Long = ANALYSIS_HOLD_CAP_MS

    internal var deletionNowMs: () -> Long = { epochMs() }

    /** Null uses [MediaAnalyzerPlatform.isCharging]. */
    internal var deletionDeviceCharging: (() -> Boolean)? = null

    /** Null uses the analysis "only while charging" setting. */
    internal var deletionOnlyWhileCharging: (() -> Boolean)? = null

    private var generationClock = 0
    private var holdCapJob: Job? = null

    fun playbackOpened(videoId: String, sourceUrl: String, persistsProgress: Boolean = true) {
        if (!allowsProgressWrite(persistsProgress)) return
        ProgressWriteTrace.note("FinishedDownloadDeletion.playbackOpened")
        notePlayed(videoId, downloadIdForSource(sourceUrl))
        val previous = session
        if (previous != null && previous.videoId != videoId) {
            notePlayed(previous.videoId, previous.queuedDownloadId)
            notePlayed(previous.videoId, previous.linkedDownloadId)
            notePlayed(previous.videoId, previous.incompleteDownloadId)
            notePlayed(previous.videoId, downloadIdForSource(previous.sourceUrl))
        }
        cancelPendingFor(videoId, sourceUrl)
        val current = session
        if (current != null && current.videoId == videoId) {
            session = current.copy(sourceUrl = sourceUrl)
            return
        }
        // Next episode: the previous file waits until this one is actually playing. Moving on from
        // the next-episode card counts as finished when it was near the end (credits or last 5 min).
        if (current != null) {
            val moved = if (current.queuedDownloadId == null && current.incompleteDownloadId == null && current.nearEnd) {
                queueFor(current) ?: current
            } else {
                current
            }
            release(moved, untilOtherPlays = true)
        }
        session = OpenPlayback(videoId = videoId, sourceUrl = sourceUrl)
    }

    fun playbackSourceChanged(videoId: String, sourceUrl: String, persistsProgress: Boolean = true) {
        if (!allowsProgressWrite(persistsProgress)) return
        ProgressWriteTrace.note("FinishedDownloadDeletion.playbackSourceChanged")
        notePlayed(videoId, downloadIdForSource(sourceUrl))
        cancelPendingFor(videoId, sourceUrl)
        val current = session ?: return
        if (current.videoId != videoId) return
        session = current.copy(sourceUrl = sourceUrl)
    }

    /**
     * The player moved from a stream to the downloaded file of the same video (or back). The
     * session keeps that download, so finishing the video counts for the file whichever of the
     * two is on screen when completion fires.
     */
    fun linkDownload(videoId: String, downloadId: String, persistsProgress: Boolean = true) {
        if (!allowsProgressWrite(persistsProgress)) return
        ProgressWriteTrace.note("FinishedDownloadDeletion.linkDownload")
        notePlayed(videoId, downloadId)
        val current = session ?: return
        if (current.videoId != videoId) return
        session = current.copy(linkedDownloadId = downloadId)
    }

    /** The player is showing frames of [videoId]: a previous episode waiting for that may go. */
    fun playbackPlaying(videoId: String, persistsProgress: Boolean = true) {
        if (!allowsProgressWrite(persistsProgress)) return
        ProgressWriteTrace.note("FinishedDownloadDeletion.playbackPlaying")
        val waiting = pending ?: return
        if (!waiting.untilOtherPlays || waiting.videoId == videoId) return
        if (session?.videoId != videoId) return
        schedule(waiting.copy(untilOtherPlays = false), delayMs = 0L)
    }

    fun playbackEnded(videoId: String, persistsProgress: Boolean = true) {
        if (!allowsProgressWrite(persistsProgress)) return
        ProgressWriteTrace.note("FinishedDownloadDeletion.playbackEnded")
        val current = session ?: return
        if (current.videoId != videoId) return
        notePlayed(current.videoId, current.queuedDownloadId)
        notePlayed(current.videoId, current.linkedDownloadId)
        notePlayed(current.videoId, current.incompleteDownloadId)
        notePlayed(current.videoId, downloadIdForSource(current.sourceUrl))
        session = null
        release(current, untilOtherPlays = false)
    }

    /**
     * Only Settings > Downloads > "delete when finished". Smart downloads used to count as on
     * here, so a finished episode was removed while that switch was off.
     */
    private fun deletionEnabled(): Boolean =
        DownloadsSettingsRepository.isDeleteWhenFinishedEnabled()

    /**
     * Watched-history completion (90 % locally, 80 % from Trakt/MDBList, or a remote pull). It no
     * longer queues anything: that marked episodes watched ~10 minutes before the end, and closing
     * the player then deleted the file. See [onLocalProgress].
     */
    @Suppress("UNUSED_PARAMETER")
    fun onPlaybackCompleted(entry: WatchProgressEntry) = Unit

    /**
     * Every progress report of the in-app player, with the real position (not snapped to the
     * duration). Queues the file once the position is at the credits or in the last two minutes
     * ([PlaybackEndRules.reachedEnd]), unqueues it when the viewer goes back before that, and moves
     * a Finish a series goal on near the end. The next-episode download is scheduled from playback.
     */
    fun onLocalProgress(
        entry: WatchProgressEntry,
        positionMs: Long,
        durationMs: Long,
        persistsProgress: Boolean = true,
    ) {
        if (!allowsProgressWrite(persistsProgress)) return
        ProgressWriteTrace.note("FinishedDownloadDeletion.onLocalProgress")
        notePlayed(entry.videoId, null)
        val current = session ?: return
        if (entry.videoId != current.videoId) return
        val items = DownloadsRepository.uiState.value.items
        val item = current.queuedDownloadId?.let { id -> items.firstOrNull { it.id == id } }
            ?: current.incompleteDownloadId?.let { id -> items.firstOrNull { it.id == id } }
            ?: downloadOpenedBySource(current.sourceUrl, items)
            ?: current.linkedDownloadId?.let { id -> items.firstOrNull { it.id == id } }
        item?.let { notePlayed(entry.videoId, it.id) }
        val creditsStartMs = item?.let { creditsStartFor(it) }
        val near = PlaybackEndRules.nearEnd(positionMs, durationMs, creditsStartMs)
        if (near) SmartDownloads.onNearEnd(entry, persistsProgress = persistsProgress)
        val tracked = current.copy(nearEnd = near)
        session = tracked
        val ended = PlaybackEndRules.reachedEnd(positionMs, durationMs, creditsStartMs)
        if (!ended) {
            if (tracked.queuedDownloadId != null || tracked.incompleteDownloadId != null) {
                // Went back into the episode: keep the file.
                session = tracked.copy(queuedDownloadId = null, incompleteDownloadId = null)
            }
            return
        }
        if (tracked.queuedDownloadId != null) return
        queueFor(tracked)?.let { session = it }
    }

    /** The session with its download queued for removal, or null when there is nothing to queue. */
    private fun queueFor(current: OpenPlayback): OpenPlayback? {
        if (!deletionEnabled()) return null
        val items = DownloadsRepository.uiState.value.items
        val item = current.incompleteDownloadId?.let { id -> items.firstOrNull { it.id == id } }
            ?: downloadOpenedBySource(current.sourceUrl, items)
            ?: current.linkedDownloadId?.let { id -> items.firstOrNull { it.id == id } }
            ?: return null
        return if (item.status == DownloadStatus.Completed) {
            current.copy(queuedDownloadId = item.id, incompleteDownloadId = null)
        } else {
            current.copy(incompleteDownloadId = item.id)
        }
    }

    private fun creditsStartFor(item: DownloadItem): Long? = runCatching {
        com.nuvio.app.features.analysis.MediaAnalysisStore
            .load(com.nuvio.app.features.analysis.MediaAnalysisStore.keyFor(item))
            ?.creditsStartMs
    }.getOrNull()

    /**
     * Storage autopilot must leave this file alone: it is transferring, on screen, or was played
     * in this process. "Delete when finished" is the only path that removes the one just played.
     */
    fun blocksStorageAutopilot(item: DownloadItem): Boolean {
        if (item.status == DownloadStatus.Downloading || item.status == DownloadStatus.Queued) return true
        if (isOpen(item)) return true
        val waiting = pending
        if (waiting != null && (waiting.downloadId == item.id || waiting.videoId == item.videoId)) return true
        val open = session
        if (open != null && (open.queuedDownloadId == item.id || open.incompleteDownloadId == item.id)) return true
        return synchronized(playedLock) {
            item.videoId in playedVideoIds || item.id in playedDownloadIds
        }
    }

    /** [item] is the video in the player now (never deleted from under it). */
    fun isOpen(item: DownloadItem): Boolean {
        val open = session ?: return false
        return open.videoId == item.videoId || open.linkedDownloadId == item.id ||
            downloadOpenedBySource(open.sourceUrl, listOf(item)) != null
    }

    /** A watched file is queued or waiting to be deleted (smart downloads wait for its space). */
    fun hasPendingDeletion(): Boolean =
        deletionEnabled() && (pending != null || session?.queuedDownloadId != null)

    internal fun reset() {
        session = null
        pending = null
        pendingJob?.cancel()
        pendingJob = null
        holdCapJob?.cancel()
        holdCapJob = null
        deletionAnalyserSupported = null
        deletionStoredAnalysis = null
        analysisHoldCapMs = ANALYSIS_HOLD_CAP_MS
        deletionNowMs = { epochMs() }
        deletionDeviceCharging = null
        deletionOnlyWhileCharging = null
        clearPersistedHold()
        synchronized(playedLock) {
            playedVideoIds.clear()
            playedDownloadIds.clear()
        }
    }

    internal fun discardMemoryForRestart() {
        pendingJob?.cancel()
        pendingJob = null
        holdCapJob?.cancel()
        holdCapJob = null
        pending = null
        session = null
    }

    fun analysisSettled(downloadId: String) {
        val current = pending ?: return
        if (current.downloadId != downloadId || current.heldSinceEpochMs == null) return
        execute(current.copy(releaseHold = true))
    }

    internal fun reevaluateHeldDeletion() {
        val current = pending ?: return
        if (current.heldSinceEpochMs == null) return
        execute(current)
    }

    internal fun onDownloadRemoved(downloadId: String) {
        val current = pending ?: return
        if (current.downloadId != downloadId) return
        drop(current)
    }

    internal fun resumeAfterLaunch() {
        val stored = loadPersistedHold() ?: return
        val current = pending
        if (current != null && current.downloadId == stored.downloadId && current.heldSinceEpochMs != null) return
        if (stored.generation > generationClock) generationClock = stored.generation
        pending = PendingDeletion(
            downloadId = stored.downloadId,
            videoId = stored.videoId,
            requireCompleted = stored.requireCompleted,
            untilOtherPlays = false,
            generation = stored.generation,
            heldSinceEpochMs = stored.heldSinceEpochMs,
            holdEligibleMs = stored.holdEligibleMs,
            holdAccountedUntilEpochMs = stored.holdAccountedUntilEpochMs,
            holdClockFrozen = stored.holdClockFrozen,
        )
        execute(pending!!)
    }

    private fun notePlayed(videoId: String?, downloadId: String?) {
        val video = videoId?.trim().orEmpty()
        val download = downloadId?.trim().orEmpty()
        if (video.isEmpty() && download.isEmpty()) return
        synchronized(playedLock) {
            if (video.isNotEmpty()) playedVideoIds += video
            if (download.isNotEmpty()) playedDownloadIds += download
        }
    }

    private fun downloadIdForSource(sourceUrl: String): String? = runCatching {
        downloadOpenedBySource(sourceUrl, DownloadsRepository.uiState.value.items)?.id
    }.getOrNull()

    private fun release(current: OpenPlayback, untilOtherPlays: Boolean) {
        if (session === current) session = null
        if (!deletionEnabled()) return
        val downloadId = current.queuedDownloadId ?: current.incompleteDownloadId ?: return
        val deletion = PendingDeletion(
            downloadId = downloadId,
            videoId = current.videoId,
            requireCompleted = current.queuedDownloadId == null,
            untilOtherPlays = untilOtherPlays,
            generation = nextGeneration(),
        )
        if (untilOtherPlays) {
            pendingJob?.cancel()
            holdCapJob?.cancel()
            holdCapJob = null
            pending = deletion
        } else {
            schedule(deletion, closeGraceMs)
        }
    }

    private fun schedule(deletion: PendingDeletion, delayMs: Long) {
        pendingJob?.cancel()
        holdCapJob?.cancel()
        holdCapJob = null
        pending = deletion
        if (delayMs <= 0L) {
            pendingJob = null
            execute(deletion)
            return
        }
        pendingJob = scope.launch {
            delay(delayMs)
            execute(deletion)
        }
    }

    private fun execute(deletion: PendingDeletion) {
        val current = pending ?: return
        if (current.generation != deletion.generation) return
        if (!deletionEnabled()) {
            drop(current)
            return
        }
        val item = DownloadsRepository.uiState.value.items.firstOrNull { it.id == current.downloadId }
        if (item == null) {
            drop(current)
            return
        }
        if (current.requireCompleted && item.status != DownloadStatus.Completed) {
            drop(current)
            return
        }
        // Never the episode on screen, whatever path got here.
        if (session?.videoId == current.videoId || isOpen(item)) {
            drop(current)
            return
        }
        if (!deletion.releaseHold) {
            val accounted = accountPendingHold(current)
            pending = accounted
            if (shouldHold(item, accounted)) {
                keepHold(accounted, item)
                return
            }
        }
        drop(current)
        DownloadsRepository.cancelDownload(current.downloadId, reason = "finished-toggle")
    }

    private fun shouldHold(item: DownloadItem, current: PendingDeletion): Boolean {
        val stored = storedAnalysis(item)
        return shouldHoldAutomaticDeletion(
            item = item,
            storedAnalysis = stored,
            introDetectionEnabled = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection),
            analyserSupported = deletionAnalyserSupported ?: MediaAnalyzerPlatform.supported,
            automatic = true,
            heldForMs = current.holdEligibleMs,
            holdCapMs = analysisHoldCapMs,
            attemptsExhausted = (stored?.analysisAttempts ?: 0) >= ANALYSIS_MAX_ATTEMPTS,
        )
    }

    private fun holdClockFrozenNow(): Boolean = analysisHoldClockFrozen(
        onlyWhileCharging = deletionOnlyWhileCharging?.invoke()
            ?: MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.OnlyWhileCharging),
        deviceCharging = deletionDeviceCharging?.invoke() ?: MediaAnalyzerPlatform.isCharging(),
    )

    private fun accountPendingHold(current: PendingDeletion): PendingDeletion {
        val now = deletionNowMs()
        val clock = accountHoldClock(
            clock = HoldClock(
                eligibleMs = current.holdEligibleMs,
                accountedUntilEpochMs = current.holdAccountedUntilEpochMs,
                frozen = current.holdClockFrozen,
            ),
            nowMs = now,
            frozenNow = holdClockFrozenNow(),
            heldSinceEpochMs = current.heldSinceEpochMs,
        )
        return current.copy(
            heldSinceEpochMs = current.heldSinceEpochMs ?: now,
            holdEligibleMs = clock.eligibleMs,
            holdAccountedUntilEpochMs = clock.accountedUntilEpochMs,
            holdClockFrozen = clock.frozen,
        )
    }

    private fun storedAnalysis(item: DownloadItem): MediaAnalysisResult? {
        deletionStoredAnalysis?.let { return it(item) }
        return runCatching { MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item)) }.getOrNull()
    }

    private fun keepHold(current: PendingDeletion, item: DownloadItem) {
        pending = current
        persistHold(current)
        if (holdCapJob?.isActive == true) return
        com.nuvio.app.features.analysis.MediaAnalysisScheduler.prioritiseForPendingDeletion(item)
        val remaining = analysisHoldCapMs - current.holdEligibleMs
        if (!current.holdClockFrozen && remaining <= 0L) {
            execute(current.copy(releaseHold = true))
            return
        }
        val generation = current.generation
        val wait = if (current.holdClockFrozen) {
            ANALYSIS_HOLD_RECHECK_MS
        } else {
            remaining.coerceAtMost(ANALYSIS_HOLD_RECHECK_MS).coerceAtLeast(1L)
        }
        holdCapJob = scope.launch {
            delay(wait)
            if (holdCapJob === kotlin.coroutines.coroutineContext[Job]) holdCapJob = null
            val latest = pending
            if (latest != null && latest.generation == generation) execute(latest)
        }
    }

    private fun drop(current: PendingDeletion) {
        if (pending?.generation == current.generation) pending = null
        pendingJob?.cancel()
        pendingJob = null
        holdCapJob?.cancel()
        holdCapJob = null
        val stored = loadPersistedHold()
        if (stored == null || stored.downloadId == current.downloadId) clearPersistedHold()
    }

    private fun nextGeneration(): Int = ++generationClock

    /** The same video (or the same file) is open again: keep its file. */
    private fun cancelPendingFor(videoId: String, sourceUrl: String) {
        val waiting = pending ?: return
        val sameFile = DownloadsRepository.uiState.value.items
            .firstOrNull { it.id == waiting.downloadId }
            ?.let { downloadOpenedBySource(sourceUrl, listOf(it)) != null } == true
        if (waiting.videoId != videoId && !sameFile) return
        pendingJob?.cancel()
        pendingJob = null
        holdCapJob?.cancel()
        holdCapJob = null
        pending = null
        val stored = loadPersistedHold()
        if (stored == null || stored.downloadId == waiting.downloadId) clearPersistedHold()
        // Back on the same episode: it is still watched, so closing again queues it again.
        val current = session
        if (current == null || current.videoId != videoId) {
            session = OpenPlayback(
                videoId = videoId,
                sourceUrl = sourceUrl,
                queuedDownloadId = waiting.downloadId.takeUnless { waiting.requireCompleted },
                incompleteDownloadId = waiting.downloadId.takeIf { waiting.requireCompleted },
            )
        }
    }
}

private data class PendingDeletion(
    val downloadId: String,
    val videoId: String,
    val requireCompleted: Boolean,
    val untilOtherPlays: Boolean,
    val generation: Int,
    val heldSinceEpochMs: Long? = null,
    val releaseHold: Boolean = false,
    val holdEligibleMs: Long = 0L,
    val holdAccountedUntilEpochMs: Long? = null,
    val holdClockFrozen: Boolean = false,
)

private const val ANALYSIS_HOLD_KEY = "finished_download_analysis_hold"

@Serializable
private data class PersistedAnalysisHold(
    val downloadId: String,
    val videoId: String,
    val requireCompleted: Boolean,
    val heldSinceEpochMs: Long,
    val generation: Int,
    val holdEligibleMs: Long = 0L,
    val holdAccountedUntilEpochMs: Long? = null,
    val holdClockFrozen: Boolean = false,
)

private fun persistHold(deletion: PendingDeletion) {
    val since = deletion.heldSinceEpochMs ?: return
    val payload = PersistedAnalysisHold(
        downloadId = deletion.downloadId,
        videoId = deletion.videoId,
        requireCompleted = deletion.requireCompleted,
        heldSinceEpochMs = since,
        generation = deletion.generation,
        holdEligibleMs = deletion.holdEligibleMs,
        holdAccountedUntilEpochMs = deletion.holdAccountedUntilEpochMs,
        holdClockFrozen = deletion.holdClockFrozen,
    )
    DownloadsSettingsStorage.saveString(
        ANALYSIS_HOLD_KEY,
        LenientDefaultsJson.encodeToString(PersistedAnalysisHold.serializer(), payload),
    )
}

private fun loadPersistedHold(): PersistedAnalysisHold? {
    val text = DownloadsSettingsStorage.loadString(ANALYSIS_HOLD_KEY) ?: return null
    return runCatching { LenientDefaultsJson.decodeFromString(PersistedAnalysisHold.serializer(), text) }.getOrNull()
}

private fun clearPersistedHold() {
    if (DownloadsSettingsStorage.loadString(ANALYSIS_HOLD_KEY) == null) return
    DownloadsSettingsStorage.saveString(ANALYSIS_HOLD_KEY, null)
}

private data class OpenPlayback(
    val videoId: String,
    val sourceUrl: String,
    val queuedDownloadId: String? = null,
    /** Watched to the end while still downloading: removed on leave only if it finished by then. */
    val incompleteDownloadId: String? = null,
    /** Downloaded while watching and switched to (see [FinishedDownloadDeletion.linkDownload]). */
    val linkedDownloadId: String? = null,
    /** Last progress report was within the near-end window (credits or last 5 minutes). */
    val nearEnd: Boolean = false,
)

internal fun downloadOpenedBySource(sourceUrl: String, items: List<DownloadItem>): DownloadItem? {
    val trimmed = sourceUrl.trim()
    if (trimmed.isEmpty()) return null
    partialPlaybackDownloadId(trimmed)?.let { downloadId ->
        return items.firstOrNull { it.id == downloadId }
    }
    val sourceKey = fileIdentity(trimmed)
    return items.firstOrNull { item ->
        val localUri = item.localFileUri?.trim().orEmpty()
        localUri.isNotEmpty() && fileIdentity(localUri) == sourceKey
    }
}

internal fun fileIdentity(value: String): String {
    val trimmed = value.trim()
    val path = when {
        trimmed.startsWith("file://") -> trimmed.removePrefix("file://")
        trimmed.startsWith("file:") -> trimmed.removePrefix("file:")
        else -> return trimmed
    }
    return path.trimEnd('/')
}
