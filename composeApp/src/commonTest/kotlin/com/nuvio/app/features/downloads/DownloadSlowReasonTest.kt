package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadSlowReasonTest {
    @Test
    fun healthyDownloadHasNoReason() {
        assertNull(downloadSlowReason(signals()))
        assertNull(downloadSlowReason(signals(status = DownloadStatus.Queued)))
        assertNull(downloadSlowReason(signals(status = DownloadStatus.Paused, pauseWhileWatching = true, playerActive = true, retryCount = 3)))
        assertNull(downloadSlowReason(signals(status = DownloadStatus.Completed, retryCount = 2, thermalBlocks = true)))
        assertNull(downloadSlowReason(signals(status = DownloadStatus.Failed, onlyOnWifi = true, onWifi = false)))
        assertNull(
            downloadSlowReason(
                signals(
                    status = DownloadStatus.Downloading,
                    pauseWhileWatching = true,
                    playerActive = true,
                    heldForPlayback = true,
                    bufferingThrottle = true,
                    activeAhead = 4,
                    onlyOnWifi = true,
                    onWifi = false,
                    thermalBlocks = true,
                ),
            ),
        )
    }

    @Test
    fun pausedWhileWatching() {
        assertEquals(
            DownloadSlowReason.PausedWhileWatching,
            downloadSlowReason(signals(status = DownloadStatus.Queued, pauseWhileWatching = true, playerActive = true)),
        )
        assertEquals(
            DownloadSlowReason.PausedWhileWatching,
            downloadSlowReason(signals(status = DownloadStatus.Queued, heldForPlayback = true)),
        )
        assertNull(
            downloadSlowReason(signals(status = DownloadStatus.Queued, pauseWhileWatching = true, playerActive = false)),
        )
    }

    @Test
    fun throttledWhileTheStreamBuffers() {
        assertEquals(
            DownloadSlowReason.ThrottledWhileBuffering,
            downloadSlowReason(signals(status = DownloadStatus.Queued, bufferingThrottle = true)),
        )
    }

    @Test
    fun waitingBehindActiveDownloads() {
        assertEquals(
            DownloadSlowReason.WaitingInQueue,
            downloadSlowReason(signals(status = DownloadStatus.Queued, activeAhead = 2)),
        )
        assertNull(downloadSlowReason(signals(status = DownloadStatus.Queued, activeAhead = 0)))
    }

    @Test
    fun waitingForWifi() {
        assertEquals(
            DownloadSlowReason.WaitingForWifi,
            downloadSlowReason(signals(status = DownloadStatus.Queued, onlyOnWifi = true, onWifi = false)),
        )
        assertNull(downloadSlowReason(signals(status = DownloadStatus.Queued, onlyOnWifi = true, onWifi = true)))
        assertNull(downloadSlowReason(signals(status = DownloadStatus.Downloading, onlyOnWifi = true, onWifi = false)))
    }

    @Test
    fun cellularWithoutTheWifiSettingIsNotAReason() {
        assertNull(downloadSlowReason(signals(status = DownloadStatus.Queued, onlyOnWifi = false, onWifi = false)))
    }

    @Test
    fun deviceTooHot() {
        assertEquals(
            DownloadSlowReason.DeviceTooHot,
            downloadSlowReason(signals(status = DownloadStatus.Queued, thermalBlocks = true)),
        )
        assertNull(downloadSlowReason(signals(status = DownloadStatus.Downloading, thermalBlocks = true)))
    }

    @Test
    fun retrying() {
        assertEquals(
            DownloadSlowReason.Retrying,
            downloadSlowReason(signals(status = DownloadStatus.Downloading, retryCount = 1)),
        )
        assertEquals(
            DownloadSlowReason.Retrying,
            downloadSlowReason(signals(status = DownloadStatus.Queued, retryCount = 2)),
        )
        assertNull(downloadSlowReason(signals(status = DownloadStatus.Downloading, retryCount = 0)))
    }

    @Test
    fun priorityUsesOneReasonAtATime() {
        val all = signals(
            status = DownloadStatus.Queued,
            pauseWhileWatching = true,
            playerActive = true,
            heldForPlayback = true,
            bufferingThrottle = true,
            activeAhead = 3,
            onlyOnWifi = true,
            onWifi = false,
            thermalBlocks = true,
            retryCount = 2,
        )
        assertEquals(DownloadSlowReason.PausedWhileWatching, downloadSlowReason(all))
        assertEquals(
            DownloadSlowReason.ThrottledWhileBuffering,
            downloadSlowReason(all.copy(pauseWhileWatching = false, playerActive = false, heldForPlayback = false)),
        )
        assertEquals(
            DownloadSlowReason.WaitingInQueue,
            downloadSlowReason(
                all.copy(
                    pauseWhileWatching = false,
                    playerActive = false,
                    heldForPlayback = false,
                    bufferingThrottle = false,
                ),
            ),
        )
        assertEquals(
            DownloadSlowReason.WaitingForWifi,
            downloadSlowReason(
                all.copy(
                    pauseWhileWatching = false,
                    playerActive = false,
                    heldForPlayback = false,
                    bufferingThrottle = false,
                    activeAhead = 0,
                ),
            ),
        )
        assertEquals(
            DownloadSlowReason.DeviceTooHot,
            downloadSlowReason(
                all.copy(
                    pauseWhileWatching = false,
                    playerActive = false,
                    heldForPlayback = false,
                    bufferingThrottle = false,
                    activeAhead = 0,
                    onWifi = true,
                ),
            ),
        )
        assertEquals(
            DownloadSlowReason.Retrying,
            downloadSlowReason(
                all.copy(
                    pauseWhileWatching = false,
                    playerActive = false,
                    heldForPlayback = false,
                    bufferingThrottle = false,
                    activeAhead = 0,
                    onWifi = true,
                    thermalBlocks = false,
                ),
            ),
        )
        assertEquals(
            DownloadSlowReason.Retrying,
            downloadSlowReason(all.copy(status = DownloadStatus.Downloading)),
        )
    }

    private fun signals(
        status: DownloadStatus = DownloadStatus.Downloading,
        pauseWhileWatching: Boolean = false,
        playerActive: Boolean = false,
        heldForPlayback: Boolean = false,
        bufferingThrottle: Boolean = false,
        activeAhead: Int = 0,
        onlyOnWifi: Boolean = false,
        onWifi: Boolean = true,
        thermalBlocks: Boolean = false,
        retryCount: Int = 0,
    ) = DownloadSlowSignals(
        status = status,
        pauseWhileWatching = pauseWhileWatching,
        playerActive = playerActive,
        heldForPlayback = heldForPlayback,
        bufferingThrottle = bufferingThrottle,
        activeAhead = activeAhead,
        onlyOnWifi = onlyOnWifi,
        onWifi = onWifi,
        thermalBlocks = thermalBlocks,
        retryCount = retryCount,
    )
}
