package com.nuvio.app.features.downloads

import com.nuvio.app.features.watching.domain.isProgressComplete
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Device report: watched to 50%, 45% downloaded, the file jumped to the end and was marked watched. */
class PartialFileEndTest {
    private val durationMs = 40 * 60_000L
    private val totalBytes = 1_000_000_000L
    private val reachMs = durationMs * 45 / 100

    @Test
    fun resumeAtHalfIsNotOpenedFromAFileDownloadedToFortyFivePercent() {
        assertFalse(
            partialPlaybackCoversStart(
                startPositionMs = durationMs / 2,
                startFraction = 0.5f,
                playableBytes = totalBytes * 45 / 100,
                totalBytes = totalBytes,
            ),
        )
    }

    @Test
    fun resumeWellInsideTheDownloadedBytesOpensTheFile() {
        assertTrue(partialPlaybackCoversStart(durationMs / 5, 0.2f, totalBytes * 45 / 100, totalBytes))
        assertTrue(partialPlaybackCoversStart(0L, null, 0L, null))
    }

    @Test
    fun resumeThatCannotBeCheckedDoesNotOpenTheFile() {
        assertFalse(partialPlaybackCoversStart(durationMs / 5, null, totalBytes / 2, totalBytes))
        assertFalse(partialPlaybackCoversStart(durationMs / 5, 0.2f, totalBytes / 2, null))
    }

    @Test
    fun endOfAnUnfinishedFileIsNotTheEndOfTheEpisode() {
        val playhead = partialFilePlayhead(
            positionMs = durationMs,
            isEnded = true,
            isPlaying = false,
            downloadFinished = false,
            reachMs = reachMs,
            lastPositionMs = durationMs / 2,
        )

        assertEquals(PartialPlayhead(positionMs = reachMs, isEnded = false), playhead)
        assertFalse(isProgressComplete(playhead.positionMs, durationMs, playhead.isEnded))
    }

    @Test
    fun endWithUnknownReachKeepsTheLastRealPosition() {
        val playhead = partialFilePlayhead(durationMs, isEnded = true, isPlaying = false, downloadFinished = false, reachMs = null, lastPositionMs = 600_000L)

        assertEquals(PartialPlayhead(600_000L, isEnded = false), playhead)
    }

    @Test
    fun positionPastTheDownloadedPartWhilePausedIsHeldToIt() {
        val playhead = partialFilePlayhead(durationMs - 1_000L, isEnded = false, isPlaying = false, downloadFinished = false, reachMs = reachMs, lastPositionMs = 0L)

        assertEquals(reachMs, playhead.positionMs)
        assertFalse(isProgressComplete(playhead.positionMs, durationMs, playhead.isEnded))
    }

    @Test
    fun renderedPositionIsLeftAlone() {
        val playhead = partialFilePlayhead(reachMs + 5_000L, isEnded = false, isPlaying = true, downloadFinished = false, reachMs = reachMs, lastPositionMs = reachMs)

        assertEquals(PartialPlayhead(reachMs + 5_000L, isEnded = false), playhead)
    }

    @Test
    fun finishedDownloadEndsNormally() {
        val playhead = partialFilePlayhead(durationMs, isEnded = true, isPlaying = false, downloadFinished = true, reachMs = durationMs, lastPositionMs = durationMs)

        assertEquals(PartialPlayhead(durationMs, isEnded = true), playhead)
        assertTrue(isProgressComplete(playhead.positionMs, durationMs, playhead.isEnded))
    }
}
