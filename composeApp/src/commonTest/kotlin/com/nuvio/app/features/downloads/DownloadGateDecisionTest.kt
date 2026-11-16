package com.nuvio.app.features.downloads

import com.nuvio.app.features.analysis.AnalysisThermal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadGateDecisionTest {
    @Test
    fun nextUpKeepsOneLowPriorityTransferWhileOtherLanesWait() {
        val gate = playing()
        assertEquals(DownloadGateState.Throttle, gate.nextUp.state)
        assertEquals(TransferPace.Low, gate.nextUp.pace)
        assertTrue(gate.nextUp.allowStart)
        assertEquals("playback", gate.nextUp.reason)
        assertFalse(gate.other.allowStart)
        assertEquals(DownloadGateState.Hold, gate.other.state)
        assertEquals(1, gate.maxActive)
        assertFalse(gate.allowNewAuto)
        assertTrue(gate.lowPriority)
        assertFalse(gate.dutyCycle)
    }

    @Test
    fun everyThermalAndLowPowerCombination() {
        val cases = listOf(
            playing(thermal = AnalysisThermal.Nominal) to TransferPace.Low,
            playing(thermal = AnalysisThermal.Fair) to TransferPace.Low,
            playing(thermal = AnalysisThermal.Serious) to TransferPace.Slowest,
            playing(thermal = AnalysisThermal.Critical) to TransferPace.Stopped,
            playing(lowPowerMode = true) to TransferPace.Low,
            playing(thermal = AnalysisThermal.Serious, lowPowerMode = true) to TransferPace.Slowest,
            playing(thermal = AnalysisThermal.Critical, lowPowerMode = true) to TransferPace.Stopped,
            playing(thermal = AnalysisThermal.Nominal, lowPowerMode = true, behindDeadline = true) to TransferPace.Normal,
            playing(thermal = AnalysisThermal.Serious, behindDeadline = true) to TransferPace.Slowest,
            playing(thermal = AnalysisThermal.Critical, behindDeadline = true) to TransferPace.Stopped,
        )
        cases.forEach { (gate, pace) ->
            assertEquals(pace, gate.nextUp.pace)
            assertFalse(gate.other.allowStart)
        }
        assertTrue(playing(lowPowerMode = true).nextUp.allowStart)
        assertEquals("low-power", playing(lowPowerMode = true).nextUp.reason)
        assertFalse(playing(thermal = AnalysisThermal.Critical).nextUp.allowStart)
        assertTrue(playing(thermal = AnalysisThermal.Serious).dutyCycle)
        assertEquals(1, playing(thermal = AnalysisThermal.Serious).maxActive)
        assertEquals(0, playing(thermal = AnalysisThermal.Critical).maxActive)
    }

    @Test
    fun pauseOrStopRunsBothLanesAtFullSpeed() {
        val paused = playbackDownloadGate(
            videoPlaying = false,
            thermal = AnalysisThermal.Serious,
            lowPowerMode = true,
            charging = false,
            onWifi = false,
            behindDeadline = false,
            pauseWhileWatching = false,
        )
        assertEquals(TransferPace.Normal, paused.nextUp.pace)
        assertTrue(paused.other.allowStart)
        assertEquals(MAX_ACTIVE_DOWNLOADS, paused.maxActive)
        assertEquals("clear", paused.reason)
        assertFalse(paused.lowPriority)
    }

    @Test
    fun chargingOnWifiIsFullSpeedForNextUpOnly() {
        val gate = playing(charging = true, onWifi = true, thermal = AnalysisThermal.Nominal)
        assertEquals(TransferPace.Normal, gate.nextUp.pace)
        assertEquals("charging-wifi", gate.nextUp.reason)
        assertFalse(gate.other.allowStart)
        assertEquals(1, gate.maxActive)
        assertFalse(gate.allowNewAuto)
    }

    @Test
    fun chargingDoesNotOverrideCriticalOrPauseWhileWatching() {
        assertEquals(TransferPace.Stopped, playing(charging = true, onWifi = true, thermal = AnalysisThermal.Critical).nextUp.pace)
        assertEquals(TransferPace.Stopped, playing(charging = true, onWifi = true, pauseWhileWatching = true).nextUp.pace)
        assertEquals(TransferPace.Low, playing(charging = true, onWifi = true, thermal = AnalysisThermal.Fair).nextUp.pace)
        assertEquals(TransferPace.Slowest, playing(charging = true, onWifi = true, thermal = AnalysisThermal.Serious).nextUp.pace)
    }

    @Test
    fun deadlineRaisesPriorityUnlessHeatIsSeriousOrCritical() {
        assertEquals(TransferPace.Normal, playing(behindDeadline = true).nextUp.pace)
        assertEquals("deadline", playing(behindDeadline = true, lowPowerMode = true).nextUp.reason)
        assertEquals(TransferPace.Slowest, playing(behindDeadline = true, thermal = AnalysisThermal.Serious).nextUp.pace)
        assertFalse(nextUpBehindDeadline(bytesLeft = 0, bytesPerSecond = 1_000, episodeRemainingMs = 60_000))
        assertFalse(nextUpBehindDeadline(bytesLeft = 1_000, bytesPerSecond = -1, episodeRemainingMs = 60_000))
        assertTrue(nextUpBehindDeadline(bytesLeft = 1_000, bytesPerSecond = 0, episodeRemainingMs = 60_000))
        assertTrue(
            nextUpBehindDeadline(
                bytesLeft = Long.MAX_VALUE / 2,
                bytesPerSecond = 1,
                episodeRemainingMs = Long.MAX_VALUE,
            ),
        )
        assertEquals(NEXT_EPISODE_SIZE_WHEN_UNKNOWN_BYTES, nextUpBytesLeft(totalBytes = null, downloadedBytes = 4_000))
        assertEquals(0L, nextUpBytesLeft(totalBytes = 100, downloadedBytes = 100))
        assertEquals(-1L, nextUpMeasuredRate(downloadedBytes = 0, baselineBytes = -1, elapsedMs = 60_000))
        assertEquals(0L, nextUpMeasuredRate(downloadedBytes = 10, baselineBytes = 10, elapsedMs = 60_000))
        assertEquals(1_000L, nextUpMeasuredRate(downloadedBytes = 1_000, baselineBytes = 0, elapsedMs = 1_000))
        assertFalse(
            deadlineRecheckDue(
                nowMs = 10_000,
                lastCheckMs = 9_000,
                episodeRemainingMs = 30_000,
                lastRemainingMs = 31_000,
            ),
        )
        assertTrue(
            deadlineRecheckDue(
                nowMs = 10_000,
                lastCheckMs = 9_000,
                episodeRemainingMs = 5_000,
                lastRemainingMs = 40 * 60_000L,
            ),
        )
        assertEquals(0.25f, transferTaskPriority(low = true))
        assertEquals(0.5f, transferTaskPriority(low = false))
        val target = nextUpDownloadTarget(season = 2, episode = 4)
        assertEquals(2, target.season)
        assertEquals(4, target.episode)
        assertTrue(target.exact)
        assertFalse(
            nextUpBehindDeadline(
                bytesLeft = 500_000,
                bytesPerSecond = 1_000_000,
                episodeRemainingMs = 20 * 60_000L,
            ),
        )
        assertTrue(
            nextUpBehindDeadline(
                bytesLeft = 500_000_000,
                bytesPerSecond = 1_000_000,
                episodeRemainingMs = 12 * 60_000L,
            ),
        )
    }

    @Test
    fun dutyCycleIsThirtyOnThirtyOffAndOffByDefault() {
        assertTrue(dutyCycleTransferring(0))
        assertTrue(dutyCycleTransferring(29_999))
        assertFalse(dutyCycleTransferring(30_000))
        assertTrue(dutyCycleTransferring(60_000))
        assertFalse(dutyCycleActive(TransferPace.Low, defaultEnabled = false))
        assertTrue(dutyCycleActive(TransferPace.Slowest, defaultEnabled = false))
        assertTrue(dutyCycleActive(TransferPace.Low, defaultEnabled = true))
    }

    @Test
    fun theDiagnosticsLineUsesAHashOrADash() {
        val line = nextUpLine("abc123def456", "throttle", 90, 600, 250_000)
        assertEquals("next-up id=abc123def456 lane=priority state=throttle eta=90s remaining=600s rate=250000", line)
        assertTrue(nextUpLine("Show Name", "throttle", 1, 1, 1).contains("id=-"))
    }

    @Test
    fun theGateLineUsesTokensOnly() {
        val line = downloadGateLine(playing(), active = 1, queued = 2, thermal = AnalysisThermal.Fair, lowPowerMode = false)
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
        behindDeadline: Boolean = false,
    ) = playbackDownloadGate(
        videoPlaying = true,
        thermal = thermal,
        lowPowerMode = lowPowerMode,
        charging = charging,
        onWifi = onWifi,
        behindDeadline = behindDeadline,
        pauseWhileWatching = pauseWhileWatching,
    )
}
