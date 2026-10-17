package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A finished download the autopilot may delete: watched, not in progress, not open in the player. */
internal data class AutopilotCandidate(val id: String, val bytes: Long, val finishedAtEpochMs: Long)

/** Oldest first until free space reaches [keepFreeBytes]; nothing when it already does. */
internal fun autopilotPicks(candidates: List<AutopilotCandidate>, freeBytes: Long, keepFreeBytes: Long): List<String> {
    var missing = keepFreeBytes - freeBytes
    if (missing <= 0L) return emptyList()
    return candidates.sortedBy { it.finishedAtEpochMs }
        .takeWhile { candidate -> (missing > 0L).also { missing -= candidate.bytes } }
        .map { it.id }
}

/**
 * Storage autopilot ("Keep X GB free"): when free space drops below the setting, watched downloads
 * go, oldest first. Unwatched and partly watched ones are never touched. Runs at launch, after a
 * download finishes, when the setting changes and when Smart Downloads run out of room.
 */
internal object StorageAutopilot {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    fun run() {
        val keepFreeBytes = SmartDownloadSettings.snapshot().keepFreeGb * 1_000_000_000L
        if (keepFreeBytes <= 0L) return
        scope.launch {
            mutex.withLock {
                val free = DownloadDiskInfo.volumeSpace()?.availableBytes ?: return@withLock
                if (free >= keepFreeBytes) return@withLock
                val candidates = DownloadsRepository.uiState.value.items
                    .filter { it.status == DownloadStatus.Completed && isWatched(it) && !isInProgress(it) }
                    .filterNot { FinishedDownloadDeletion.isOpen(it) }
                    .map { AutopilotCandidate(it.id, it.totalBytes ?: it.downloadedBytes, it.updatedAtEpochMs) }
                val picks = autopilotPicks(candidates, free, keepFreeBytes)
                if (picks.isEmpty()) return@withLock
                val freed = candidates.filter { it.id in picks }.sumOf { it.bytes }
                withContext(Dispatchers.Main) {
                    DownloadsRepository.cancelDownloads(picks)
                    NuvioToastController.show("Storage autopilot freed ${formatDownloadBytes(freed)} of watched downloads")
                }
            }
        }
    }

    private fun isWatched(item: DownloadItem): Boolean = runCatching {
        WatchedRepository.isWatched(
            id = item.parentMetaId,
            type = item.parentMetaType,
            season = item.seasonNumber,
            episode = item.episodeNumber,
        )
    }.getOrDefault(false)

    private fun isInProgress(item: DownloadItem): Boolean = runCatching {
        WatchProgressRepository.progressForVideo(item.videoId, item.parentMetaId, item.seasonNumber, item.episodeNumber)
            ?.let { !it.isCompleted && it.lastPositionMs > 0L } == true
    }.getOrDefault(true)
}
