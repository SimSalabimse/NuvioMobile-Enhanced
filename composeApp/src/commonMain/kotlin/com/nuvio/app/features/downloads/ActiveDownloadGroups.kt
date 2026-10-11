package com.nuvio.app.features.downloads

/** Two or more active episodes of one show and season shown as one collapsible group. */
internal data class ActiveSeasonGroup(
    val key: String,
    val label: String,
    val items: List<DownloadItem>,
) {
    val anyRunning: Boolean
        get() = items.any { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }

    val anyPaused: Boolean
        get() = items.any { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }

    /** Downloaded share of the bytes known so far; null until any episode reports a size. */
    val progressPercent: Int?
        get() {
            val sized = items.filter { (it.totalBytes ?: 0L) > 0L }
            if (sized.isEmpty()) return null
            val total = sized.sumOf { it.totalBytes ?: 0L }
            val done = sized.sumOf { it.downloadedBytes.coerceAtMost(it.totalBytes ?: 0L) }
            return ((done.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        }
}

internal sealed class ActiveDownloadEntry {
    data class Single(val item: DownloadItem) : ActiveDownloadEntry()
    data class Season(val group: ActiveSeasonGroup) : ActiveDownloadEntry()
}

/**
 * Active rows in list order. Episodes of the same show and season become one group at the
 * position of their first episode; a lone episode or a movie stays a single row.
 */
internal fun activeDownloadEntries(active: List<DownloadItem>): List<ActiveDownloadEntry> {
    val bySeason = active
        .filter { it.isEpisode }
        .groupBy { seasonGroupKey(it) }
    val emitted = mutableSetOf<String>()
    val entries = mutableListOf<ActiveDownloadEntry>()
    for (item in active) {
        if (!item.isEpisode) {
            entries += ActiveDownloadEntry.Single(item)
            continue
        }
        val key = seasonGroupKey(item)
        val members = bySeason[key].orEmpty()
        if (members.size < 2) {
            entries += ActiveDownloadEntry.Single(item)
            continue
        }
        if (!emitted.add(key)) continue
        entries += ActiveDownloadEntry.Season(
            ActiveSeasonGroup(
                key = key,
                label = "${item.title} · S${item.seasonNumber}",
                items = members.sortedBy { it.episodeNumber ?: 0 },
            ),
        )
    }
    return entries
}

private fun seasonGroupKey(item: DownloadItem): String = "${item.parentMetaId.trim()}|${item.seasonNumber}"
