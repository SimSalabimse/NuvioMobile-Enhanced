package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircleOutline
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
import com.nuvio.app.features.downloads.DownloadItem
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
    val url = activeSourceUrl?.trim().orEmpty()
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
    val url = activeSourceUrl ?: return
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
    val result = DownloadsRepository.enqueueFromStream(
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
        stream = stream,
    )
    NuvioToastController.show(result.toastMessage())
}

@Composable
private fun PlayerDownloadButton(
    row: DownloadItem?,
    buttonSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val percent = row?.totalBytes?.takeIf { it > 0L }?.let { ((row.downloadedBytes * 100) / it).toInt().coerceIn(0, 100) }
    val description = when (row?.status) {
        null -> "Download"
        DownloadStatus.Completed -> "Downloaded"
        DownloadStatus.Downloading -> if (percent != null) "Downloading $percent%" else "Downloading"
        DownloadStatus.Queued -> "Download queued"
        DownloadStatus.Paused -> "Download paused"
        DownloadStatus.Failed -> "Download failed"
    }
    IconButton(onClick = onClick, modifier = Modifier.size(buttonSize)) {
        when (row?.status) {
            DownloadStatus.Downloading, DownloadStatus.Queued -> Box(contentAlignment = Alignment.Center) {
                if (percent != null && row.status == DownloadStatus.Downloading) {
                    CircularProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.size(iconSize + 4.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.25f),
                        strokeWidth = 2.dp,
                    )
                    Text("$percent", color = Color.White, fontSize = 9.sp, maxLines = 1)
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(iconSize + 4.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.25f),
                        strokeWidth = 2.dp,
                    )
                }
            }
            else -> Icon(
                imageVector = when (row?.status) {
                    DownloadStatus.Completed -> Icons.Rounded.DownloadDone
                    DownloadStatus.Paused -> Icons.Rounded.PauseCircleOutline
                    DownloadStatus.Failed -> Icons.Rounded.ErrorOutline
                    else -> Icons.Rounded.Download
                },
                contentDescription = description,
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
