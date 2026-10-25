package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.downloads.DownloadEnqueueService
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadTarget
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.downloadLogicalContentKey
import com.nuvio.app.features.downloads.isLoopbackPartialPlaybackUrl
import com.nuvio.app.features.downloads.isSupportedDownloadUrl
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders

/**
 * Download while watching. Queues the item on screen from the exact stream being played (addon,
 * title, bingeGroup and headers, so the expired-link refresh can find the same source again).
 * Playback is not touched: the player keeps the stream, and the next play uses the file.
 * Hidden for local files, early play, torrents, live TV and anything that is not downloadable.
 */
internal fun PlayerScreenRuntime.playerDownloadActionAvailable(): Boolean {
    if (!isDownloadablePlayback) return false
    if (isPlayingSwappedDownload) return true
    val url = activeSourceUrl.trim()
    if (url.isEmpty() || url.startsWith("file:") || isLoopbackPartialPlaybackUrl(url)) return false
    return url.isSupportedDownloadUrl()
}

private val PlayerScreenRuntime.isDownloadablePlayback: Boolean
    get() = shouldTrackWatchProgress

@Composable
internal fun PlayerScreenRuntime.rememberPlayerDownloadAction(): (@Composable (buttonSize: Dp, iconSize: Dp) -> Unit)? {
    val uiState by DownloadsRepository.uiState.collectAsStateWithLifecycle()
    if (!playerDownloadActionAvailable()) return null
    val key = downloadLogicalContentKey(parentMetaId, activeSeasonNumber, activeEpisodeNumber)
    val row = uiState.items.firstOrNull { it.logicalContentKey == key }
    return { buttonSize, iconSize ->
        PlayerDownloadButton(
            row = row,
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = { onPlayerDownloadClick(row) },
        )
    }
}

private fun PlayerScreenRuntime.onPlayerDownloadClick(row: DownloadItem?) {
    when (row?.status) {
        DownloadStatus.Completed -> NuvioToastController.show("Already downloaded")
        DownloadStatus.Downloading, DownloadStatus.Queued -> NuvioToastController.show("Downloading in the background")
        DownloadStatus.Paused -> {
            DownloadsRepository.resumeDownload(row.id)
            NuvioToastController.show("Download resumed")
        }
        // A failed row keeps its bytes; retry continues it (with a fresh link if it expired).
        DownloadStatus.Failed -> {
            DownloadsRepository.retryDownload(row.id)
            NuvioToastController.show("Retrying download")
        }
        null -> startPlayerDownload()
    }
}

private fun PlayerScreenRuntime.startPlayerDownload() {
    val url = activeSourceUrl
    val stream = StreamItem(
        name = activeStreamTitle,
        description = activeStreamSubtitle,
        url = url,
        addonName = activeProviderName.orEmpty(),
        addonId = activeProviderAddonId.orEmpty(),
        streamType = activeStreamType,
        behaviorHints = StreamBehaviorHints(
            bingeGroup = currentStreamBingeGroup,
            proxyHeaders = StreamProxyHeaders(
                request = activeSourceHeaders.takeIf { it.isNotEmpty() },
                response = activeSourceResponseHeaders.takeIf { it.isNotEmpty() },
            ),
        ),
    )
    val target = DownloadTarget(
        contentType = contentType ?: parentMetaType,
        videoId = activeVideoId ?: videoId ?: parentMetaId,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        title = title,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        episodeThumbnail = activeEpisodeThumbnail,
    )
    NuvioToastController.show(DownloadEnqueueService.enqueue(target, stream).toastMessage())
}

internal sealed interface PlayerDownloadIndicator {
    data class Progress(val percent: Int) : PlayerDownloadIndicator
    /** Bytes are arriving but the size is unknown. */
    data object Spinner : PlayerDownloadIndicator
    /** Waiting for a slot (the playback throttle holds it back): nothing is moving, so nothing spins. */
    data object Waiting : PlayerDownloadIndicator
    data object NotDownloaded : PlayerDownloadIndicator
    data object Done : PlayerDownloadIndicator
    data object Paused : PlayerDownloadIndicator
    data object Failed : PlayerDownloadIndicator
}

internal fun playerDownloadIndicator(row: DownloadItem?): PlayerDownloadIndicator = when (row?.status) {
    null -> PlayerDownloadIndicator.NotDownloaded
    DownloadStatus.Completed -> PlayerDownloadIndicator.Done
    DownloadStatus.Paused -> PlayerDownloadIndicator.Paused
    DownloadStatus.Failed -> PlayerDownloadIndicator.Failed
    DownloadStatus.Queued -> PlayerDownloadIndicator.Waiting
    DownloadStatus.Downloading -> row.totalBytes?.takeIf { it > 0L }
        ?.let { PlayerDownloadIndicator.Progress(((row.downloadedBytes * 100) / it).toInt().coerceIn(0, 100)) }
        ?: PlayerDownloadIndicator.Spinner
}

@Composable
private fun PlayerDownloadButton(
    row: DownloadItem?,
    buttonSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val indicator = playerDownloadIndicator(row)
    val description = when (indicator) {
        PlayerDownloadIndicator.NotDownloaded -> "Download"
        PlayerDownloadIndicator.Done -> "Downloaded"
        is PlayerDownloadIndicator.Progress -> "Downloading ${indicator.percent}%"
        PlayerDownloadIndicator.Spinner -> "Downloading"
        PlayerDownloadIndicator.Waiting -> "Download queued"
        PlayerDownloadIndicator.Paused -> "Download paused"
        PlayerDownloadIndicator.Failed -> "Download failed"
    }
    IconButton(onClick = onClick, modifier = Modifier.size(buttonSize)) {
        when (indicator) {
            is PlayerDownloadIndicator.Progress -> Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { indicator.percent / 100f },
                    modifier = Modifier.size(iconSize + 4.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeWidth = 2.dp,
                )
                Text("${indicator.percent}", color = Color.White, fontSize = 9.sp, maxLines = 1)
            }
            PlayerDownloadIndicator.Spinner -> CircularProgressIndicator(
                modifier = Modifier.size(iconSize + 4.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                strokeWidth = 2.dp,
            )
            else -> Icon(
                imageVector = when (indicator) {
                    PlayerDownloadIndicator.Done -> Icons.Rounded.DownloadDone
                    PlayerDownloadIndicator.Paused -> Icons.Rounded.PauseCircleOutline
                    PlayerDownloadIndicator.Failed -> Icons.Rounded.ErrorOutline
                    PlayerDownloadIndicator.Waiting -> Icons.Rounded.Schedule
                    else -> Icons.Rounded.Download
                },
                contentDescription = description,
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
