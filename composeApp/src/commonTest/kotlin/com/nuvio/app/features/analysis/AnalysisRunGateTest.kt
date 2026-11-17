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
    fun seriousHeatWithNoPlaybackShowsTheHeatText() {
        val reason = analysisPauseReason(
            videoPlaying = false,
            thermal = AnalysisThermal.Serious,
            lowPowerMode = false,
            transferActive = false,
        )
        assertEquals("thermal-serious", reason)
        assertEquals(AnalysisJobState.WaitingForHeat, analysisWaitJob(reason!!))
        assertEquals(
            "Paused while the phone is hot. It continues when it cools down.",
            analysisHoldMessage(reason),
        )
        assertEquals(
            "Paused while something is playing. It continues when you stop watching.",
            analysisHoldMessage("playback"),
        )
        assertEquals(AnalysisJobState.WaitingWhileDownloading, analysisWaitJob("download"))
        assertEquals(AnalysisJobState.WaitingForLowPower, analysisWaitJob("low-power"))
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
        assertFalse(analysisMayStart(videoPlaying = false, AnalysisThermal.Serious, lowPowerMode = false))
        assertFalse(
            analysisMayStart(
                videoPlaying = false,
                thermal = AnalysisThermal.Serious,
                lowPowerMode = false,
                transferActive = true,
            ),
        )
        assertFalse(analysisMayStart(videoPlaying = false, AnalysisThermal.Critical, lowPowerMode = false))
        assertTrue(analysisMayStart(videoPlaying = false, AnalysisThermal.Nominal, lowPowerMode = false))
        val first = analysisDiagnosticsLine(
            event = "stop",
            job = "ep-1",
            reason = "power",
            qos = "utility",
            power = AnalysisPowerSnapshot(AnalysisThermal.Serious, lowPowerMode = false),
        )
        val again = analysisDiagnosticsLine(
            event = "stop",
            job = "ep-1",
            reason = "power",
            qos = "background",
            power = AnalysisPowerSnapshot(AnalysisThermal.Critical, lowPowerMode = true),
        )
        assertTrue(sameAnalysisStop(first, again))
        assertFalse(sameAnalysisStop(first, first.replace("stop", "start")))
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