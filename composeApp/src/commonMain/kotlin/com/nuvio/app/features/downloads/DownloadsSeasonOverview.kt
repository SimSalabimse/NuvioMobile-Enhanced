package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.navigation.EpisodeSubmitRequest

internal data class DownloadsSeasonOverviewContent(
    val meta: MetaDetails,
    val episodes: List<MetaVideo>,
)

/**
 * The downloads season uses the same overview model as details.
 * Only episodes already in this show and season are included.
 */
internal fun downloadsSeasonOverviewContent(
    items: List<DownloadItem>,
    showId: String,
    season: Int,
): DownloadsSeasonOverviewContent? {
    val episodes = items
        .filter { it.parentMetaId == showId && it.seasonNumber == season && it.episodeNumber != null }
        .sortedWith(compareBy({ it.episodeNumber }, { it.createdAtEpochMs }))
    if (episodes.isEmpty()) return null
    val first = episodes.first()
    val type = first.parentMetaType.ifBlank { first.contentType }.ifBlank { "series" }
    return DownloadsSeasonOverviewContent(
        meta = MetaDetails(
            id = showId,
            type = type,
            name = first.title,
            poster = first.poster,
            background = first.background,
            logo = first.logo,
            imdbId = showId.takeIf { it.startsWith("tt", ignoreCase = true) },
        ),
        episodes = episodes.map { item ->
            MetaVideo(
                id = item.videoId,
                title = item.episodeTitle?.takeIf { it.isNotBlank() } ?: item.title,
                thumbnail = item.episodeThumbnail,
                season = item.seasonNumber,
                episode = item.episodeNumber,
                imdbId = item.videoId.split(":").firstOrNull()?.takeIf { it.startsWith("tt", ignoreCase = true) },
            )
        },
    )
}

/** Opens the downloaded episode in submit mode. Resume and watched state are not fields here. */
internal fun DownloadItem.toEpisodeSubmitRequest(): EpisodeSubmitRequest = EpisodeSubmitRequest(
    type = parentMetaType.ifBlank { contentType }.ifBlank { "series" },
    videoId = videoId,
    parentMetaId = parentMetaId,
    parentMetaType = parentMetaType.ifBlank { contentType }.ifBlank { "series" },
    title = title,
    logo = logo,
    poster = poster,
    background = background,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeTitle = episodeTitle,
    episodeThumbnail = episodeThumbnail,
)
