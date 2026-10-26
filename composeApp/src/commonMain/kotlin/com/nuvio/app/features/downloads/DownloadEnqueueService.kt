package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridSourceFallback
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a download is for: the title and, for an episode, which one. */
internal data class DownloadTarget(
    val contentType: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String?,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
)

/** A queued download ([result]) or why the source could not be resolved ([failure]). */
internal data class DownloadOutcome(
    val result: DownloadEnqueueResult?,
    val failure: DirectDebridPlayableResult? = null,
)

/**
 * The one way every download button queues a download: resolve a debrid source (with the shared
 * not-cached fallback), then enqueue. DownloadEnqueueServiceGuardTest fails when another file calls
 * DownloadsRepository.enqueueFromStream directly.
 */
internal object DownloadEnqueueService {
    /** Queues an already playable [stream] (the player's current source). */
    fun enqueue(target: DownloadTarget, stream: StreamItem): DownloadEnqueueResult =
        DownloadsRepository.enqueueFromStream(
            contentType = target.contentType,
            videoId = target.videoId,
            parentMetaId = target.parentMetaId,
            parentMetaType = target.parentMetaType,
            title = target.title,
            logo = target.logo,
            poster = target.poster,
            background = target.background,
            seasonNumber = target.seasonNumber,
            episodeNumber = target.episodeNumber,
            episodeTitle = target.episodeTitle,
            episodeThumbnail = target.episodeThumbnail,
            stream = stream,
        )

    /** Resolves [stream] (falling back to the next good source when it isn't cached) and queues it. */
    suspend fun resolveAndEnqueue(
        target: DownloadTarget,
        stream: StreamItem,
        notify: Boolean = true,
    ): DownloadOutcome {
        val outcome = DebridSourceFallback.playable(stream, target.seasonNumber, target.episodeNumber, notify) {
            downloadCandidates(target.contentType, target.videoId, target.seasonNumber, target.episodeNumber)
        }
        val playable = outcome.stream
            ?: return DownloadOutcome(null, outcome.failure ?: DirectDebridPlayableResult.Error)
        // Smart downloads get here on a background dispatcher; the repository's line is the main thread's.
        return DownloadOutcome(withContext(Dispatchers.Main.immediate) { enqueue(target, playable) })
    }
}
