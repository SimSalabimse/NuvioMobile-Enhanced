package com.nuvio.app.features.downloads

import com.nuvio.app.features.analysis.AnalysisThermal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadGateDecisionTest {
    @Test
    fun playbackThrottlesToOneAndDoesNotStartAutomaticDownloads() {
        val gate = playing()
        assertEquals(DownloadGateState.Throttle, gate.state)
        assertEquals("playback", gate.reason)
        assertEquals(1, gate.maxActive)
        assertFalse(gate.allowNewAuto)
        assertTrue(gate.lowPriority)
    }

    @Test
    fun pauseOrStopResumesTheQueueEvenWhileThePlayerIsStillStrained() {
        val paused = downloadGateDecision(
            videoPlaying = false,
            strained = true,
            thermal = AnalysisThermal.Nominal,
            lowPowerMode = false,
            charging = false,
            onWifi = true,
            pauseWhileWatching = false,
        )
        assertEquals(DownloadGateState.Run, paused.state)
        assertEquals("paused", paused.reason)
        assertEquals(MAX_ACTIVE_DOWNLOADS, paused.maxActive)
        assertTrue(paused.allowNewAuto)
        assertFalse(paused.lowPriority)
    }

    @Test
    fun heatAndLowPowerHoldTheQueue() {
        val hot = playing(thermal = AnalysisThermal.Serious)
        assertEquals(DownloadGateState.Hold, hot.state)
        assertEquals("thermal-serious", hot.reason)
        assertEquals(0, hot.maxActive)

        val critical = playing(thermal = AnalysisThermal.Critical)
        assertEquals("thermal-critical", critical.reason)

        val lowPower = playing(lowPowerMode = true)
        assertEquals(DownloadGateState.Hold, lowPower.state)
        assertEquals("low-power", lowPower.reason)
    }

    @Test
    fun chargingOnWifiAtNominalHeatRunsAtFullSpeed() {
        val gate = playing(charging = true, onWifi = true, thermal = AnalysisThermal.Nominal)
        assertEquals(DownloadGateState.Run, gate.state)
        assertEquals("charging-wifi", gate.reason)
        assertEquals(MAX_ACTIVE_DOWNLOADS, gate.maxActive)
        assertTrue(gate.allowNewAuto)
        assertFalse(gate.lowPriority)
    }

    @Test
    fun chargingDoesNotOverrideHeatLowPowerOrPauseWhileWatching() {
        assertEquals(
            DownloadGateState.Throttle,
            playing(charging = true, onWifi = true, thermal = AnalysisThermal.Fair).state,
        )
        assertEquals(
            DownloadGateState.Hold,
            playing(charging = true, onWifi = true, thermal = AnalysisThermal.Serious).state,
        )
        assertEquals(
            DownloadGateState.Hold,
            playing(charging = true, onWifi = true, lowPowerMode = true).state,
        )
        assertEquals(
            DownloadGateState.Hold,
            playing(charging = true, onWifi = true, pauseWhileWatching = true).state,
        )
    }

    @Test
    fun anIdlePhoneRunsTwoDownloads() {
        val gate = downloadGateDecision(
            videoPlaying = false,
            strained = false,
            thermal = AnalysisThermal.Nominal,
            lowPowerMode = false,
            charging = false,
            onWifi = false,
            pauseWhileWatching = true,
        )
        assertEquals(DownloadGateState.Run, gate.state)
        assertEquals("clear", gate.reason)
        assertEquals(MAX_ACTIVE_DOWNLOADS, gate.maxActive)
    }

    @Test
    fun theDiagnosticsLineUsesTokensOnly() {
        val line = downloadGateLine(
            gate = playing(),
            active = 1,
            queued = 2,
            thermal = AnalysisThermal.Fair,
            lowPowerMode = false,
        )
        assertEquals(
            "download gate state=throttle reason=playback active=1 queued=2 thermal=fair lowPower=false",
            line,
        )
    }

    @Test
    fun aManualDownloadCanPassAnAutomaticOneThatMustWait() {
        val queue = DownloadStartQueue()
        queue.maxActive = 1
        val started = queue.request("auto", thermalBlocksNextFile = false, canStart = { false }) +
            queue.request("manual", thermalBlocksNextFile = false, canStart = { it == "manual" })
        assertEquals(listOf("manual"), started)
        assertEquals(listOf("auto"), queue.waitingIds())
    }

    private fun playing(
        thermal: AnalysisThermal = AnalysisThermal.Fair,
        lowPowerMode: Boolean = false,
        charging: Boolean = false,
        onWifi: Boolean = true,
        pauseWhileWatching: Boolean = false,
    ) = downloadGateDecision(
        videoPlaying = true,
        strained = false,
        thermal = thermal,
        lowPowerMode = lowPowerMode,
        charging = charging,
        onWifi = onWifi,
        pauseWhileWatching = pauseWhileWatching,
    )
}
