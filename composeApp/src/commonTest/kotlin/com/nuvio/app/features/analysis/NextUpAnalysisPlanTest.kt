package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextUpAnalysisPlanTest {
    @Test
    fun introThenCreditsWhileCool() {
        val plan = nextUpAnalysisPlan(
            introDone = false,
            creditsDone = false,
            atThreshold = false,
            videoPlaying = true,
            thermal = AnalysisThermal.Fair,
            lowPowerMode = false,
        )
        assertEquals(listOf("intro", "credits"), plan)
    }

    @Test
    fun heatDuringPlaybackWaitsUntilPause() {
        val hot = nextUpAnalysisPlan(
            introDone = false,
            creditsDone = false,
            atThreshold = false,
            videoPlaying = true,
            thermal = AnalysisThermal.Serious,
            lowPowerMode = false,
        )
        assertEquals(emptyList(), hot)
        val paused = nextUpAnalysisPlan(
            introDone = false,
            creditsDone = true,
            atThreshold = false,
            videoPlaying = false,
            thermal = AnalysisThermal.Critical,
            lowPowerMode = true,
        )
        assertEquals(listOf("intro"), paused)
    }

    @Test
    fun theThresholdRunsWhateverIsLeft() {
        val plan = nextUpAnalysisPlan(
            introDone = true,
            creditsDone = false,
            atThreshold = true,
            videoPlaying = true,
            thermal = AnalysisThermal.Critical,
            lowPowerMode = true,
        )
        assertEquals(listOf("credits"), plan)
    }

    @Test
    fun autoplayWaitsForBothWindows() {
        assertFalse(nextUpAutoplayReady(introDone = true, creditsDone = false))
        assertTrue(nextUpAutoplayReady(introDone = true, creditsDone = true))
    }
}
