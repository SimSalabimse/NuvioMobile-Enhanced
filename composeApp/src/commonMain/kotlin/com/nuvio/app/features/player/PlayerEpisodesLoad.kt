package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo

internal enum class EpisodesPanelBody {
    Loading,
    List,
    Empty,
    Failed,
}

/**
 * What the episodes panel shows for the whole list.
 * A list that already has episodes stays visible. An empty list is a spinner while
 * loading, a retry when the fetch failed or only an empty cached result came back,
 * and "No episodes available" only after a finished load that is still empty.
 */
internal fun episodesPanelBody(
    loading: Boolean,
    failed: Boolean,
    episodeCount: Int,
    emptyNeedsRetry: Boolean,
): EpisodesPanelBody = when {
    episodeCount > 0 -> EpisodesPanelBody.List
    loading -> EpisodesPanelBody.Loading
    failed || emptyNeedsRetry -> EpisodesPanelBody.Failed
    else -> EpisodesPanelBody.Empty
}

/** Keeps a loaded episode list when a later details update arrives with none. */
internal fun mergePlayerEpisodes(current: List<MetaVideo>, incoming: List<MetaVideo>): List<MetaVideo> =
    if (incoming.isEmpty() && current.isNotEmpty()) current else incoming
