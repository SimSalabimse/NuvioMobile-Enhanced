package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
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
    for (row in rows) {
        val season = row.video.season
        val episode = row.video.episode
        // Queuing replaces an existing row for the episode and deletes its file. Leave one the user has.
        val existingKey = downloadLogicalContentKey(meta.id, season, episode)
        val existingStatus = DownloadsRepository.uiState.value.items
            .firstOrNull { it.logicalContentKey == existingKey }
            ?.status
        if (seasonExistingDownload(existingStatus) != null) continue
        val resolved = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(row.stream)) {
            when (val result = DirectDebridPlaybackResolver.resolveToPlayableStream(row.stream, season, episode)) {
                is DirectDebridPlayableResult.Success -> result.stream
                else -> {
                    failureMessage = failureMessage ?: result.toastMessage()
                    null
                }
            }
        } else {
            row.stream
        } ?: continue

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
