package com.nuvio.app.features.downloads

import com.nuvio.app.features.analysis.MediaAnalyzerPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SmartDownloadChargingWaitTest {
    @Test
    fun androidStubWithChargerOnlyDoesNotWait() {
        assertFalse(MediaAnalyzerPlatform.supported)
        assertFalse(MediaAnalyzerPlatform.isCharging())
        assertNull(
            smartDownloadWaitReason(
                onlyOnWifi = false,
                onWifi = true,
                onlyWhileCharging = true,
                charging = MediaAnalyzerPlatform.isCharging(),
                analyserSupported = MediaAnalyzerPlatform.supported,
            ),
        )
    }

    @Test
    fun aSupportedAnalyserStillWaitsForTheCharger() {
        assertEquals(
            "Waiting for charging",
            smartDownloadWaitReason(
                onlyOnWifi = false,
                onWifi = true,
                onlyWhileCharging = true,
                charging = false,
                analyserSupported = true,
            ),
        )
    }
}
