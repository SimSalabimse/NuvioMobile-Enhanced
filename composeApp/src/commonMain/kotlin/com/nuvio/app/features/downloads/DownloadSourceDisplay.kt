package com.nuvio.app.features.downloads

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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


/** Quality buckets offered as options: 4K, 1080p, 720p, 480p (anything lower counts as 480p). */
internal val DOWNLOAD_QUALITY_BUCKETS = listOf(2160, 1080, 720, 480)

internal fun downloadQualityBucket(stream: StreamItem): Int? {
    val height = qualityHeight(streamQualityLabel(stream)) ?: return null
    return DOWNLOAD_QUALITY_BUCKETS.firstOrNull { height >= it } ?: 480
}

internal fun downloadQualityBucketLabel(bucket: Int): String = if (bucket >= 2160) "4K" else "${bucket}p"

/** One tappable quality option: the best auto-pick candidate of that quality. */
internal data class DownloadQualityOption(
    val bucket: Int,
    val stream: StreamItem,
) {
    val label: String get() = downloadQualityBucketLabel(bucket)
}

/**
 * Best candidate per quality present in [streams], highest quality first. [pickFrom] is the
 * auto-pick selector (same rules as auto-play); when it declines a quality, the first
 * downloadable stream of that quality in source order is used.
 */
internal fun bestPerQualityOptions(
    streams: List<StreamItem>,
    pickFrom: (List<StreamItem>) -> StreamItem?,
): List<DownloadQualityOption> {
    val byBucket = streams.filter { it.isSeasonDownloadCandidate() }.groupBy(::downloadQualityBucket)
    return DOWNLOAD_QUALITY_BUCKETS.mapNotNull { bucket ->
        val candidates = byBucket[bucket].orEmpty()
        if (candidates.isEmpty()) return@mapNotNull null
        val best = runCatching { pickFrom(candidates) }.getOrNull()?.takeIf { it in candidates } ?: candidates.first()
        DownloadQualityOption(bucket, best)
    }
}

/**
 * Row of per-quality options ("1080p · 2.3 GB"); the current one is highlighted. Shared by the
 * picked-source card (every single download entry point) and Download Season.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadQualityOptionsRow(
    options: List<DownloadQualityOption>,
    selectedBucket: Int?,
    onSelect: (DownloadQualityOption) -> Unit,
    modifier: Modifier = Modifier,
    showSize: Boolean = true,
) {
    if (options.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val selected = option.bucket == selectedBucket
            val text = if (showSize) "${option.label} · ${downloadSizeLabel(option.stream)}" else option.label
            FilterChip(
                selected = selected,
                onClick = { if (!selected) onSelect(option) },
                label = { Text(text, maxLines = 1) },
            )
        }
    }
}
