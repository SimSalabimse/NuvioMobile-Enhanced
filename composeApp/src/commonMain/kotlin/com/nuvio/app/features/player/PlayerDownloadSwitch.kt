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
 * When the download of the video on screen has finished, or is far enough ahead and fast enough
 * (see [partialSwitchAllowed]), the player moves from the stream to the file at the same position.
 * A partial file plays through the early-play loopback server; a watchdog moves back to the stream
 * before playback can catch up with the downloaded bytes. On iOS the file is swapped inside the same mpv instance (`loadfile`
 * with `start=`), so the player screen, the scrobble session and the track/speed/delay/aspect
 * state stay as they are and the last frame stays up while the file opens. Never in the last
 * 2 minutes, never during a seek, and it
 * waits for a pause or a healthy buffer. If the file does not open, the stream comes back at the
 * same position without a message. A finished file that fails is not tried again for that video in
 * this player session; a partial file that had to hand back needs a much larger lead next time, and
 * after [PARTIAL_MAX_FALLBACKS] hand-backs only the finished file is used.
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
    /** Playing a still-downloading file through the loopback server (watchdog active). */
    val partial: Boolean,
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

// Partial (still downloading) files.
/** The byte-rate estimate of how far the file reaches is cut by this much (VBR, headers). */
private const val PARTIAL_REACH_MARGIN = 0.90
/** Downloaded video needed past the current position before a first partial switch. */
private const val PARTIAL_ENTRY_LEAD_MS = 5 * 60_000L
/** After a hand-back to the stream, the lead needed before trying the partial file again. */
private const val PARTIAL_RETRY_LEAD_MS = 15 * 60_000L
/** Below this lead the watchdog hands back to the stream. */
private const val PARTIAL_FLOOR_LEAD_MS = 60_000L
/** The download has to run at least this many times the playback byte rate... */
private const val PARTIAL_SPEED_FACTOR = 1.5
/** ...over this window, and every [PARTIAL_SPEED_SLICE_MS] slice of it at least [PARTIAL_SLICE_FACTOR]. */
private const val PARTIAL_SPEED_WINDOW_MS = 30_000L
private const val PARTIAL_SPEED_SLICE_MS = 10_000L
private const val PARTIAL_SLICE_FACTOR = 1.1
/** No new bytes for this long while on the partial file counts as a stall. */
private const val PARTIAL_STALL_MS = 15_000L
private const val PARTIAL_MAX_FALLBACKS = 2

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
    InAppLogger.warn(
        "Player/DownloadSwap",
        "local file failed partial=${swap.partial}, back to stream error=${message.orEmpty()}",
    )
    fallBackToStream(swap, partialHandBack = swap.partial)
    return true
}

/** The partial file ran out (mpv saw its end) before the download finished: back to the stream. */
internal fun PlayerScreenRuntime.handleDownloadSwapPartialEnd(): Boolean {
    val swap = downloadSwap ?: return false
    if (!swap.partial || activeSourceUrl != swap.localUrl) return false
    val row = DownloadsRepository.uiState.value.items.firstOrNull { it.id == swap.downloadId }
    if (row?.status == DownloadStatus.Completed) return false
    InAppLogger.warn("Player/DownloadSwap", "partial file ended early, back to stream")
    fallBackToStream(swap, partialHandBack = true)
    return true
}

private fun PlayerScreenRuntime.fallBackToStream(swap: PlayerDownloadSwap, partialHandBack: Boolean = false) {
    val position = playbackClock.positionMs.takeIf { it > 1_000L } ?: swap.startMs ?: 0L
    if (partialHandBack) {
        downloadSwapPartialFallbacks += 1
    } else {
        downloadSwapGaveUpVideoIds = downloadSwapGaveUpVideoIds + swap.videoId
    }
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
        downloadSwapPartialFallbacks = 0
    }
    LaunchedEffect(Unit) {
        var stableTicks = 0
        var lastPositionMs = -1L
        var lastTickAt = 0L
        var readySince = 0L
        val samples = DownloadByteSamples()
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
            if (swap != null && swap.phase == DownloadSwapPhase.Playing) {
                if (swap.partial) watchPartialSwap(swap, samples, now)
                continue
            }

            val target = switchTargetOrNull()
            if (target == null) {
                readySince = 0L
                samples.clear()
                continue
            }
            if (target.partial) {
                samples.add(target.downloadId, now, DownloadsRepository.playableContiguousBytes(target.downloadId))
                if (!partialSwitchAllowed(target.downloadId, samples, positionMs, snapshot.durationMs, now)) {
                    readySince = 0L
                    continue
                }
            }
            if (readySince == 0L) readySince = now
            val nearEnd = snapshot.durationMs > 0L && positionMs > snapshot.durationMs - SWAP_END_GUARD_MS
            val seeking = isScrubbingTimeline || isTouchingTimeline || scrubbingPositionMs != null ||
                accumulatedSeekState != null || stableTicks < SWAP_STABLE_TICKS
            if (nearEnd || seeking || positionMs < SWAP_MIN_POSITION_MS) continue
            val bufferedAhead = playbackClock.bufferedPositionMs - positionMs
            val naturalMoment = !snapshot.isPlaying ||
                bufferedAhead >= SWAP_BUFFER_AHEAD_MS ||
                now - readySince >= SWAP_FORCE_AFTER_WAIT_MS
            if (!naturalMoment) continue
            startDownloadSwap(target, positionMs, now)
        }
    }
}

