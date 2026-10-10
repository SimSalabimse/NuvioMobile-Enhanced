package com.nuvio.app.features.downloads

enum class DetailsDownloadProgressKind {
    None,
    Determinate,
    Indeterminate,
    Completed,
}

data class DetailsDownloadProgress(
    val kind: DetailsDownloadProgressKind,
    val percent: Int? = null,
    val fraction: Float? = null,
) {
    companion object {
        val None = DetailsDownloadProgress(DetailsDownloadProgressKind.None)
    }

    val showsMark: Boolean
        get() = kind != DetailsDownloadProgressKind.None
}

fun downloadLogicalContentKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

/**
 * Integer percent of the Downloads screen bar for this item.
 * Null when the total size is unknown. That is when Downloads shows an indeterminate bar.
 */
fun DownloadItem.downloadsScreenProgressPercent(): Int? {
    totalBytes?.takeIf { it > 0L } ?: return null
    return (progressFraction * 100f).toInt().coerceIn(0, 100)
}

fun detailsDownloadProgress(
    items: List<DownloadItem>,
    logicalContentKey: String,
): DetailsDownloadProgress {
    val matches = items.filter { it.logicalContentKey == logicalContentKey }
    val downloading = matches.firstOrNull { it.status == DownloadStatus.Downloading }
    if (downloading != null) {
        val percent = downloading.downloadsScreenProgressPercent()
        return if (percent == null) {
            DetailsDownloadProgress(kind = DetailsDownloadProgressKind.Indeterminate)
        } else {
            DetailsDownloadProgress(
                kind = DetailsDownloadProgressKind.Determinate,
                percent = percent,
                fraction = downloading.progressFraction,
            )
        }
    }
    if (matches.any { it.status == DownloadStatus.Completed }) {
        return DetailsDownloadProgress(kind = DetailsDownloadProgressKind.Completed)
    }
    return DetailsDownloadProgress.None
}

/**
 * Same match the details Play button uses.
 * A null result means Play still opens the stream list.
 */
internal fun matchingPlayableDownload(
    items: List<DownloadItem>,
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    videoId: String?,
    playable: (DownloadItem) -> Boolean,
): DownloadItem? {
    val normalizedVideoId = videoId?.trim().orEmpty()
    if (normalizedVideoId.isNotBlank()) {
        items.firstOrNull { it.videoId == normalizedVideoId && playable(it) }?.let { return it }
    }
    val normalizedParentMetaId = parentMetaId.trim()
    return if (seasonNumber != null && episodeNumber != null) {
        items.firstOrNull { item ->
            item.parentMetaId == normalizedParentMetaId &&
                item.seasonNumber == seasonNumber &&
                item.episodeNumber == episodeNumber &&
                playable(item)
        }
    } else {
        items.firstOrNull { item ->
            item.parentMetaId == normalizedParentMetaId &&
                item.seasonNumber == null &&
                item.episodeNumber == null &&
                playable(item)
        }
    }
}
