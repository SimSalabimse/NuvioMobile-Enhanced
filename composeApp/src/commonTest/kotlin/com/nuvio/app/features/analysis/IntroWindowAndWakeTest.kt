package com.nuvio.app.features.analysis

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntroWindowAndWakeTest {
    @Test
    fun theIntroWindowRunsOnlyForACoolDownloadedEpisodeWithNoMarks() {
        assertTrue(
            shouldRunIntroWindow(
                atNextEpisodeThreshold = true,
                nextEpisodeDownloaded = true,
                hasOnDeviceMarks = false,
                thermal = AnalysisThermal.Nominal,
                lowPowerMode = false,
            ),
        )
        assertTrue(
            shouldRunIntroWindow(
                atNextEpisodeThreshold = true,
                nextEpisodeDownloaded = true,
                hasOnDeviceMarks = false,
                thermal = AnalysisThermal.Fair,
                lowPowerMode = false,
            ),
        )
        assertFalse(
            shouldRunIntroWindow(
                atNextEpisodeThreshold = false,
                nextEpisodeDownloaded = true,
                hasOnDeviceMarks = false,
                thermal = AnalysisThermal.Nominal,
                lowPowerMode = false,
            ),
        )
        assertFalse(
            shouldRunIntroWindow(
                atNextEpisodeThreshold = true,
                nextEpisodeDownloaded = false,
                hasOnDeviceMarks = false,
                thermal = AnalysisThermal.Nominal,
                lowPowerMode = false,
            ),
        )
        assertFalse(
            shouldRunIntroWindow(
                atNextEpisodeThreshold = true,
                nextEpisodeDownloaded = true,
                hasOnDeviceMarks = true,
                thermal = AnalysisThermal.Nominal,
                lowPowerMode = false,
            ),
        )
        assertFalse(
            shouldRunIntroWindow(
                atNextEpisodeThreshold = true,
                nextEpisodeDownloaded = true,
                hasOnDeviceMarks = false,
                thermal = AnalysisThermal.Serious,
                lowPowerMode = false,
            ),
        )
        assertFalse(
            shouldRunIntroWindow(
                atNextEpisodeThreshold = true,
                nextEpisodeDownloaded = true,
                hasOnDeviceMarks = false,
                thermal = AnalysisThermal.Nominal,
                lowPowerMode = true,
            ),
        )
    }

    @Test
    fun storedIntroRecapOrUserMarksCountAsOnDeviceMarks() {
        assertFalse(null.hasOnDeviceMarks())
        assertTrue(result(introStartMs = 0L, introEndMs = 90_000L).hasOnDeviceMarks())
        assertTrue(result(recapStartMs = 0L, recapEndMs = 40_000L).hasOnDeviceMarks())
        assertTrue(
            result(userSegments = mapOf("intro" to UserSegment(startMs = 0L, endMs = 1_000L))).hasOnDeviceMarks(),
        )
        assertFalse(result(userSegments = mapOf("outro" to UserSegment(startMs = 0L, endMs = 1_000L))).hasOnDeviceMarks())
    }

    @Test
    fun theIntroWindowAbortsWhenThePhoneHeatsUpOrEntersLowPowerMode() {
        assertFalse(introWindowShouldAbort(AnalysisThermal.Nominal, lowPowerMode = false))
        assertFalse(introWindowShouldAbort(AnalysisThermal.Fair, lowPowerMode = false))
        assertTrue(introWindowShouldAbort(AnalysisThermal.Serious, lowPowerMode = false))
        assertTrue(introWindowShouldAbort(AnalysisThermal.Critical, lowPowerMode = false))
        assertTrue(introWindowShouldAbort(AnalysisThermal.Nominal, lowPowerMode = true))
    }

    @Test
    fun kickZeroEndsThePollSleepAndALaterKickDoesNot() = runTest {
        val wake = AnalysisDelayWake()
        val sleeping = launch { wake.sleep(60_000L) }
        runCurrent()
        assertTrue(sleeping.isActive)
        wake.kick()
        runCurrent()
        assertTrue(sleeping.isCompleted)

        val second = launch { wake.sleep(60_000L) }
        runCurrent()
        assertTrue(second.isActive)
        advanceTimeBy(60_000L)
        runCurrent()
        assertTrue(second.isCompleted)
    }

    private fun result(
        introStartMs: Long? = null,
        introEndMs: Long? = null,
        recapStartMs: Long? = null,
        recapEndMs: Long? = null,
        userSegments: Map<String, UserSegment> = emptyMap(),
    ) = MediaAnalysisResult(
        key = "episode",
        analyzedAtMs = 0L,
        durationMs = 40 * 60_000L,
        introStartMs = introStartMs,
        introEndMs = introEndMs,
        recapStartMs = recapStartMs,
        recapEndMs = recapEndMs,
        userSegments = userSegments,
    )
}
