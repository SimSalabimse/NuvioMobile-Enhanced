package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisRunGateTest {
    @Test
    fun playbackPausesAnalysisAndStoppingOrIdleResumesIt() {
        assertEquals("playback", analysisPauseReason(videoPlaying = true, AnalysisThermal.Nominal, lowPowerMode = false))
        assertEquals(1, analysisThrottleLevel(com.nuvio.app.features.downloads.PlaybackPriority.Level.Playing, videoPlaying = true))
        assertNull(analysisPauseReason(videoPlaying = false, AnalysisThermal.Nominal, lowPowerMode = false))
        assertNull(analysisPauseReason(videoPlaying = false, AnalysisThermal.Fair, lowPowerMode = false))
        assertEquals(0, analysisThrottleLevel(com.nuvio.app.features.downloads.PlaybackPriority.Level.Playing, videoPlaying = false))
    }

    @Test
    fun seriousOrCriticalThermalPausesEvenWhenNothingIsPlaying() {
        assertEquals("thermal-serious", analysisPauseReason(videoPlaying = false, AnalysisThermal.Serious, lowPowerMode = false))
        assertEquals("thermal-critical", analysisPauseReason(videoPlaying = false, AnalysisThermal.Critical, lowPowerMode = false))
        assertEquals(
            3,
            analysisThrottleLevel(
                com.nuvio.app.features.downloads.PlaybackPriority.Level.Normal,
                videoPlaying = false,
                thermal = AnalysisThermal.Critical,
            ),
        )
        assertEquals("playback", analysisPauseReason(videoPlaying = true, AnalysisThermal.Critical, lowPowerMode = true))
    }

    @Test
    fun aTransferHoldsAnalysisFromFairUpwardAndNominalStillRuns() {
        assertEquals(
            "download",
            analysisPauseReason(
                videoPlaying = false,
                AnalysisThermal.Fair,
                lowPowerMode = false,
                transferActive = true,
            ),
        )
        assertNull(
            analysisPauseReason(
                videoPlaying = false,
                AnalysisThermal.Nominal,
                lowPowerMode = false,
                transferActive = true,
            ),
        )
        assertEquals(
            3,
            analysisThrottleLevel(
                com.nuvio.app.features.downloads.PlaybackPriority.Level.Normal,
                videoPlaying = false,
                thermal = AnalysisThermal.Fair,
                transferActive = true,
            ),
        )
        assertEquals(0, analysisThrottleLevel(
            com.nuvio.app.features.downloads.PlaybackPriority.Level.Normal,
            videoPlaying = false,
            thermal = AnalysisThermal.Nominal,
            transferActive = true,
        ))
        assertEquals("utility", analysisQosLabel(playerSessionOpen = false, paused = false, transferActive = true))
        assertEquals("userInitiated", analysisQosLabel(playerSessionOpen = false, paused = false, transferActive = false))
    }

    @Test
    fun thePowerPathChecksHeatAgainBeforeAStart() {
        assertEquals(
            "thermal-serious",
            analysisPauseReason(videoPlaying = false, AnalysisThermal.Serious, lowPowerMode = false, transferActive = true),
        )
        assertEquals(
            "download",
            analysisPauseReason(videoPlaying = false, AnalysisThermal.Fair, lowPowerMode = false, transferActive = true),
        )
        assertNull(
            analysisPauseReason(videoPlaying = false, AnalysisThermal.Nominal, lowPowerMode = false, transferActive = false),
        )
        assertEquals("utility", analysisQosLabel(playerSessionOpen = false, paused = true, transferActive = false))
    }

    @Test
    fun lowPowerModePausesWhenTheVideoIsIdle() {
        assertEquals("low-power", analysisPauseReason(videoPlaying = false, AnalysisThermal.Fair, lowPowerMode = true))
        assertEquals(
            3,
            analysisThrottleLevel(
                com.nuvio.app.features.downloads.PlaybackPriority.Level.Normal,
                videoPlaying = false,
                lowPowerMode = true,
            ),
        )
    }

    @Test
    fun diagnosticsLineKeepsTheJobAndDropsUrls() {
        val line = analysisDiagnosticsLine(
            event = "stop",
            job = "download-1",
            reason = "playback",
            qos = "utility",
            power = AnalysisPowerSnapshot(AnalysisThermal.Serious, lowPowerMode = true),
        )
        assertEquals(
            "analysis stop job=download-1 reason=playback qos=utility thermal=serious lowPower=true",
            line,
        )
        val redacted = analysisDiagnosticsLine(
            event = "start",
            job = "https://cdn.example/file.mkv?token=secret",
            reason = "clear",
            qos = "utility",
            power = AnalysisPowerSnapshot(AnalysisThermal.Nominal, lowPowerMode = false),
        )
        assertTrue(redacted.contains("job=-"))
        assertFalse(redacted.contains("http"))
        assertFalse(redacted.contains("token"))
        assertFalse(redacted.contains("cdn.example"))
    }
}