package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsRepository
import com.nuvio.app.features.downloads.FinishedDownloadDeletion
import com.nuvio.app.features.downloads.downloadLogicalContentKey
import com.nuvio.app.features.downloads.isLoopbackPartialPlaybackUrl
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.isIos
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_now_playing_from_download
import org.jetbrains.compose.resources.getString
import kotlin.math.abs

/**
 * "Switch to downloaded file while watching" (Downloads settings, on by default).
 *
 * When the download of the video on screen has finished, the player moves from the stream to the
 * file at the same position. On iOS the file is swapped inside the same mpv instance (`loadfile`
 * with `start=`), so the player screen, the scrobble session and the track/speed/delay/aspect
 * state stay as they are and the last frame stays up while the file opens. Only finished files
 * are used (no early-play loopback), never in the last 2 minutes, never during a seek, and it
 * waits for a pause or a healthy buffer. If the file does not open, the stream comes back at the
 * same position without a message, and that video is not tried again in this player session.
 */
internal enum class DownloadSwapPhase { Loading, Playing, FellBack }

internal data class PlayerDownloadSwap(
    val videoId: String,
    val downloadId: String,
    val streamUrl: String,
    val streamHeaders: Map<String, String>,
    val streamResponseHeaders: Map<String, String>,
    val streamType: String?,
    val localUrl: String,
    val phase: DownloadSwapPhase,
    /** Where the pending load starts; cleared once the load has been seen through. */
    val startMs: Long?,
    val startedAtMs: Long,
    val restore: SwapTrackRestore,
)

internal data class SwapTrackRestore(
    val speed: Float,
    val audio: AudioTrack?,
    val subtitle: SubtitleTrack?,
    val subtitlesOff: Boolean,
    val addonSubtitleUrl: String?,
)

private const val SWAP_TICK_MS = 1_000L
private const val SWAP_END_GUARD_MS = 120_000L
private const val SWAP_MIN_POSITION_MS = 5_000L
private const val SWAP_BUFFER_AHEAD_MS = 10_000L
private const val SWAP_FORCE_AFTER_WAIT_MS = 45_000L
private const val SWAP_STABLE_TICKS = 3
private const val SWAP_LOAD_TIMEOUT_MS = 12_000L

/** The swap keeps the surface (and so the mpv instance) by keeping the playback identity. */
internal val PlayerScreenRuntime.downloadSwapIdentity: String?
    get() = downloadSwap
        ?.takeIf { activeSourceUrl == it.localUrl || activeSourceUrl == it.streamUrl }
        ?.streamUrl

/** A swap load is pending for the URL now active (the per-source reset is skipped for it). */
internal val PlayerScreenRuntime.isDownloadSwapInFlight: Boolean
    get() = downloadSwapIdentity != null && downloadSwap?.startMs != null

/** Start position for the surface when its URL is one side of a pending swap. */
internal fun PlayerScreenRuntime.downloadSwapStartMsFor(surfaceUrl: String?): Long? {
    val swap = downloadSwap ?: return null
    if (surfaceUrl != swap.localUrl && surfaceUrl != swap.streamUrl) return null
    return swap.startMs
}

/** While the new file opens mpv reports 0; the clock (and progress) keeps the swap position. */
internal fun PlayerScreenRuntime.downloadSwapHeldPositionMs(incomingPositionMs: Long): Long {
    val swap = downloadSwap ?: return incomingPositionMs
    val start = swap.startMs ?: return incomingPositionMs
    if (swap.phase == DownloadSwapPhase.Playing) return incomingPositionMs
    return if (incomingPositionMs < 1_000L) start else incomingPositionMs
}

/** The playing item is our own finished download (after a swap): the Download button stays. */
internal val PlayerScreenRuntime.isPlayingSwappedDownload: Boolean
    get() = downloadSwap?.let { it.localUrl == activeSourceUrl } == true

/** Returns true when a load error belongs to the swapped file and the stream was put back. */
internal fun PlayerScreenRuntime.handleDownloadSwapError(message: String?): Boolean {
    val swap = downloadSwap ?: return false
    if (activeSourceUrl != swap.localUrl) return false
    InAppLogger.warn("Player/DownloadSwap", "local file failed, back to stream error=${message.orEmpty()}")
    fallBackToStream(swap)
    return true
}

