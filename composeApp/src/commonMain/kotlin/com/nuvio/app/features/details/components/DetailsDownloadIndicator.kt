package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.features.downloads.DetailsDownloadProgress
import com.nuvio.app.features.downloads.DetailsDownloadProgressKind
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.detailsDownloadProgress
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_downloaded
import nuvio.composeapp.generated.resources.details_download_action
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun rememberDetailsDownloadProgress(contentKey: String?): DetailsDownloadProgress {
    val state by DownloadsRepository.uiState.collectAsStateWithLifecycle()
    if (contentKey.isNullOrBlank()) return DetailsDownloadProgress.None
    return remember(state.items, contentKey) {
        detailsDownloadProgress(state.items, contentKey)
    }
}

@Composable
internal fun downloadProgressLabel(progress: DetailsDownloadProgress): String {
    val action = stringResource(Res.string.details_download_action)
    return when (progress.kind) {
        DetailsDownloadProgressKind.Determinate -> "$action ${progress.percent}%"
        DetailsDownloadProgressKind.Completed -> stringResource(Res.string.compose_player_downloaded)
        DetailsDownloadProgressKind.Indeterminate,
        DetailsDownloadProgressKind.None,
        -> action
    }
}

@Composable
internal fun DetailsDownloadIconMark(
    progress: DetailsDownloadProgress,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    when (progress.kind) {
        DetailsDownloadProgressKind.None -> Unit
        DetailsDownloadProgressKind.Determinate -> {
            Box(
                modifier = modifier.size(34.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { progress.fraction ?: 0f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "${progress.percent}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
            }
        }
        DetailsDownloadProgressKind.Indeterminate -> {
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(16.dp),
                )
                LinearProgressIndicator(
                    modifier = Modifier.width(28.dp).height(3.dp),
                )
            }
        }
        DetailsDownloadProgressKind.Completed -> {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = contentDescription,
                modifier = modifier.size(21.dp),
            )
        }
    }
}

@Composable
internal fun DetailsDownloadWideContent(
    progress: DetailsDownloadProgress,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    val labelStyle = if (isTablet) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.titleSmall
    }
    val iconSize = if (isTablet) 22.dp else 20.dp
    when (progress.kind) {
        DetailsDownloadProgressKind.Indeterminate -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.details_download_action),
                    style = labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
        DetailsDownloadProgressKind.Determinate -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    progress = { progress.fraction ?: 0f },
                    modifier = Modifier.size(iconSize),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${progress.percent}%",
                    style = labelStyle,
                    maxLines = 1,
                )
            }
        }
        DetailsDownloadProgressKind.Completed -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(Res.string.compose_player_downloaded),
                    modifier = Modifier.size(iconSize),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.details_download_action),
                    style = labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DetailsDownloadProgressKind.None -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    modifier = Modifier.size(if (isTablet) 20.dp else 18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.details_download_action),
                    style = labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun DetailsDownloadEpisodeMark(
    progress: DetailsDownloadProgress,
    onDark: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!progress.showsMark) return
    val percentColor = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface
    val track = if (onDark) {
        Color.White.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    }
    val fill = MaterialTheme.colorScheme.primary
    when (progress.kind) {
        DetailsDownloadProgressKind.None -> Unit
        DetailsDownloadProgressKind.Determinate -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NuvioProgressBar(
                    progress = progress.fraction ?: 0f,
                    height = 4.dp,
                    trackColor = track,
                    fillColor = fill,
                )
                Text(
                    text = "${progress.percent}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = percentColor,
                    maxLines = 1,
                )
            }
        }
        DetailsDownloadProgressKind.Indeterminate -> {
            LinearProgressIndicator(
                modifier = modifier.fillMaxWidth().height(4.dp),
                color = fill,
                trackColor = track,
            )
        }
        DetailsDownloadProgressKind.Completed -> {
            Box(modifier = modifier) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(Res.string.compose_player_downloaded),
                    tint = fill,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
