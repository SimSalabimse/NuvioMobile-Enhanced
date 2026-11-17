package com.nuvio.app.features.downloads

import com.nuvio.app.core.serialization.LenientDefaultsJson
import com.nuvio.app.features.analysis.ANALYSIS_MAX_ATTEMPTS
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.MediaAnalysisSettings
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.analysis.MediaAnalyzerPlatform
import com.nuvio.app.features.streams.epochMs
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.providerId
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktHistory
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceLocal
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
 *
 * The same switch also removes a finished download when another device has fully watched it.
 * [shouldDeleteFinishedDownloadForSyncedCompletion] is that decision. It accepts only a
 * completion applied from a successful sync pull in this process, or a history import that
 * carries a timestamp later than the download. Anything unclear keeps the file: the switch
 * off, a transfer still running, an analysis-temp download, a hold already waiting on the
 * intro fingerprint, a completion that is not fully watched, a missing source or timestamp,
 * a completion from before the download finished, local progress newer than the sync, or the
 * video on screen, in this player session, or played here inside the grace period. Local
 * playback does not enter this path. A removal that does start uses the same grace and the
 * intro-analysis hold, then [DownloadsRepository.cancelDownload].
 */
internal object FinishedDownloadDeletion {
    private var session: OpenPlayback? = null
    private var pending: PendingDeletion? = null
    private var pendingJob: Job? = null
    private val playedLock = SynchronizedObject()
    private val playedVideoIds = mutableSetOf<String>()
    private val playedDownloadIds = mutableSetOf<String>()
    private val playedAtEpochMs = mutableMapOf<String, Long>()
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

    /**
     * This branch has no automation-test switch. A harness sets this so a synced
     * completion cannot delete. Submit mode is refused by [allowsProgressWrite].
     */
    internal var blockSyncedDeletion: () -> Boolean = { false }

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
     * Local playback and a direct watched-history callback. Not a sync pull, so it queues
     * nothing. [onLocalProgress] owns this device. Another device is [onSyncedCompletions],
     * and only after a pull in this process has applied the completion. Calling that path
     * from here deleted the file twice, including while it was still on screen.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onPlaybackCompleted(entry: WatchProgressEntry) = Unit

    /**
     * Completions just applied from a successful sync pull or a history import.
     * One file at a time, through the same grace and analysis hold as local playback.
     * A failure keeps every file and does not fail the sync that called this.
     */
    fun onSyncedCompletions(completions: List<SyncedWatchCompletion>, persistsProgress: Boolean = true) {
        if (!allowsProgressWrite(persistsProgress)) return
        if (completions.isEmpty()) return
        runCatching { queueSyncedCompletions(completions, persistsProgress) }
    }

    /** Watched rows just written from a successful watched-state pull. */
    fun onSyncedWatchedItems(
        items: Collection<WatchedItem>,
        origin: SyncedWatchOrigin,
        source: String,
        persistsProgress: Boolean = true,
    ) {
        onSyncedCompletions(
            completions = items.mapNotNull { it.toSyncedWatchCompletion(origin, source) },
            persistsProgress = persistsProgress,
        )
    }

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