private fun PlayerScreenRuntime.fallBackToStream(swap: PlayerDownloadSwap) {
    val position = playbackClock.positionMs.takeIf { it > 1_000L } ?: swap.startMs ?: 0L
    downloadSwapGaveUpVideoIds = downloadSwapGaveUpVideoIds + swap.videoId
    downloadSwap = swap.copy(
        phase = DownloadSwapPhase.FellBack,
        startMs = position,
        startedAtMs = nowMs(),
    )
    errorMessage = null
    activeSourceHeaders = swap.streamHeaders
    activeSourceResponseHeaders = swap.streamResponseHeaders
    activeStreamType = swap.streamType
    activeSourceUrl = swap.streamUrl
}

@Composable
internal fun PlayerScreenRuntime.BindDownloadSwitchEffect() {
    LaunchedEffect(activeVideoId) {
        val swap = downloadSwap
        if (swap != null && swap.videoId != activeVideoId.orEmpty()) downloadSwap = null
    }
    LaunchedEffect(Unit) {
        var stableTicks = 0
        var lastPositionMs = -1L
        var lastTickAt = 0L
        var readySince = 0L
        while (isActive) {
            delay(SWAP_TICK_MS)
            val now = nowMs()
            val snapshot = playbackSnapshot
            val positionMs = playbackClock.positionMs

            // Seek detection: while playing the position moves about one tick per tick.
            val expectedDelta = if (snapshot.isPlaying) ((now - lastTickAt) * snapshot.playbackSpeed).toLong() else 0L
            val jumped = lastPositionMs >= 0L && abs((positionMs - lastPositionMs) - expectedDelta) > 2_500L
            stableTicks = if (jumped || snapshot.isLoading) 0 else stableTicks + 1
            lastPositionMs = positionMs
            lastTickAt = now

            val swap = downloadSwap
            if (swap != null && swap.phase != DownloadSwapPhase.Playing && swap.startMs != null) {
                superviseSwapLoad(swap, now)
                continue
            }
            if (swap != null) continue

            val target = switchTargetOrNull()
            if (target == null) {
                readySince = 0L
                continue
            }
            if (readySince == 0L) readySince = now
            val (downloadId, localUrl) = target
            val nearEnd = snapshot.durationMs > 0L && positionMs > snapshot.durationMs - SWAP_END_GUARD_MS
            val seeking = isScrubbingTimeline || isTouchingTimeline || scrubbingPositionMs != null ||
                accumulatedSeekState != null || stableTicks < SWAP_STABLE_TICKS
            if (nearEnd || seeking || positionMs < SWAP_MIN_POSITION_MS) continue
            val bufferedAhead = playbackClock.bufferedPositionMs - positionMs
            val naturalMoment = !snapshot.isPlaying ||
                bufferedAhead >= SWAP_BUFFER_AHEAD_MS ||
                now - readySince >= SWAP_FORCE_AFTER_WAIT_MS
            if (!naturalMoment) continue
            startDownloadSwap(downloadId, localUrl, positionMs, now)
        }
    }
}

/** The finished download of the video on screen, when switching to it is allowed right now. */
private fun PlayerScreenRuntime.switchTargetOrNull(): Pair<String, String>? {
    if (!isIos) return null
    if (!DownloadsSettingsRepository.isSwitchToDownloadWhileWatchingEnabled()) return null
    if (!shouldTrackWatchProgress || errorMessage != null || !initialLoadCompleted || !initialSeekApplied) return null
    if (activeTorrentInfoHash != null || !activeSourceAudioUrl.isNullOrBlank()) return null
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return null
    if (videoId in downloadSwapGaveUpVideoIds) return null
    val url = activeSourceUrl.trim()
    if (url.isEmpty() || url.startsWith("file:") || isLoopbackPartialPlaybackUrl(url)) return null
    // An HLS variant pick plays another URL than the source; leave those alone.
    if (activePlaybackSourceUrl != activeSourceUrl) return null
    val key = downloadLogicalContentKey(parentMetaId, activeSeasonNumber, activeEpisodeNumber)
    val row = DownloadsRepository.uiState.value.items.firstOrNull { it.logicalContentKey == key } ?: return null
    if (row.status != DownloadStatus.Completed) return null
    val localUrl = DownloadsRepository.playableLocalFileUri(row) ?: return null
    return row.id to localUrl
}

