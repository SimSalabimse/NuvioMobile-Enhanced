package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisThrottleLevelTest {
    @Test
    fun playingKeepsTodaysLevelAndPauseDoesNotSleep() {
        assertEquals(1, analysisThrottleLevel(PlaybackPriority.Level.Playing, videoPlaying = true, boosted = false))
        assertEquals(2, analysisThrottleLevel(PlaybackPriority.Level.Strained, videoPlaying = true, boosted = false))
        assertEquals(0, analysisThrottleLevel(PlaybackPriority.Level.Normal, videoPlaying = false, boosted = false))
        assertEquals(0, analysisThrottleLevel(PlaybackPriority.Level.Playing, videoPlaying = false, boosted = false))
        assertEquals(0, analysisThrottleLevel(PlaybackPriority.Level.Strained, videoPlaying = false, boosted = false))
        assertEquals(0, analysisThrottleLevel(PlaybackPriority.Level.Playing, videoPlaying = true, boosted = true))
        assertEquals(0, analysisThrottleLevel(PlaybackPriority.Level.Strained, videoPlaying = true, boosted = true))
    }
}