    private fun queueSyncedCompletions(completions: List<SyncedWatchCompletion>, persistsProgress: Boolean) {
        if (blockSyncedDeletion()) return
        if (!deletionEnabled()) return
        ProgressWriteTrace.note("FinishedDownloadDeletion.onSyncedCompletions")
        if (pending != null) return
        val items = DownloadsRepository.uiState.value.items
        for (completion in completions) {
            for (item in items) {
                if (!syncedCompletionMatchesDownload(
                        downloadVideoId = item.videoId,
                        downloadParentMetaId = item.parentMetaId,
                        downloadSeasonNumber = item.seasonNumber,
                        downloadEpisodeNumber = item.episodeNumber,
                        completion = completion,
                    )
                ) {
                    continue
                }
                val holdActive = pending?.downloadId == item.id && pending?.heldSinceEpochMs != null
                val analysisTemp = runCatching { AnalysisTempDownloads.isTagged(item.id) }.getOrDefault(true)
                val open = session
                val delete = shouldDeleteFinishedDownloadForSyncedCompletion(
                    download = FinishedDownloadDeletionCandidate(
                        status = item.status,
                        videoId = item.videoId,
                        parentMetaId = item.parentMetaId,
                        seasonNumber = item.seasonNumber,
                        episodeNumber = item.episodeNumber,
                        finishedAtEpochMs = item.updatedAtEpochMs.takeIf { it > 0L },
                        analysisTemp = analysisTemp,
                        introAnalysisHoldActive = holdActive,
                    ),
                    completion = completion,
                    context = SyncedDeletionContext(
                        deleteWhenFinishedEnabled = true,
                        persistsProgress = persistsProgress,
                        automationTestMode = blockSyncedDeletion(),
                        videoOnScreen = isOpen(item),
                        inPlayerSession = open != null && (
                            open.videoId == item.videoId ||
                                open.linkedDownloadId == item.id ||
                                open.queuedDownloadId == item.id ||
                                open.incompleteDownloadId == item.id
                            ),
                        playedWithinGrace = playedWithinGrace(item),
                        localWatch = completion.localWatchBeforeApply ?: localWatchLookup(item),
                    ),
                )
                if (!delete) continue
                schedule(
                    PendingDeletion(
                        downloadId = item.id,
                        videoId = item.videoId,
                        requireCompleted = true,
                        untilOtherPlays = false,
                        generation = nextGeneration(),
                        fromSyncedWatch = true,
                    ),
                    closeGraceMs,
                )
                return
            }
        }
    }

    private fun localWatchLookup(item: DownloadItem): LocalWatchSnapshot? {
        val lookedUp = runCatching {
            WatchProgressRepository.progressForVideo(
                videoId = item.videoId,
                parentMetaId = item.parentMetaId,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
            )
        }
        if (lookedUp.isFailure) {
            return LocalWatchSnapshot(lastPositionMs = 1L, isCompleted = false, lastUpdatedEpochMs = null)
        }
        val entry = lookedUp.getOrNull() ?: return null
        if (entry.isCompleted || entry.lastPositionMs <= 0L) return null
        return entry.localWatchSnapshot()
    }

    private fun playedWithinGrace(item: DownloadItem): Boolean {
        val grace = closeGraceMs
        if (grace <= 0L) return false
        val marked = synchronized(playedLock) {
            maxOf(playedAtEpochMs[item.videoId] ?: 0L, playedAtEpochMs[item.id] ?: 0L)
        }
        if (marked <= 0L) return false
        return deletionNowMs() - marked < grace
    }

    /** The session with its download queued for removal, or null when there is nothing to queue. */
    private fun queueFor(current: OpenPlayback): OpenPlayback? {
        if (!deletionEnabled()) return null
        val items = DownloadsRepository.uiState.value.items
        val item = current.incompleteDownloadId?.let { id -> items.firstOrNull { it.id == id } }
            ?: downloadOpenedBySource(current.sourceUrl, items)
            ?: current.linkedDownloadId?.let { id -> items.firstOrNull { it.id == id } }
            ?: return null
        if (AnalysisTempDownloads.isTagged(item.id)) return null
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
        blockSyncedDeletion = { false }
        clearPersistedHold()
        synchronized(playedLock) {
            playedVideoIds.clear()
            playedDownloadIds.clear()
            playedAtEpochMs.clear()
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
        AnalysisTempDownloads.onAnalysisSettled(downloadId)
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
            fromSyncedWatch = stored.fromSyncedWatch,
        )
        execute(pending!!)
    }

