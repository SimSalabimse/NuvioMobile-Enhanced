package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo

internal enum class EpisodesPanelBody {
    Loading,
    List,
    /** Episodes that loaded, plus Retry for the seasons that did not. */
    Partial,
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
    episodesComplete: Boolean = true,
): EpisodesPanelBody = when {
    episodeCount > 0 && loading -> EpisodesPanelBody.List
    episodeCount > 0 && !episodesComplete -> EpisodesPanelBody.Partial
    episodeCount > 0 -> EpisodesPanelBody.List
    loading -> EpisodesPanelBody.Loading
    failed || emptyNeedsRetry || !episodesComplete -> EpisodesPanelBody.Failed
    else -> EpisodesPanelBody.Empty
}

/** An incomplete fetch keeps episodes that already arrived. */
internal fun episodesKeptAfterIncompleteFetch(
    current: List<MetaVideo>,
    incoming: List<MetaVideo>,
    episodesComplete: Boolean,
): List<MetaVideo> = if (episodesComplete || incoming.isNotEmpty() || current.isNotEmpty()) {
    mergePlayerEpisodes(current, incoming)
} else {
    current
}

/** Keeps a loaded episode list when a later details update arrives with none. */
internal fun mergePlayerEpisodes(current: List<MetaVideo>, incoming: List<MetaVideo>): List<MetaVideo> =
    if (incoming.isEmpty() && current.isNotEmpty()) current else incoming
