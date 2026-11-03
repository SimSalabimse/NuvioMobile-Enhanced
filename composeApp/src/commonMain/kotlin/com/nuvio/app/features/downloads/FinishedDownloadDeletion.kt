package com.nuvio.app.features.downloads

import com.nuvio.app.core.serialization.LenientDefaultsJson
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.MediaAnalysisSettings
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.analysis.MediaAnalyzerPlatform
import com.nuvio.app.features.streams.epochMs
import com.nuvio.app.features.watchprogress.WatchProgressEntry
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
 */
internal object FinishedDownloadDeletion {
    private var session: OpenPlayback? = null
    private var pending: PendingDeletion? = null
    private var pendingJob: Job? = null
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

    private var generationClock = 0
    private var holdCapJob: Job? = null

    fun playbackOpened(videoId: String, sourceUrl: String) {
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

    fun playbackSourceChanged(videoId: String, sourceUrl: String) {
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
    fun linkDownload(videoId: String, downloadId: String) {
        val current = session ?: return
        if (current.videoId != videoId) return
        session = current.copy(linkedDownloadId = downloadId)
    }

    /** The player is showing frames of [videoId]: a previous episode waiting for that may go. */
    fun playbackPlaying(videoId: String) {
        val waiting = pending ?: return
        if (!waiting.untilOtherPlays || waiting.videoId == videoId) return
        if (session?.videoId != videoId) return
        schedule(waiting.copy(untilOtherPlays = false), delayMs = 0L)
    }

    fun playbackEnded(videoId: String) {
        val current = session ?: return
        if (current.videoId != videoId) return
        session = null
        release(current, untilOtherPlays = false)
    }

    /** Delete after watching, or smart downloads (which replace a watched episode with the next ones). */
    private fun deletionEnabled(): Boolean =
        DownloadsSettingsRepository.isDeleteWhenFinishedEnabled() || SmartDownloads.isEnabled()

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
    fun onLocalProgress(entry: WatchProgressEntry, positionMs: Long, durationMs: Long) {
        val current = session ?: return
        if (entry.videoId != current.videoId) return
        val items = DownloadsRepository.uiState.value.items
        val item = current.queuedDownloadId?.let { id -> items.firstOrNull { it.id == id } }
            ?: current.incompleteDownloadId?.let { id -> items.firstOrNull { it.id == id } }
            ?: downloadOpenedBySource(current.sourceUrl, items)
            ?: current.linkedDownloadId?.let { id -> items.firstOrNull { it.id == id } }
        val creditsStartMs = item?.let { creditsStartFor(it) }
        val near = PlaybackEndRules.nearEnd(positionMs, durationMs, creditsStartMs)
        if (near) SmartDownloads.onNearEnd(entry)
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
        clearPersistedHold()
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
        )
        execute(pending!!)
    }

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
        if (!deletion.releaseHold && shouldHold(item, current)) {
            keepHold(current, item)
            return
        }
        drop(current)
        DownloadsRepository.cancelDownload(current.downloadId)
    }

    private fun shouldHold(item: DownloadItem, current: PendingDeletion): Boolean {
        val since = current.heldSinceEpochMs
        val heldForMs = if (since == null) 0L else (deletionNowMs() - since).coerceAtLeast(0L)
        return shouldHoldAutomaticDeletion(
            item = item,
            storedAnalysis = storedAnalysis(item),
            introDetectionEnabled = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection),
            analyserSupported = deletionAnalyserSupported ?: MediaAnalyzerPlatform.supported,
            automatic = true,
            heldForMs = heldForMs,
            holdCapMs = analysisHoldCapMs,
        )
    }

    private fun storedAnalysis(item: DownloadItem): MediaAnalysisResult? {
        deletionStoredAnalysis?.let { return it(item) }
        return runCatching { MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item)) }.getOrNull()
    }

    private fun keepHold(current: PendingDeletion, item: DownloadItem) {
        val since = current.heldSinceEpochMs ?: deletionNowMs()
        val updated = current.copy(heldSinceEpochMs = since)
        pending = updated
        persistHold(updated)
        if (holdCapJob?.isActive == true) return
        com.nuvio.app.features.analysis.MediaAnalysisScheduler.prioritiseForPendingDeletion(item)
        val remaining = analysisHoldCapMs - (deletionNowMs() - since)
        if (remaining <= 0L) {
            execute(updated.copy(releaseHold = true))
            return
        }
        val generation = updated.generation
        holdCapJob = scope.launch {
            delay(remaining)
            if (holdCapJob === coroutineContext[Job]) holdCapJob = null
            val latest = pending
            if (latest != null && latest.generation == generation) {
                execute(latest.copy(releaseHold = true))
            }
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
)

private const val ANALYSIS_HOLD_KEY = "finished_download_analysis_hold"

@Serializable
private data class PersistedAnalysisHold(
    val downloadId: String,
    val videoId: String,
    val requireCompleted: Boolean,
    val heldSinceEpochMs: Long,
    val generation: Int,
)

private fun persistHold(deletion: PendingDeletion) {
    val since = deletion.heldSinceEpochMs ?: return
    val payload = PersistedAnalysisHold(
        downloadId = deletion.downloadId,
        videoId = deletion.videoId,
        requireCompleted = deletion.requireCompleted,
        heldSinceEpochMs = since,
        generation = deletion.generation,
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
