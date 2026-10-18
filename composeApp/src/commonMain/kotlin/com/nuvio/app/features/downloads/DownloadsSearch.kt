package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.matchesSearch

/** Order of finished movies and shows in Downloads. */
internal enum class DownloadsSort(val label: String) {
    Name("Name"),
    Size("Size"),
    Date("Date"),
}

/** Downloads whose title or episode title contains [query] (case and accents ignored). */
internal fun List<DownloadItem>.matchingDownloadsQuery(query: String): List<DownloadItem> =
    if (query.isBlank()) this else filter { matchesSearch(it.title, query) || matchesSearch(it.episodeTitle, query) }

/** Groups (a movie, or a show's episodes) by name, total size (largest first) or newest first. */
internal fun List<List<DownloadItem>>.sortedDownloadGroups(sort: DownloadsSort): List<List<DownloadItem>> = when (sort) {
    DownloadsSort.Name -> sortedBy { group -> group.first().title.lowercase() }
    DownloadsSort.Size -> sortedByDescending { group -> group.sumOf { it.totalBytes ?: it.downloadedBytes } }
    DownloadsSort.Date -> sortedByDescending { group -> group.maxOf { it.updatedAtEpochMs } }
}
