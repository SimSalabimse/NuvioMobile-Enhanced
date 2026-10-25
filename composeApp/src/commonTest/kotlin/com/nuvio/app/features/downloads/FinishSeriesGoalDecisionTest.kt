package com.nuvio.app.features.downloads

import com.nuvio.app.features.watchprogress.isWatchProgressComplete
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinishSeriesGoalDecisionTest {
    private val durationMs = 44 * 60_000L
    /** 8 minutes left on a 44-minute episode: about 82 %, where the smart-download trigger fires. */
    private val eightMinutesLeft = durationMs - 8 * 60_000L
    private val lastIndex = 9

    @Test
    fun lastEpisodeAtEightyTwoPercentKeepsTheGoal() {
        assertEquals(0.82, eightMinutesLeft.toDouble() / durationMs.toDouble(), 0.01)
        assertFalse(PlaybackEndRules.reachedEnd(eightMinutesLeft, durationMs, null))
        assertFalse(isWatchProgressComplete(eightMinutesLeft, durationMs, isEnded = false))
        assertTrue(
            resumeStillInProgress(
                positionMs = eightMinutesLeft,
                durationMs = durationMs,
                markedCompleted = true,
            ),
        )

        val nearEnd = decideFinishSeriesGoal(
            regularEpisodeCount = lastIndex + 1,
            playingEpisodeIndex = lastIndex,
            nextEpisodeIndex = lastIndex + 1,
            lastEpisodePositionMs = eightMinutesLeft,
            lastEpisodeDurationMs = durationMs,
            lastEpisodeMarkedCompleted = true,
            playbackReachedEnd = false,
            playbackCompleted = false,
        )
        assertEquals(FinishSeriesGoalAction.KeepPinned, nearEnd.action)
        assertEquals(FINISHING_LAST_EPISODE_STATUS, nearEnd.statusText)
        assertNull(nearEnd.removalReason)

        val watchedMarker = decideFinishSeriesGoal(
            regularEpisodeCount = lastIndex + 1,
            playingEpisodeIndex = null,
            nextEpisodeIndex = lastIndex + 1,
            lastEpisodePositionMs = eightMinutesLeft,
            lastEpisodeDurationMs = durationMs,
            lastEpisodeMarkedCompleted = true,
            playbackReachedEnd = false,
            playbackCompleted = false,
        )
        assertEquals(FinishSeriesGoalAction.KeepPinned, watchedMarker.action)
        assertNull(watchedMarker.removalReason)
    }

    @Test
    fun lastEpisodeTrulyCompletedRemovesTheGoalWithAToast() {
        val atEnd = durationMs - 60_000L
        assertTrue(PlaybackEndRules.reachedEnd(atEnd, durationMs, null))
        val byEnd = decideFinishSeriesGoal(
            regularEpisodeCount = lastIndex + 1,
            playingEpisodeIndex = lastIndex,
            nextEpisodeIndex = lastIndex + 1,
            lastEpisodePositionMs = atEnd,
            lastEpisodeDurationMs = durationMs,
            lastEpisodeMarkedCompleted = true,
            playbackReachedEnd = true,
            playbackCompleted = isWatchProgressComplete(atEnd, durationMs, isEnded = false),
        )
        assertEquals(FinishSeriesGoalAction.RemoveAndToast, byEnd.action)
        val endReason = assertNotNull(byEnd.removalReason)
        assertTrue(endReason.contains("reached the end"))
        assertNull(byEnd.statusText)

        val atNinetyPercent = (durationMs * 0.90).toLong()
        assertTrue(isWatchProgressComplete(atNinetyPercent, durationMs, isEnded = false))
        assertFalse(PlaybackEndRules.reachedEnd(atNinetyPercent, durationMs, null))
        val byRule = decideFinishSeriesGoal(
            regularEpisodeCount = lastIndex + 1,
            playingEpisodeIndex = lastIndex,
            nextEpisodeIndex = lastIndex + 1,
            lastEpisodePositionMs = atNinetyPercent,
            lastEpisodeDurationMs = durationMs,
            lastEpisodeMarkedCompleted = true,
            playbackReachedEnd = false,
            playbackCompleted = true,
        )
        assertEquals(FinishSeriesGoalAction.RemoveAndToast, byRule.action)
        val ruleReason = assertNotNull(byRule.removalReason)
        assertTrue(ruleReason.contains("completed"))

        val resumeCompleted = decideFinishSeriesGoal(
            regularEpisodeCount = lastIndex + 1,
            playingEpisodeIndex = null,
            nextEpisodeIndex = lastIndex + 1,
            lastEpisodePositionMs = durationMs,
            lastEpisodeDurationMs = durationMs,
            lastEpisodeMarkedCompleted = true,
            playbackReachedEnd = false,
            playbackCompleted = false,
        )
        assertEquals(FinishSeriesGoalAction.RemoveAndToast, resumeCompleted.action)
        assertNotNull(resumeCompleted.removalReason)
    }

    @Test
    fun midSeriesEpisodeNearEndAdvancesTheAnchor() {
        val decision = decideFinishSeriesGoal(
            regularEpisodeCount = lastIndex + 1,
            playingEpisodeIndex = 4,
            nextEpisodeIndex = 5,
            lastEpisodePositionMs = eightMinutesLeft,
            lastEpisodeDurationMs = durationMs,
            lastEpisodeMarkedCompleted = false,
            playbackReachedEnd = PlaybackEndRules.reachedEnd(eightMinutesLeft, durationMs, null),
            playbackCompleted = isWatchProgressComplete(eightMinutesLeft, durationMs, isEnded = false),
        )
        assertEquals(FinishSeriesGoalAction.AdvanceAnchor, decision.action)
        assertNull(decision.removalReason)
        assertNull(decision.statusText)

        val endedMidSeries = decideFinishSeriesGoal(
            regularEpisodeCount = lastIndex + 1,
            playingEpisodeIndex = 4,
            nextEpisodeIndex = 5,
            lastEpisodePositionMs = durationMs,
            lastEpisodeDurationMs = durationMs,
            lastEpisodeMarkedCompleted = true,
            playbackReachedEnd = true,
            playbackCompleted = true,
        )
        assertEquals(FinishSeriesGoalAction.AdvanceAnchor, endedMidSeries.action)
    }
}
