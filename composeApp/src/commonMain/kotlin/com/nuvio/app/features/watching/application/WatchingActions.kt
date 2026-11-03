package com.nuvio.app.features.watching.application

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.currentOfflineDetailsNetwork
import com.nuvio.app.features.details.OfflineCacheNetwork
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.FinishedDownloadDeletion
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.offline.DownloadedEpisodeRef
import com.nuvio.app.features.offline.isOfflineSeriesType
import com.nuvio.app.features.offline.watchedToggleTargets
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.episodePlaybackId
import com.nuvio.app.features.watched.releasedMainSeasonEpisodes
import com.nuvio.app.features.watched.toEpisodeWatchedItem
import com.nuvio.app.features.watched.toSeriesWatchedItem
import com.nuvio.app.features.watched.toWatchedItem
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WatchingActions {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun togglePosterWatched(preview: MetaPreview) {
        val offline = currentOfflineDetailsNetwork() == OfflineCacheNetwork.Offline
        val cached = MetaDetailsRepository.cachedMeta(type = preview.type, id = preview.id)
        val meta = cached ?: if (offline) {
            null
        } else {
            MetaDetailsRepository.fetch(type = preview.type, id = preview.id)
        }
        val isCurrentlyWatched = WatchedRepository.isWatched(
            id = preview.id,
            type = preview.type,
        ) || WatchedRepository.isFullyWatchedSeries(id = preview.id, type = preview.type)
        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val targets = watchedToggleTargets(
            id = preview.id,
            type = preview.type,
            name = preview.name,
            poster = preview.poster,
            releaseInfo = preview.releaseInfo,
            currentlyWatched = isCurrentlyWatched,
            cachedMeta = meta,
            downloadedEpisodes = downloadedEpisodeRefs(preview.id),
            todayIsoDate = todayIsoDate,
            markedAtEpochMs = 0L,
        )
        if (targets.isEmpty()) return
        if (isCurrentlyWatched) {
            WatchedRepository.unmarkWatched(targets)
            clearToggleProgress(preview.id, meta, targets, todayIsoDate)
            if (preview.type.isSeriesLikeType()) {
                WatchedRepository.updateFullyWatchedSeries(
                    id = preview.id,
                    type = preview.type,
                    isFullyWatched = false,
                )
            }
        } else {
            WatchedRepository.markWatched(targets)
            val seriesComplete = meta != null &&
                preview.type.isSeriesLikeType() &&
                meta.releasedMainSeasonEpisodes(todayIsoDate).isNotEmpty()
            if (seriesComplete) {
                WatchedRepository.updateFullyWatchedSeries(
                    id = preview.id,
                    type = preview.type,
                    isFullyWatched = true,
                )
                clearToggleProgress(preview.id, meta, targets, todayIsoDate)
            }
        }
    }

    suspend fun toggleContinueWatchingWatched(item: ContinueWatchingItem) {
        val meta = MetaDetailsRepository.cachedMeta(type = item.parentMetaType, id = item.parentMetaId)
        val episode = item.seasonNumber?.let { season ->
            item.episodeNumber?.let { episode ->
                meta?.videos?.firstOrNull { video -> video.season == season && video.episode == episode }
                    ?: MetaVideo(
                        id = item.videoId,
                        title = item.episodeTitle ?: item.title,
                        season = season,
                        episode = episode,
                        released = "2000-01-01",
                    )
            }
        }
        if (meta != null && episode != null) {
            val watched = WatchedRepository.isWatched(
                id = item.parentMetaId,
                type = item.parentMetaType,
                season = episode.season,
                episode = episode.episode,
            )
            toggleEpisodeWatched(meta, episode, watched)
            return
        }
        WatchedRepository.toggleWatched(
            WatchedItem(
                id = item.parentMetaId,
                type = item.parentMetaType,
                name = item.episodeTitle ?: item.title,
                poster = item.poster,
                season = item.seasonNumber,
                episode = item.episodeNumber,
                videoId = item.videoId,
                markedAtEpochMs = 0L,
            ),
        )
    }

    fun toggleEpisodeWatched(
        meta: MetaDetails,
        episode: MetaVideo,
        isCurrentlyWatched: Boolean,
    ) {
        val watchedItem = meta.toEpisodeWatchedItem(episode)
        if (isCurrentlyWatched) {
            WatchedRepository.unmarkWatched(watchedItem)
            WatchProgressRepository.clearProgress(
                videoId = meta.episodePlaybackId(episode),
                parentMetaId = meta.id,
            )
        } else {
            WatchedRepository.markWatched(watchedItem)
            WatchProgressRepository.clearProgress(
                videoId = meta.episodePlaybackId(episode),
                parentMetaId = meta.id,
            )
        }
        reconcileSeriesWatchedState(meta)
    }

    fun togglePreviousEpisodesWatched(
        meta: MetaDetails,
        episodes: Collection<MetaVideo>,
        areCurrentlyWatched: Boolean,
    ) {
        toggleEpisodesWatched(
            meta = meta,
            episodes = episodes,
            areCurrentlyWatched = areCurrentlyWatched,
        )
    }

    fun toggleSeasonWatched(
        meta: MetaDetails,
        episodes: Collection<MetaVideo>,
        areCurrentlyWatched: Boolean,
    ) {
        toggleEpisodesWatched(
            meta = meta,
            episodes = episodes,
            areCurrentlyWatched = areCurrentlyWatched,
        )
    }

    fun reconcileSeriesWatchedState(
        meta: MetaDetails,
        todayIsoDate: String = CurrentDateProvider.todayIsoDate(),
    ) {
        if (!meta.type.isSeriesLikeType()) return

        WatchedRepository.reconcileSeriesWatchedState(
            meta = meta,
            todayIsoDate = todayIsoDate,
            isEpisodeCompleted = { episode ->
                WatchProgressRepository.progressForVideo(
                    videoId = meta.episodePlaybackId(episode),
                    parentMetaId = meta.id,
                    seasonNumber = episode.season,
                    episodeNumber = episode.episode,
                )?.isCompleted == true
            },
        )
    }

    fun onProgressEntryUpdated(entry: WatchProgressEntry, syncRemote: Boolean = true) {
        if (!entry.isCompleted) return

        val watchedItem = WatchedItem(
            id = entry.parentMetaId,
            type = entry.parentMetaType,
            name = entry.title,
            poster = entry.poster,
            season = entry.seasonNumber,
            episode = entry.episodeNumber,
            markedAtEpochMs = entry.lastUpdatedEpochMs,
        )
        WatchedRepository.markWatchedFromPlaybackCompletion(watchedItem, syncRemote = syncRemote)
        FinishedDownloadDeletion.onPlaybackCompleted(entry)

        if (!syncRemote || !entry.isEpisode) return
        actionScope.launch {
            val meta = runCatching {
                MetaDetailsRepository.fetch(
                    type = entry.parentMetaType,
                    id = entry.parentMetaId,
                )
            }.getOrNull() ?: return@launch

            reconcileSeriesWatchedState(meta = meta)
        }
    }

    private fun toggleEpisodesWatched(
        meta: MetaDetails,
        episodes: Collection<MetaVideo>,
        areCurrentlyWatched: Boolean,
    ) {
        if (episodes.isEmpty()) return
        val watchedItems = episodes.map(meta::toEpisodeWatchedItem)
        if (areCurrentlyWatched) {
            WatchedRepository.unmarkWatched(watchedItems)
            WatchProgressRepository.clearProgress(
                videoIds = episodes.map(meta::episodePlaybackId),
                parentMetaId = meta.id,
            )
        } else {
            WatchedRepository.markWatched(watchedItems)
            WatchProgressRepository.clearProgress(
                videoIds = episodes.map(meta::episodePlaybackId),
                parentMetaId = meta.id,
            )
        }
        reconcileSeriesWatchedState(meta)
    }
}

private fun downloadedEpisodeRefs(parentMetaId: String): List<DownloadedEpisodeRef> =
    DownloadsRepository.uiState.value.items.mapNotNull { item ->
        if (item.parentMetaId != parentMetaId || !item.isPlayable) return@mapNotNull null
        val season = item.seasonNumber ?: return@mapNotNull null
        val episode = item.episodeNumber ?: return@mapNotNull null
        DownloadedEpisodeRef(
            season = season,
            episode = episode,
            title = item.episodeTitle ?: item.title,
            videoId = item.videoId,
            poster = item.episodeThumbnail ?: item.poster,
        )
    }

private fun clearToggleProgress(
    parentMetaId: String,
    meta: MetaDetails?,
    targets: List<WatchedItem>,
    todayIsoDate: String,
) {
    val videoIds = if (meta != null && meta.type.isSeriesLikeType()) {
        meta.releasedMainSeasonEpisodes(todayIsoDate).map(meta::episodePlaybackId)
    } else {
        targets.mapNotNull { it.videoId }
    }
    if (videoIds.isEmpty()) return
    WatchProgressRepository.clearProgress(videoIds = videoIds, parentMetaId = parentMetaId)
}

private fun String.isSeriesLikeType(): Boolean = isOfflineSeriesType()
