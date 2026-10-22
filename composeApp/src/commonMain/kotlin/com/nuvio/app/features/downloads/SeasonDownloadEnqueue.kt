package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridAddOffer
import com.nuvio.app.features.debrid.DebridSourceFallback
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
    var offered = false
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
        val videoId = seasonStreamVideoId(meta.id, row.video)
        val outcome = DebridSourceFallback.playable(row.stream, season, episode, notify = false) {
            downloadCandidates(meta.type, videoId, season, episode)
        }
        val resolved = outcome.stream ?: run {
            val failure = outcome.failure ?: DirectDebridPlayableResult.Error
            // One add offer for the batch (the first uncached episode), not one per row.
            if (failure is DirectDebridPlayableResult.NotCached && !offered) offered = DebridAddOffer.offer(row.stream)
            failureMessage = failureMessage ?: failure.toastMessage()
            null
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

/** Other sources for an episode to fall back to when a download's pick isn't usable, best quality first. */
internal suspend fun downloadCandidates(type: String, videoId: String, season: Int?, episode: Int?): List<StreamItem> =
    SeasonStreamCatalog.load(type, videoId, season, episode, annotateDebridCache = true)
        .orderedForQuality(SmartDownloadSettings.snapshot().quality)
        .filter { it.isSeasonDownloadCandidate() }
