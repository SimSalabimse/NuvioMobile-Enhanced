package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository

/** Watched (check) or part-watched (0..1 progress) for a downloaded episode or movie. */
internal data class DownloadWatchState(val watched: Boolean, val progress: Float?)

internal fun downloadWatchState(item: DownloadItem): DownloadWatchState {
    val watched = runCatching {
        WatchedRepository.isWatched(
            id = item.parentMetaId,
            type = item.parentMetaType.ifBlank { item.contentType },
            season = item.seasonNumber,
            episode = item.episodeNumber,
        )
    }.getOrDefault(false)
    val progress = runCatching {
        WatchProgressRepository.progressForVideo(
            videoId = item.videoId,
            parentMetaId = item.parentMetaId,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
        )
    }.getOrNull()
    if (watched || progress?.isEffectivelyCompleted == true) return DownloadWatchState(true, null)
    return DownloadWatchState(false, progress?.progressFraction?.takeIf { it > 0.01f })
}

/** Recomputes when watched marks or progress change. */
@Composable
internal fun rememberDownloadWatchState(item: DownloadItem): DownloadWatchState {
    val watched by WatchedRepository.uiState.collectAsState()
    val progress by WatchProgressRepository.uiState.collectAsState()
    return remember(item.id, watched, progress) { downloadWatchState(item) }
}