    private fun notePlayed(videoId: String?, downloadId: String?) {
        val video = videoId?.trim().orEmpty()
        val download = downloadId?.trim().orEmpty()
        if (video.isEmpty() && download.isEmpty()) return
        val now = deletionNowMs()
        synchronized(playedLock) {
            if (video.isNotEmpty()) {
                playedVideoIds += video
                playedAtEpochMs[video] = now
            }
            if (download.isNotEmpty()) {
                playedDownloadIds += download
                playedAtEpochMs[download] = now
            }
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
        // A file queued because another device finished it is a rewatch once it is opened.
        if (waiting.fromSyncedWatch) return
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
    val fromSyncedWatch: Boolean = false,
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
    val fromSyncedWatch: Boolean = false,
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
        fromSyncedWatch = deletion.fromSyncedWatch,
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

/** Account-sync rows are stored without a remote source label. Callers pass this instead. */
internal const val SyncedWatchSourceAccount = "nuvio_sync"

/** Where a fully-watched signal came from. [SyncedWatchOrigin.Unclear] always keeps the file. */
internal enum class SyncedWatchOrigin {
    /** A sync pull that succeeded in this process. */
    AccountSyncPull,
    /** Trakt, MDBList, or the same kind of history. Trusted only with a real later timestamp. */
    HistoryImport,
    /** Launch cache, local playback, or any writer that did not just pull. */
    Unclear,
}

/** One watched signal from a pull. [fullyCompleted] is the remote completed flag, not a percent. */
internal data class SyncedWatchCompletion(
    val fullyCompleted: Boolean,
    val videoId: String,
    val parentMetaId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val completedAtEpochMs: Long?,
    val source: String?,
    val origin: SyncedWatchOrigin,
    /**
     * Account progress has no separate completed flag: the row counts only when the playhead
     * is at the end of a known runtime. History and watched marks leave this false.
     */
    val requiresPlayheadAtEnd: Boolean = false,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    /** The progress row this device had before the pull replaced it. Null when there was none. */
    val localWatchBeforeApply: LocalWatchSnapshot? = null,
)

/** Local continue-watching row. In progress is [lastPositionMs] > 0 and not completed. */
internal data class LocalWatchSnapshot(
    val lastPositionMs: Long,
    val isCompleted: Boolean,
    val lastUpdatedEpochMs: Long?,
)

internal data class FinishedDownloadDeletionCandidate(
    val status: DownloadStatus,
    val videoId: String,
    val parentMetaId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val finishedAtEpochMs: Long?,
    val analysisTemp: Boolean,
    val introAnalysisHoldActive: Boolean,
)

internal data class SyncedDeletionContext(
    val deleteWhenFinishedEnabled: Boolean,
    val persistsProgress: Boolean,
    val automationTestMode: Boolean,
    val videoOnScreen: Boolean,
    val inPlayerSession: Boolean,
    val playedWithinGrace: Boolean,
    val localWatch: LocalWatchSnapshot?,
)

/**
 * True only when a synced full watch should remove this finished download.
 * Every uncertain input keeps the file.
 */
internal fun shouldDeleteFinishedDownloadForSyncedCompletion(
    download: FinishedDownloadDeletionCandidate,
    completion: SyncedWatchCompletion,
    context: SyncedDeletionContext,
): Boolean {
    if (!context.deleteWhenFinishedEnabled) return false
    if (!allowsProgressWrite(context.persistsProgress)) return false
    if (context.automationTestMode) return false
    if (download.analysisTemp || download.introAnalysisHoldActive) return false
    if (download.status != DownloadStatus.Completed) return false
    if (context.videoOnScreen || context.inPlayerSession || context.playedWithinGrace) return false
    if (!completion.fullyCompleted) return false
    if (completion.requiresPlayheadAtEnd) {
        val duration = completion.durationMs
        val position = completion.positionMs
        if (duration == null || duration <= 0L || position == null || position < duration) return false
    }
    if (!completion.hasTrustedRemoteSource()) return false
    if (!syncedCompletionMatchesDownload(
            downloadVideoId = download.videoId,
            downloadParentMetaId = download.parentMetaId,
            downloadSeasonNumber = download.seasonNumber,
            downloadEpisodeNumber = download.episodeNumber,
            completion = completion,
        )
    ) {
        return false
    }
    val completedAt = completion.completedAtEpochMs ?: return false
    if (completedAt <= 0L) return false
    val finishedAt = download.finishedAtEpochMs ?: return false
    if (finishedAt <= 0L || completedAt <= finishedAt) return false
    val local = context.localWatch
    if (local != null && local.lastPositionMs > 0L && !local.isCompleted) {
        val localUpdated = local.lastUpdatedEpochMs ?: return false
        if (completedAt <= localUpdated) return false
    }
    return true
}

internal fun syncedCompletionMatchesDownload(
    downloadVideoId: String,
    downloadParentMetaId: String,
    downloadSeasonNumber: Int?,
    downloadEpisodeNumber: Int?,
    completion: SyncedWatchCompletion,
): Boolean {
    val completionVideo = completion.videoId.trim()
    val downloadVideo = downloadVideoId.trim()
    if (completionVideo.isNotEmpty() && completionVideo == downloadVideo) return true
    val completionIsEpisode = completion.seasonNumber != null && completion.episodeNumber != null
    val downloadIsEpisode = downloadSeasonNumber != null && downloadEpisodeNumber != null
    if (completionIsEpisode && downloadIsEpisode) {
        val parent = completion.parentMetaId.trim()
        return parent.isNotEmpty() &&
            parent == downloadParentMetaId.trim() &&
            completion.seasonNumber == downloadSeasonNumber &&
            completion.episodeNumber == downloadEpisodeNumber
    }
    if (!completionIsEpisode && !downloadIsEpisode) {
        val parent = completion.parentMetaId.trim()
        return parent.isNotEmpty() && parent == downloadParentMetaId.trim()
    }
    return false
}

internal fun WatchProgressEntry.localWatchSnapshot(): LocalWatchSnapshot = LocalWatchSnapshot(
    lastPositionMs = lastPositionMs,
    isCompleted = isCompleted,
    lastUpdatedEpochMs = lastUpdatedEpochMs.takeIf { it > 0L },
)

internal fun WatchedItem.toSyncedWatchCompletion(
    origin: SyncedWatchOrigin,
    source: String,
): SyncedWatchCompletion? {
    val isEpisodeRow = season != null && episode != null
    val movie = type.equals("movie", ignoreCase = true)
    if (!isEpisodeRow && !movie) return null
    val video = videoId?.trim().orEmpty().ifEmpty { if (movie) id.trim() else "" }
    return SyncedWatchCompletion(
        fullyCompleted = true,
        videoId = video,
        parentMetaId = id,
        seasonNumber = if (isEpisodeRow) season else null,
        episodeNumber = if (isEpisodeRow) episode else null,
        completedAtEpochMs = markedAtEpochMs.takeIf { it > 0L },
        source = source,
        origin = origin,
    )
}

internal fun WatchProgressSource.syncedDeletionOrigin(): SyncedWatchOrigin =
    if (providerId == null) SyncedWatchOrigin.AccountSyncPull else SyncedWatchOrigin.HistoryImport

internal fun WatchProgressSource.syncedDeletionSourceLabel(): String = when (this) {
    WatchProgressSource.NUVIO_SYNC -> SyncedWatchSourceAccount
    WatchProgressSource.TRAKT -> WatchProgressSourceTraktHistory
    WatchProgressSource.SIMKL -> "simkl_history"
    WatchProgressSource.MDBLIST -> "mdblist_history"
    WatchProgressSource.ANILIST -> "anilist_history"
    WatchProgressSource.WETRAKR -> "wetrakr_history"
    WatchProgressSource.PUBLICMETADB -> "publicmetadb_history"
}

private fun SyncedWatchCompletion.hasTrustedRemoteSource(): Boolean {
    val value = source?.trim().orEmpty()
    if (value.isEmpty() || value.equals(WatchProgressSourceLocal, ignoreCase = true)) return false
    return origin == SyncedWatchOrigin.AccountSyncPull || origin == SyncedWatchOrigin.HistoryImport
}