private fun PlayerScreenRuntime.startDownloadSwap(downloadId: String, localUrl: String, positionMs: Long, now: Long) {
    val videoId = activeVideoId ?: return
    val restore = SwapTrackRestore(
        speed = playbackSnapshot.playbackSpeed.takeIf { it > 0f } ?: 1f,
        audio = audioTracks.firstOrNull { it.index == selectedAudioIndex } ?: audioTracks.firstOrNull { it.isSelected },
        subtitle = subtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }.takeIf { !useCustomSubtitles },
        subtitlesOff = !useCustomSubtitles && selectedSubtitleIndex < 0,
        addonSubtitleUrl = selectedAddonSubtitleId
            ?.takeIf { useCustomSubtitles }
            ?.let { id -> addonSubtitles.firstOrNull { it.matchesSelection(id) }?.url },
    )
    // Saves progress without ending the scrobble: the same session goes on from the file.
    flushWatchProgress(TrackingScrobbleAction.START)
    downloadSwap = PlayerDownloadSwap(
        videoId = videoId,
        downloadId = downloadId,
        streamUrl = activeSourceUrl,
        streamHeaders = activeSourceHeaders,
        streamResponseHeaders = activeSourceResponseHeaders,
        streamType = activeStreamType,
        localUrl = localUrl,
        phase = DownloadSwapPhase.Loading,
        startMs = positionMs,
        startedAtMs = now,
        restore = restore,
    )
    FinishedDownloadDeletion.linkDownload(videoId, downloadId)
    InAppLogger.info(
        "Player/DownloadSwap",
        "switch to download videoId=$videoId positionMs=$positionMs paused=${!playbackSnapshot.isPlaying} " +
            "file=${InAppLogger.redactUrl(localUrl)}",
    )
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeSourceUrl = localUrl
}

private suspend fun PlayerScreenRuntime.superviseSwapLoad(swap: PlayerDownloadSwap, now: Long) {
    val snapshot = playbackSnapshot
    val target = if (swap.phase == DownloadSwapPhase.FellBack) swap.streamUrl else swap.localUrl
    val loaded = playerControllerSourceUrl == target && !snapshot.isLoading && snapshot.durationMs > 0L &&
        playbackClock.positionMs > 0L
    if (!loaded) {
        if (swap.phase == DownloadSwapPhase.Loading && now - swap.startedAtMs > SWAP_LOAD_TIMEOUT_MS) {
            InAppLogger.warn("Player/DownloadSwap", "local file did not open in time, back to stream")
            fallBackToStream(swap)
        } else if (swap.phase == DownloadSwapPhase.FellBack && now - swap.startedAtMs > SWAP_LOAD_TIMEOUT_MS) {
            downloadSwap = swap.copy(startMs = null)
        }
        return
    }
    restoreTracksAfterSwap(swap.restore)
    if (swap.phase == DownloadSwapPhase.Loading) {
        downloadSwap = swap.copy(phase = DownloadSwapPhase.Playing, startMs = null)
        InAppLogger.info("Player/DownloadSwap", "playing from download positionMs=${playbackClock.positionMs}")
        NuvioToastController.show(getString(Res.string.player_now_playing_from_download))
    } else {
        downloadSwap = swap.copy(startMs = null)
    }
}

/** mpv keeps speed, sub delay and aspect across files; tracks are put back explicitly. */
private fun PlayerScreenRuntime.restoreTracksAfterSwap(restore: SwapTrackRestore) {
    val controller = playerController ?: return
    if (abs(playbackSnapshot.playbackSpeed - restore.speed) > 0.01f) controller.setPlaybackSpeed(restore.speed)
    controller.setSubtitleDelayMs(subtitleDelayMs)
    val newAudio = controller.getAudioTracks()
    restore.audio?.let { wanted ->
        val match = newAudio.firstOrNull { it.id == wanted.id && it.label == wanted.label }
            ?: newAudio.firstOrNull { it.language == wanted.language && it.label == wanted.label }
            ?: newAudio.firstOrNull { it.id == wanted.id }
        if (match != null && !match.isSelected) {
            controller.selectAudioTrack(match.index)
            selectedAudioIndex = match.index
        }
    }
    val newSubs = controller.getSubtitleTracks()
    when {
        restore.addonSubtitleUrl != null -> controller.setSubtitleUri(restore.addonSubtitleUrl)
        restore.subtitle != null -> {
            val wanted = restore.subtitle
            val match = newSubs.firstOrNull { it.id == wanted.id && it.label == wanted.label }
                ?: newSubs.firstOrNull { it.language == wanted.language && it.label == wanted.label }
            if (match != null && !match.isSelected) {
                controller.selectSubtitleTrack(match.index)
                selectedSubtitleIndex = match.index
            }
        }
        restore.subtitlesOff -> if (newSubs.any { it.isSelected }) controller.selectSubtitleTrack(-1)
    }
    refreshTracks()
}

private fun nowMs(): Long = com.nuvio.app.features.streams.epochMs()
