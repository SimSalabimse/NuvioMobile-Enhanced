package com.nuvio.app.features.analysis

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
    fun aStaleStageIsVisibleAndAMovingStageIsNot() {
        assertTrue(analysisStageIsStale(lastChangeMs = 0L, nowMs = ANALYSIS_PROGRESS_STALL_MS, limitMs = ANALYSIS_PROGRESS_STALL_MS))
        assertFalse(analysisStageIsStale(lastChangeMs = 0L, nowMs = ANALYSIS_PROGRESS_STALL_MS - 1L, limitMs = ANALYSIS_PROGRESS_STALL_MS))
        assertEquals(0.72, AnalysisJobState.Running("e5", "Evil", AnalysisStage.Matching, -1.0, false).overall)
        assertTrue(AnalysisJobState.Running("e5", "Evil", AnalysisStage.Audio, 1.0, false).overall <= 0.70)
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