private class SwitchTarget(val downloadId: String, val localUrl: String, val partial: Boolean)

/** Contiguous-byte samples of one download, for its recent speed and stall detection. */
private class DownloadByteSamples {
    private var downloadId: String? = null
    private val points = ArrayDeque<Pair<Long, Long>>()

    fun clear() {
        downloadId = null
        points.clear()
    }

    fun add(id: String, atMs: Long, bytes: Long?) {
        if (bytes == null) return
        if (downloadId != id) {
            clear()
            downloadId = id
        }
        points.addLast(atMs to bytes)
        while (points.size > 2 && atMs - points.first().first > PARTIAL_SPEED_WINDOW_MS + 5_000L) points.removeFirst()
    }

    /** Bytes per ms over the last [windowMs], or null without enough history. */
    fun rate(nowMs: Long, windowMs: Long): Double? {
        val last = points.lastOrNull() ?: return null
        val start = points.firstOrNull { nowMs - it.first <= windowMs } ?: return null
        val first = points.firstOrNull() ?: return null
        if (last.first - first.first < windowMs - 1_500L) return null
        val span = last.first - start.first
        if (span <= 0L) return null
        return (last.second - start.second).toDouble() / span.toDouble()
    }

    /** True when every slice of the window is at least [minRate]. */
    fun steadyAtLeast(nowMs: Long, minRate: Double): Boolean {
        var sliceEnd = nowMs
        repeat((PARTIAL_SPEED_WINDOW_MS / PARTIAL_SPEED_SLICE_MS).toInt()) {
            val endPoint = points.lastOrNull { it.first <= sliceEnd } ?: return false
            val startPoint = points.lastOrNull { it.first <= sliceEnd - PARTIAL_SPEED_SLICE_MS } ?: return false
            val span = endPoint.first - startPoint.first
            if (span <= 0L) return false
            if ((endPoint.second - startPoint.second).toDouble() / span < minRate) return false
            sliceEnd -= PARTIAL_SPEED_SLICE_MS
        }
        return true
    }

    /** How long the byte count has not moved. */
    fun stalledForMs(nowMs: Long): Long {
        val last = points.lastOrNull() ?: return 0L
        val firstSame = points.firstOrNull { it.second == last.second } ?: return 0L
        return nowMs - firstSame.first
    }
}

/** Video time the contiguous downloaded bytes safely reach (byte-rate estimate with a margin). */
private fun PlayerScreenRuntime.partialSafeReachMs(downloadId: String, durationMs: Long): Long? {
    if (durationMs <= 0L) return null
    val row = DownloadsRepository.uiState.value.items.firstOrNull { it.id == downloadId } ?: return null
    if (row.status == DownloadStatus.Completed) return durationMs
    val total = row.totalBytes?.takeIf { it > 0L } ?: return null
    val bytes = DownloadsRepository.playableContiguousBytes(downloadId) ?: return null
    val fraction = (bytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    return (durationMs * fraction * PARTIAL_REACH_MARGIN).toLong()
}

/** Playback byte rate (bytes per ms of video), from file size over duration. */
private fun playbackBytesPerMs(totalBytes: Long?, durationMs: Long): Double? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    if (durationMs <= 0L) return null
    return total.toDouble() / durationMs.toDouble()
}

