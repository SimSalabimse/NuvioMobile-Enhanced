package com.nuvio.app.features.downloads

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
     * ([PlaybackEndRules.reachedEnd]), unqueues it when the viewer goes back before that, and asks
     * smart downloads for the next episodes near the end.
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
        SmartDownloads.onProgress(entry, positionMs, durationMs, creditsStartMs)
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

    internal fun reset() {
        session = null
        pending = null
        pendingJob?.cancel()
        pendingJob = null
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
        )
        if (untilOtherPlays) {
            pendingJob?.cancel()
            pending = deletion
        } else {
            schedule(deletion, closeGraceMs)
        }
    }

    private fun schedule(deletion: PendingDeletion, delayMs: Long) {
        pendingJob?.cancel()
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
        if (pending !== deletion) return
        pending = null
        pendingJob = null
        if (!deletionEnabled()) return
        val item = DownloadsRepository.uiState.value.items.firstOrNull { it.id == deletion.downloadId } ?: return
        if (deletion.requireCompleted && item.status != DownloadStatus.Completed) return
        // Never the episode on screen, whatever path got here.
        val open = session
        if (open != null) {
            if (open.videoId == deletion.videoId) return
            if (open.linkedDownloadId == deletion.downloadId) return
            if (downloadOpenedBySource(open.sourceUrl, listOf(item)) != null) return
        }
        DownloadsRepository.cancelDownload(deletion.downloadId)
    }

    /** The same video (or the same file) is open again: keep its file. */
    private fun cancelPendingFor(videoId: String, sourceUrl: String) {
        val waiting = pending ?: return
        val sameFile = DownloadsRepository.uiState.value.items
            .firstOrNull { it.id == waiting.downloadId }
            ?.let { downloadOpenedBySource(sourceUrl, listOf(it)) != null } == true
        if (waiting.videoId != videoId && !sameFile) return
        pendingJob?.cancel()
        pendingJob = null
        pending = null
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
)

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
