package com.nuvio.app.features.downloads

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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

    fun playbackOpened(videoId: String, sourceUrl: String) {
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

    fun playbackSourceChanged(videoId: String, sourceUrl: String) {
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
    fun linkDownload(videoId: String, downloadId: String) {
        notePlayed(videoId, downloadId)
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
     * ([PlaybackEndRules.reachedEnd]), unqueues it when the viewer goes back before that, and asks
     * smart downloads for the next episodes near the end.
     */
    fun onLocalProgress(entry: WatchProgressEntry, positionMs: Long, durationMs: Long) {
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
        synchronized(playedLock) {
            playedVideoIds.clear()
            playedDownloadIds.clear()
        }
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
        if (session?.videoId == deletion.videoId || isOpen(item)) return
        DownloadsRepository.cancelDownload(deletion.downloadId, reason = "finished-toggle")
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