private fun PlayerScreenRuntime.partialSwitchAllowed(
    downloadId: String,
    samples: DownloadByteSamples,
    positionMs: Long,
    durationMs: Long,
    now: Long,
): Boolean {
    val row = DownloadsRepository.uiState.value.items.firstOrNull { it.id == downloadId } ?: return false
    if (row.status != DownloadStatus.Downloading) return false
    val reach = partialSafeReachMs(downloadId, durationMs) ?: return false
    val neededLead = if (downloadSwapPartialFallbacks > 0) PARTIAL_RETRY_LEAD_MS else PARTIAL_ENTRY_LEAD_MS
    if (reach - positionMs < neededLead) return false
    val byteRate = playbackBytesPerMs(row.totalBytes, durationMs) ?: return false
    val speed = samples.rate(now, PARTIAL_SPEED_WINDOW_MS) ?: return false
    if (speed < byteRate * PARTIAL_SPEED_FACTOR) return false
    return samples.steadyAtLeast(now, byteRate * PARTIAL_SLICE_FACTOR)
}

/** On the partial file: hand back to the stream before playback can reach the downloaded end. */
private fun PlayerScreenRuntime.watchPartialSwap(swap: PlayerDownloadSwap, samples: DownloadByteSamples, now: Long) {
    if (activeSourceUrl != swap.localUrl) return
    val row = DownloadsRepository.uiState.value.items.firstOrNull { it.id == swap.downloadId }
    if (row?.status == DownloadStatus.Completed) {
        // Every byte is there now; the loopback server serves the finished file.
        downloadSwap = swap.copy(partial = false)
        InAppLogger.info("Player/DownloadSwap", "download finished while playing it, watchdog off")
        return
    }
    val reason: String? = when {
        row == null -> "download removed"
        row.status != DownloadStatus.Downloading -> "download ${row.status.name.lowercase()}"
        else -> {
            samples.add(swap.downloadId, now, DownloadsRepository.playableContiguousBytes(swap.downloadId))
            val reach = partialSafeReachMs(swap.downloadId, playbackSnapshot.durationMs)
            val watched = maxOf(playbackClock.positionMs, scrubbingPositionMs ?: 0L)
            when {
                samples.stalledForMs(now) >= PARTIAL_STALL_MS -> "download stalled"
                reach == null -> null
                reach - watched < PARTIAL_FLOOR_LEAD_MS -> "lead ${(reach - watched) / 1000}s below floor"
                else -> null
            }
        }
    }
    if (reason == null) return
    InAppLogger.warn("Player/DownloadSwap", "partial file hand-back: $reason")
    fallBackToStream(swap, partialHandBack = true)
}

/** The download of the video on screen, when switching to it may be allowed. */
private fun PlayerScreenRuntime.switchTargetOrNull(): SwitchTarget? {
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
    return when (row.status) {
        DownloadStatus.Completed -> {
            val localUrl = DownloadsRepository.playableLocalFileUri(row) ?: return null
            SwitchTarget(row.id, localUrl, partial = false)
        }
        DownloadStatus.Downloading -> {
            if (downloadSwapPartialFallbacks >= PARTIAL_MAX_FALLBACKS) return null
            // Only files the early-play check found playable from the front (moov first, etc.).
            if (!row.earlyPlayReady || row.playsWhenDownloadFinishes) return null
            if (row.totalBytes == null || row.totalBytes <= 0L) return null
            val partialUrl = DownloadsRepository.playableLocalFileUri(row) ?: return null
            if (!isLoopbackPartialPlaybackUrl(partialUrl)) return null
            SwitchTarget(row.id, partialUrl, partial = true)
        }
        else -> null
    }
}

private fun PlayerScreenRuntime.startDownloadSwap(target: SwitchTarget, positionMs: Long, now: Long) {
    val videoId = activeVideoId ?: return
    val downloadId = target.downloadId
    val localUrl = target.localUrl
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
        partial = target.partial,
        phase = DownloadSwapPhase.Loading,
        startMs = positionMs,
        startedAtMs = now,
        restore = restore,
    )
    FinishedDownloadDeletion.linkDownload(videoId, downloadId)
    InAppLogger.info(
        "Player/DownloadSwap",
        "switch to download partial=${target.partial} videoId=$videoId positionMs=$positionMs paused=${!playbackSnapshot.isPlaying} " +
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
            InAppLogger.warn("Player/DownloadSwap", "local file did not open in time partial=${swap.partial}, back to stream")
            fallBackToStream(swap, partialHandBack = swap.partial)
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
