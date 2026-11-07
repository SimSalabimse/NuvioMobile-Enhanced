package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisQueueTest {
    private val season = listOf("e5", "e6", "e7", "e8")

    @Test
    fun finishedJobSelectsTheNextQueuedEpisode() {
        assertEquals(
            "e6",
            nextQueuedAnalysisId(season, finishedId = "e5", failedIds = emptySet(), backingOffIds = emptySet()),
        )
        assertEquals(
            "e7",
            nextQueuedAnalysisId(season, finishedId = "e6", failedIds = emptySet(), backingOffIds = emptySet()),
        )
        assertNull(
            nextQueuedAnalysisId(listOf("e8"), finishedId = "e8", failedIds = emptySet(), backingOffIds = emptySet()),
        )
    }

    @Test
    fun exceptionAndCancellationDoNotKeepTheFailedEpisodeRunning() {
        val afterException = nextQueuedAnalysisId(
            orderedIds = listOf("e6", "e7", "e8"),
            finishedId = null,
            failedIds = setOf("e6"),
            backingOffIds = emptySet(),
        )
        assertEquals("e7", afterException)
        assertEquals(
            AnalysisAttemptKind.Cancelled,
            classifyAnalysisAttempt(scopeActive = false, analyserFailed = true, clipUnavailable = true),
        )
        assertEquals(
            AnalysisAttemptKind.Retryable,
            classifyAnalysisAttempt(scopeActive = true, analyserFailed = true, clipUnavailable = false),
        )
    }

    @Test
    fun unknownProgressDoesNotCountAsMovement() {
        assertFalse(analysisProgressMoved(previous = Double.NaN, progress = Double.NaN))
        assertFalse(analysisProgressMoved(previous = 0.2, progress = Double.NaN))
        assertFalse(analysisProgressMoved(previous = 0.2, progress = 0.2))
        assertTrue(analysisProgressMoved(previous = Double.NaN, progress = 0.2))
    }

    @Test
    fun matchingStaysAtSeventyTwoWhileTheClockMoves() {
        val matching = AnalysisJobState.Running(
            "e5",
            "Evil",
            AnalysisStage.Matching,
            -1.0,
            false,
            activity = "match",
            activitySinceEpochMs = 1_000L,
        )
        assertEquals(0.72, matching.overall)
        assertEquals(0, analysisElapsedMinutes(1_000L, 1_000L + 59_000L))
        assertEquals(11, analysisElapsedMinutes(1_000L, 1_000L + 11 * 60_000L))
        assertEquals(0.72, matching.copy(activity = "clip", activitySinceEpochMs = 2_000L).overall)
        assertTrue(AnalysisJobState.Running("e5", "Evil", AnalysisStage.Audio, 1.0, false).overall <= 0.70)
    }

    @Test
    fun pausedOrIncompleteIsNotEligible() {
        assertTrue(analysisAcceptsDownload(DownloadStatus.Completed))
        assertFalse(analysisAcceptsDownload(DownloadStatus.Paused))
        assertFalse(analysisAcceptsDownload(DownloadStatus.Downloading))
        assertFalse(analysisAcceptsDownload(DownloadStatus.Queued))
        assertFalse(analysisAcceptsDownload(DownloadStatus.Failed))
    }

    @Test
    fun backingOffSkipsTheEpisodeUntilTheNextOne() {
        assertEquals(
            "e8",
            nextQueuedAnalysisId(
                orderedIds = season,
                finishedId = "e5",
                failedIds = emptySet(),
                backingOffIds = setOf("e6", "e7"),
            ),
        )
    }
}
