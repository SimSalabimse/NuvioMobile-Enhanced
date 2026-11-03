package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipUndo
import com.nuvio.app.features.player.skip.SkipUndoState
import com.nuvio.app.features.stats.WatchStats
import com.nuvio.app.features.streams.epochMs
import kotlinx.coroutines.delay

internal fun PlayerScreenRuntime.offerSkipUndo(fromMs: Long, savedMs: Long, interval: SkipInterval) {
    skipUndo = SkipUndo.offer(
        state = skipUndo,
        fromMs = fromMs,
        nowMs = epochMs(),
        segmentStartMs = secondsToMs(interval.startTime),
        segmentEndMs = secondsToMs(interval.endTime),
        savedMs = savedMs,
        partyLocked = isPartyControlLocked,
        seenManualSeek = lastManualSkipSeekPositions,
    )
}

internal fun PlayerScreenRuntime.undoSkip() {
    val tap = SkipUndo.undo(skipUndo, epochMs())
    skipUndo = tap.state
    val target = tap.seekToMs ?: return
    if (tap.subtractMs > 0L) WatchStats.recordSkip(-tap.subtractMs)
    noteSeek()
    playerController?.seekTo(target)
    scheduleProgressSyncAfterSeek()
    publishPartyPlayback(positionMs = target)
}

@Composable
internal fun PlayerScreenRuntime.BindSkipUndoEffects() {
    LaunchedEffect(activeVideoId, parentMetaId, activeSeasonNumber, activeEpisodeNumber) {
        skipUndo = SkipUndo.onEpisodeChanged()
    }
    LaunchedEffect(activeSkipInterval) {
        val interval = activeSkipInterval
        skipUndo = if (interval == null) {
            SkipUndo.dismiss(skipUndo)
        } else {
            SkipUndo.noteSegment(
                skipUndo,
                secondsToMs(interval.startTime),
                secondsToMs(interval.endTime),
            )
        }
    }
    LaunchedEffect(skipUndo) {
        val skipped = skipUndo as? SkipUndoState.Skipped ?: return@LaunchedEffect
        val wait = skipped.untilMs - epochMs()
        if (wait > 0L) delay(wait)
        skipUndo = SkipUndo.tick(skipUndo, epochMs())
    }
    LaunchedEffect(Unit) {
        snapshotFlow { playbackClock.positionMs to lastManualSkipSeekPositions }
            .collect { (positionMs, manualSeek) ->
                skipUndo = SkipUndo.observe(
                    state = skipUndo,
                    nowMs = epochMs(),
                    positionMs = positionMs,
                    partyLocked = isPartyControlLocked,
                    manualSeek = manualSeek,
                )
            }
    }
    LaunchedEffect(Unit) {
        PartyRepository.state.collect {
            if (!isPartyControlLocked) return@collect
            skipUndo = SkipUndo.observe(
                state = skipUndo,
                nowMs = epochMs(),
                positionMs = playbackClock.positionMs,
                partyLocked = true,
                manualSeek = lastManualSkipSeekPositions,
            )
        }
    }
}

private fun secondsToMs(seconds: Double): Long = (seconds * 1000.0).toLong()
