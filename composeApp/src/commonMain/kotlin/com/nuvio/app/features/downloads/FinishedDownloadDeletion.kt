package com.nuvio.app.features.downloads

import com.nuvio.app.features.watchprogress.WatchProgressEntry

/**
 * Queues removal of a finished download when in-app playback completes, and
 * runs [DownloadsRepository.cancelDownload] only after that playback leaves the file.
 *
 * A transfer that is still running when completion first fires is not removed,
 * including an early-play session that crosses the threshold before the bytes finish.
 * Scrubbing back inside the same video keeps the file until the player leaves it.
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
        if (current.queuedDownloadId != null || current.blockedIncomplete) return
        if (!DownloadsSettingsRepository.deleteWhenFinished.value) return
        val item = downloadOpenedBySource(current.sourceUrl, DownloadsRepository.uiState.value.items) ?: return
        session = if (item.status == DownloadStatus.Completed) {
            current.copy(queuedDownloadId = item.id)
        } else {
            current.copy(blockedIncomplete = true)
        }
    }

    internal fun reset() {
        session = null
    }

    private fun release(current: OpenPlayback) {
        session = null
        val downloadId = current.queuedDownloadId ?: return
        if (!DownloadsSettingsRepository.deleteWhenFinished.value) return
        DownloadsRepository.cancelDownload(downloadId)
    }
}

private data class OpenPlayback(
    val videoId: String,
    val sourceUrl: String,
    val queuedDownloadId: String? = null,
    val blockedIncomplete: Boolean = false,
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
