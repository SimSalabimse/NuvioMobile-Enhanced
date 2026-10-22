package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId

internal data class SeasonEnqueueRow(
    val video: MetaVideo,
    val stream: StreamItem,
)

internal data class SeasonEnqueueResult(
    val queued: Int,
    val failureMessage: String?,
)

internal suspend fun enqueueSeasonDownloads(
    meta: MetaDetails,
    rows: List<SeasonEnqueueRow>,
): SeasonEnqueueResult {
    var queued = 0
    var failureMessage: String? = null
    DownloadsRepository.ensureLoaded()
    // Lowest episode first, whatever order the sheet listed them in.
    val ordered = rows.sortedWith(compareBy({ it.video.season ?: Int.MAX_VALUE }, { it.video.episode ?: Int.MAX_VALUE }))
    for (row in ordered) {
        val season = row.video.season
        val episode = row.video.episode
        // Queuing replaces an existing row for the episode and deletes its file. Leave one the user has.
        val existingKey = downloadLogicalContentKey(meta.id, season, episode)
        val existingStatus = DownloadsRepository.uiState.value.items
            .firstOrNull { it.logicalContentKey == existingKey }
            ?.status
        if (seasonExistingDownload(existingStatus) != null) continue
        val resolved = resolveForDownload(row.stream, season, episode)
            ?: nextGoodDownloadStream(meta, row)
            ?: run {
                failureMessage = failureMessage ?: lastResolveFailure
                null
            }
            ?: continue

        val url = resolved.playableDirectUrl
        if (url.isNullOrBlank()) {
            failureMessage = failureMessage ?: DownloadEnqueueResult.MissingUrl.toastMessage()
            continue
        }
        if (!url.isSupportedDownloadUrl()) {
            failureMessage = failureMessage ?: DownloadEnqueueResult.UnsupportedFormat.toastMessage()
            continue
        }
        val videoId = seasonStreamVideoId(meta.id, row.video)
        val enqueueResult = DownloadsRepository.enqueueFromStream(
            contentType = meta.type,
            videoId = videoId,
            parentMetaId = meta.id,
            parentMetaType = meta.type,
            title = meta.name,
            logo = meta.logo,
            poster = meta.poster,
            background = meta.background,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = row.video.title,
            episodeThumbnail = row.video.thumbnail,
            stream = resolved,
        )
        when (enqueueResult) {
            DownloadEnqueueResult.Started,
            DownloadEnqueueResult.Replaced,
            -> queued += 1
            else -> failureMessage = failureMessage ?: enqueueResult.toastMessage()
        }
    }
    return SeasonEnqueueResult(queued = queued, failureMessage = failureMessage)
}

internal fun seasonStreamVideoId(parentMetaId: String, video: MetaVideo): String {
    val playbackVideoId = buildPlaybackVideoId(
        parentMetaId = parentMetaId,
        seasonNumber = video.season,
        episodeNumber = video.episode,
        fallbackVideoId = video.id,
    )
    return video.id.takeIf { it.isNotBlank() } ?: playbackVideoId
}

private var lastResolveFailure: String? = null

/** The stream ready to download, or null when debrid can't give a link (e.g. not cached). */
private suspend fun resolveForDownload(stream: StreamItem, season: Int?, episode: Int?): StreamItem? {
    if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) return stream
    return when (val result = DirectDebridPlaybackResolver.resolveToPlayableStream(stream, season, episode)) {
        is DirectDebridPlayableResult.Success -> result.stream
        else -> {
            lastResolveFailure = result.toastMessage()
            null
        }
    }
}

/**
 * The picked source wasn't usable (not cached on debrid, stale): try the next downloadable
 * sources for the episode in quality order, skipping ones the cache check already marked uncached.
 */
private suspend fun nextGoodDownloadStream(meta: MetaDetails, row: SeasonEnqueueRow): StreamItem? {
    val video = row.video
    val streams = SeasonStreamCatalog.load(meta.type, seasonStreamVideoId(meta.id, video), video.season, video.episode, annotateDebridCache = true)
    return streams.orderedForQuality(SmartDownloadSettings.snapshot().quality)
        .filter { it != row.stream && it.isSeasonDownloadCandidate() && it.debridCacheStatus?.state != StreamDebridCacheState.NOT_CACHED }
        .take(MAX_FALLBACK_SOURCES)
        .firstNotNullOfOrNull { resolveForDownload(it, video.season, video.episode) }
}

private const val MAX_FALLBACK_SOURCES = 3
