package com.nuvio.app.features.downloads

import com.nuvio.app.features.watchprogress.WatchProgressEntry

/**
 * Queues removal of a finished download when in-app playback completes, and
 * runs [DownloadsRepository.cancelDownload] only after that playback leaves the file.
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

    fun playbackOpened(videoId: String, sourceUrl: String) {
        val current = session
        if (current != null && current.videoId == videoId) {
            session = current.copy(sourceUrl = sourceUrl)
            return
        }
        if (current != null) release(current)
        session = OpenPlayback(videoId = videoId, sourceUrl = sourceUrl)
    }

    fun playbackSourceChanged(videoId: String, sourceUrl: String) {
        val current = session ?: return
        if (current.videoId != videoId) return
        session = current.copy(sourceUrl = sourceUrl)
    }

    fun playbackEnded(videoId: String) {
        val current = session ?: return
        if (current.videoId != videoId) return
        release(current)
    }

    fun onPlaybackCompleted(entry: WatchProgressEntry) {
        val current = session ?: return
        if (!entry.isCompleted || entry.videoId != current.videoId) return
        if (current.queuedDownloadId != null) return
        if (!DownloadsSettingsRepository.isDeleteWhenFinishedEnabled()) return
        val items = DownloadsRepository.uiState.value.items
        val item = current.incompleteDownloadId?.let { id -> items.firstOrNull { it.id == id } }
            ?: downloadOpenedBySource(current.sourceUrl, items)
            ?: return
        session = if (item.status == DownloadStatus.Completed) {
            current.copy(queuedDownloadId = item.id, incompleteDownloadId = null)
        } else {
            current.copy(incompleteDownloadId = item.id)
        }
    }

    internal fun reset() {
        session = null
    }

    private fun release(current: OpenPlayback) {
        session = null
        if (!DownloadsSettingsRepository.isDeleteWhenFinishedEnabled()) return
        val downloadId = current.queuedDownloadId
            ?: current.incompleteDownloadId?.takeIf { id ->
                DownloadsRepository.uiState.value.items.any { it.id == id && it.status == DownloadStatus.Completed }
            }
            ?: return
        DownloadsRepository.cancelDownload(downloadId)
    }
}

private data class OpenPlayback(
    val videoId: String,
    val sourceUrl: String,
    val queuedDownloadId: String? = null,
    /** Watched to the end while still downloading: removed on leave only if it finished by then. */
    val incompleteDownloadId: String? = null,
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
