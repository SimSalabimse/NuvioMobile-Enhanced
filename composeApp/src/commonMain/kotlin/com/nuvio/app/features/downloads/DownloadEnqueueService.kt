package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridSourceFallback
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.resume

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
 * The one way every download button queues a download: show a Preparing row at once, resolve a
 * debrid source (with the shared not-cached fallback) in the background, then start or fail that
 * same row. DownloadEnqueueServiceGuardTest fails when another file calls
 * DownloadsRepository.enqueueFromStream directly.
 */
internal object DownloadEnqueueService {
    private val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /**
     * Shows the download immediately, then resolves [stream] (falling back to the next good source
     * when it isn't cached). The caller waits for the final result. Cancelling the row stops the
     * resolve; cancelling the caller does not, so closing the sheet still finishes this download.
     */
    suspend fun resolveAndEnqueue(
        target: DownloadTarget,
        stream: StreamItem,
        notify: Boolean = true,
    ): DownloadOutcome {
        val id = withContext(Dispatchers.Main.immediate) {
            DownloadsRepository.beginPreparation(target, stream)
        }
        return suspendCancellableCoroutine { cont ->
            val job = preparationScope.launch {
                val outcome = try {
                    yield()
                    resolvePrepared(id, target, stream, notify)
                } catch (cancelled: CancellationException) {
                    DownloadOutcome(null, null)
                }
                if (cont.isActive) cont.resume(outcome)
            }
            DownloadsRepository.bindPreparation(id, job)
        }
    }

    private suspend fun resolvePrepared(
        id: String,
        target: DownloadTarget,
        stream: StreamItem,
        notify: Boolean,
    ): DownloadOutcome {
        val outcome = try {
            DebridSourceFallback.playable(stream, target.seasonNumber, target.episodeNumber, notify) {
                downloadCandidates(target.contentType, target.videoId, target.seasonNumber, target.episodeNumber)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "Couldn't prepare the download"
            withContext(Dispatchers.Main.immediate) { DownloadsRepository.failPreparation(id, message) }
            return DownloadOutcome(null, DirectDebridPlayableResult.Error)
        }
        val playable = outcome.stream
        if (playable == null) {
            val failure = outcome.failure ?: DirectDebridPlayableResult.Error
            val message = failure.toastMessage() ?: "Couldn't prepare the download"
            withContext(Dispatchers.Main.immediate) { DownloadsRepository.failPreparation(id, message) }
            return DownloadOutcome(null, failure)
        }
        val result = withContext(Dispatchers.Main.immediate) {
            DownloadsRepository.finishPreparation(id, playable)
        }
        return if (result == null) DownloadOutcome(null, null) else DownloadOutcome(result)
    }
}
