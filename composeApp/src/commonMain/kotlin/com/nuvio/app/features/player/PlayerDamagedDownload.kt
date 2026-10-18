package com.nuvio.app.features.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.features.downloads.DownloadsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Ahead by this much more than the time that passed (at up to 2x speed) counts as a jump. */
private const val DAMAGED_JUMP_MS = 20_000L
/** Opening the file (and the resume seek) is left alone. */
private const val DAMAGED_SETTLE_MS = 10_000L
/** Jumps before the file counts as damaged (a damaged spot jumps again when seeked back to). */
private const val DAMAGED_JUMPS = 2

/** The position moved ahead further than playback (even at 2x) could have taken it. */
internal fun isUnaskedJump(fromMs: Long, toMs: Long, elapsedMs: Long): Boolean =
    toMs - fromMs > elapsedMs * 2 + DAMAGED_JUMP_MS

/** Every seek the app asks for is noted, so only jumps the player made on its own count. */
internal fun PlayerScreenRuntime.noteSeek() {
    lastSeekMark = TimeSource.Monotonic.markNow()
}

/**
 * A downloaded file that breaks while playing (jumps ahead on its own, or does not decode): offers
 * to stream the episode instead and downloads the file again once the player lets go of it.
 */
@Composable
internal fun BoxScope.RenderDamagedDownloadPrompt(runtime: PlayerScreenRuntime) {
    runtime.run {
        val sourceUrl = activeSourceUrl
        val item = remember(sourceUrl, parentMetaId, activeSeasonNumber, activeEpisodeNumber) {
            playingDownload(sourceUrl, parentMetaId, activeSeasonNumber, activeEpisodeNumber)
        } ?: return
        var flagged by remember(item.id) { mutableStateOf(false) }
        var asking by remember(item.id) { mutableStateOf(false) }
        fun damaged(why: String) {
            if (flagged) return
            flagged = true
            asking = true
            InAppLogger.warn("Player/Damaged", "download ${item.id} $why")
        }

        LaunchedEffect(item.id) {
            delay(DAMAGED_SETTLE_MS)
            var jumps = 0
            var lastPosition = playbackClock.positionMs
            var lastAt = TimeSource.Monotonic.markNow()
            while (isActive) {
                delay(1_000L)
                val position = playbackClock.positionMs
                val seekedLately = lastSeekMark?.let { it.elapsedNow() < 5.seconds } == true
                if (playbackSnapshot.isPlaying && !seekedLately && !isScrubbingTimeline &&
                    isUnaskedJump(lastPosition, position, lastAt.elapsedNow().inWholeMilliseconds)
                ) {
                    jumps += 1
                    if (jumps >= DAMAGED_JUMPS) {
                        damaged("jumped ahead $lastPosition -> $position")
                        break
                    }
                }
                lastPosition = position
                lastAt = TimeSource.Monotonic.markNow()
            }
        }
        LaunchedEffect(errorMessage, item.id) {
            errorMessage?.let { damaged("error: $it") }
        }
        // Downloaded again only once nothing plays it any more (stream picked, player closed).
        DisposableEffect(item.id) {
            onDispose { if (flagged) DownloadsRepository.redownloadDamaged(item.id) }
        }

        NuvioStatusModal(
            title = "This download looks damaged",
            message = "It skipped ahead on its own or could not be played. Stream this episode instead? " +
                "The file downloads again when you leave it.",
            isVisible = asking,
            confirmText = "Stream instead",
            dismissText = "Keep playing",
            onConfirm = {
                asking = false
                if (!handleDownloadSwapError("damaged download")) openSourcesPanel()
            },
            onDismiss = { asking = false },
        )
    }
}
