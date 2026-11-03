package com.nuvio.app.features.offline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.sync.OfflineOutboxEntry
import com.nuvio.app.core.sync.watchedOutboxEntry
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.releasedMainSeasonEpisodes
import com.nuvio.app.features.watched.toEpisodeWatchedItem
import com.nuvio.app.features.watched.toSeriesWatchedItem

internal enum class OfflineSurfaceAction {
    LongPressMenu,
    Details,
    WatchedToggle,
    Play,
    StreamsLookup,
    Trailers,
    RemoteSearch,
    AccountSync,
}

internal data class OfflineItemFacts(
    val online: Boolean,
    val cached: Boolean,
    val downloaded: Boolean,
)

internal data class DownloadedEpisodeRef(
    val season: Int,
    val episode: Int,
    val title: String,
    val videoId: String,
    val poster: String?,
)

/**
 * A visible row (library, Continue Watching, catalog, or a saved details page) counts as cached.
 * A playable file on disk counts as downloaded. Network actions stay off until the connection is back.
 */
internal fun offlineSurfaceActionEnabled(
    facts: OfflineItemFacts,
    action: OfflineSurfaceAction,
): Boolean {
    val onDevice = facts.online || facts.cached || facts.downloaded
    return when (action) {
        OfflineSurfaceAction.LongPressMenu,
        OfflineSurfaceAction.Details,
        OfflineSurfaceAction.WatchedToggle,
        -> onDevice
        OfflineSurfaceAction.Play,
        -> facts.online || facts.downloaded
        OfflineSurfaceAction.StreamsLookup,
        OfflineSurfaceAction.Trailers,
        OfflineSurfaceAction.RemoteSearch,
        OfflineSurfaceAction.AccountSync,
        -> facts.online
    }
}

internal fun String.isOfflineSeriesType(): Boolean =
    trim().lowercase() in setOf("series", "show", "tv", "tvshow")

internal fun watchedToggleTargets(
    id: String,
    type: String,
    name: String,
    poster: String?,
    releaseInfo: String?,
    currentlyWatched: Boolean,
    cachedMeta: MetaDetails?,
    downloadedEpisodes: List<DownloadedEpisodeRef>,
    todayIsoDate: String,
    markedAtEpochMs: Long,
): List<WatchedItem> {
    if (cachedMeta != null && type.isOfflineSeriesType()) {
        val released = cachedMeta.releasedMainSeasonEpisodes(todayIsoDate)
        if (released.isEmpty()) {
            return if (currentlyWatched) {
                listOf(cachedMeta.toSeriesWatchedItem(markedAtEpochMs))
            } else {
                emptyList()
            }
        }
        return buildList {
            add(cachedMeta.toSeriesWatchedItem(markedAtEpochMs))
            addAll(released.map { episode -> cachedMeta.toEpisodeWatchedItem(episode, markedAtEpochMs) })
        }
    }
    if (type.isOfflineSeriesType() && downloadedEpisodes.isNotEmpty()) {
        return buildList {
            add(
                WatchedItem(
                    id = id,
                    type = type,
                    name = name,
                    poster = poster,
                    releaseInfo = releaseInfo,
                    markedAtEpochMs = markedAtEpochMs,
                ),
            )
            addAll(
                downloadedEpisodes.map { episode ->
                    WatchedItem(
                        id = id,
                        type = type,
                        name = episode.title.ifBlank { name },
                        poster = episode.poster ?: poster,
                        releaseInfo = releaseInfo,
                        season = episode.season,
                        episode = episode.episode,
                        videoId = episode.videoId,
                        markedAtEpochMs = markedAtEpochMs,
                    )
                },
            )
        }
    }
    return listOf(
        WatchedItem(
            id = id,
            type = type,
            name = name,
            poster = poster,
            releaseInfo = releaseInfo,
            markedAtEpochMs = markedAtEpochMs,
        ),
    )
}

/** Entries [com.nuvio.app.features.watched.WatchedRepository] writes for this toggle. Empty when the toggle is disabled. */
internal fun offlineWatchedToggleEntries(
    facts: OfflineItemFacts,
    played: Boolean,
    id: String,
    type: String,
    name: String,
    poster: String?,
    releaseInfo: String?,
    currentlyWatched: Boolean,
    cachedMeta: MetaDetails?,
    downloadedEpisodes: List<DownloadedEpisodeRef>,
    todayIsoDate: String,
    markedAtEpochMs: Long,
    profileId: Int,
    source: WatchProgressSource,
): List<OfflineOutboxEntry> {
    if (!offlineSurfaceActionEnabled(facts, OfflineSurfaceAction.WatchedToggle)) return emptyList()
    return watchedToggleTargets(
        id = id,
        type = type,
        name = name,
        poster = poster,
        releaseInfo = releaseInfo,
        currentlyWatched = currentlyWatched,
        cachedMeta = cachedMeta,
        downloadedEpisodes = downloadedEpisodes,
        todayIsoDate = todayIsoDate,
        markedAtEpochMs = markedAtEpochMs,
    ).map { item ->
        watchedOutboxEntry(
            profileId = profileId,
            item = item,
            played = played,
            mirrorTrackers = true,
            source = source,
        )
    }
}

internal fun metaFromPlayableDownloads(
    type: String,
    id: String,
    items: List<DownloadItem>,
): MetaDetails? {
    val matched = items.filter { item ->
        item.parentMetaId == id &&
            item.parentMetaType.equals(type, ignoreCase = true) &&
            item.isPlayable
    }
    if (matched.isEmpty()) return null
    val first = matched.maxBy { it.updatedAtEpochMs }
    val videos = matched.filter { it.isEpisode }
        .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
        .map { item ->
            MetaVideo(
                id = item.videoId.ifBlank { "${item.parentMetaId}:${item.seasonNumber}:${item.episodeNumber}" },
                title = item.episodeTitle?.takeIf { it.isNotBlank() } ?: item.title,
                thumbnail = item.episodeThumbnail ?: item.poster,
                season = item.seasonNumber,
                episode = item.episodeNumber,
                released = "2000-01-01",
                available = true,
            )
        }
    return MetaDetails(
        id = id,
        type = type,
        name = first.title,
        poster = matched.firstNotNullOfOrNull { it.poster },
        background = matched.firstNotNullOfOrNull { it.background },
        logo = matched.firstNotNullOfOrNull { it.logo },
        videos = videos,
    )
}

internal fun downloadedMetaFor(type: String, id: String): MetaDetails? =
    metaFromPlayableDownloads(type, id, DownloadsRepository.uiState.value.items)

@Composable
internal fun rememberItemPlayEnabled(
    parentMetaId: String,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    videoId: String? = null,
    cached: Boolean = true,
): Boolean {
    val network by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    val downloads by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val downloaded = remember(downloads.items, parentMetaId, seasonNumber, episodeNumber, videoId) {
        DownloadsRepository.findPlayableDownload(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            videoId = videoId,
        ) != null
    }
    return offlineSurfaceActionEnabled(
        OfflineItemFacts(
            online = !network.isOfflineLike,
            cached = cached,
            downloaded = downloaded,
        ),
        OfflineSurfaceAction.Play,
    )
}
