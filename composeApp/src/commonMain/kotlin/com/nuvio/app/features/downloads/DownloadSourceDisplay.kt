package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.streams.StreamItem

/*
 * Shared source display for every download entry point (details sheet, episode rows, the
 * Continue Watching / Next Up hold menu, Download Season and the stream list in download mode).
 */

/** Download size from stream metadata: TorBox/debrid file size, behaviorHints.videoSize, or a size in the text. */
internal fun downloadStreamBytes(stream: StreamItem): Long? =
    stream.clientResolve?.stream?.raw?.size?.takeIf { it > 0L }
        ?: statedStreamBytes(stream)
        ?: stream.debridCacheStatus?.cachedSize?.takeIf { it > 0L }

internal const val DOWNLOAD_SIZE_UNKNOWN = "Size unknown"

internal fun downloadSizeLabel(stream: StreamItem): String =
    downloadStreamBytes(stream)?.let(::formatSeasonBytes) ?: DOWNLOAD_SIZE_UNKNOWN

/** Quality chip text, e.g. "1080p" or "4K"; null when the stream names no resolution. */
internal fun downloadQualityChipText(stream: StreamItem): String? {
    val label = streamQualityLabel(stream)
    val height = qualityHeight(label) ?: return null
    return if (height >= 2160) "4K" else "${height}p"
}

/** "1080p · 2.3 GB", "1080p · Size unknown" or just the size when no resolution is named. */
internal fun downloadQualitySizeLabel(stream: StreamItem): String =
    listOfNotNull(downloadQualityChipText(stream), downloadSizeLabel(stream)).joinToString(" · ")

/** Quality and size chips shown on picked cards and source rows. */
@Composable
internal fun DownloadQualitySizeChips(
    stream: StreamItem,
    modifier: Modifier = Modifier,
    bytesOverride: Long? = null,
) {
    val quality = downloadQualityChipText(stream)
    val size = (bytesOverride?.takeIf { it > 0L } ?: downloadStreamBytes(stream))?.let(::formatSeasonBytes)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (quality != null) {
            DownloadChip(text = quality, emphasized = true)
        }
        DownloadChip(text = size ?: DOWNLOAD_SIZE_UNKNOWN, emphasized = false, muted = size == null)
    }
}

@Composable
private fun DownloadChip(text: String, emphasized: Boolean, muted: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (emphasized) scheme.primary.copy(alpha = 0.18f) else scheme.onSurface.copy(alpha = 0.08f),
        contentColor = when {
            emphasized -> scheme.primary
            muted -> scheme.onSurfaceVariant.copy(alpha = 0.75f)
            else -> scheme.onSurface
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** Set by download screens so stream list rows also show the quality and size chips. */
internal val LocalDownloadSourceChips = staticCompositionLocalOf { false }

