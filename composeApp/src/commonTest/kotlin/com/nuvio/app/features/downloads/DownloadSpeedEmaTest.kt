package com.nuvio.app.features.downloads

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadSpeedEmaTest {
    @Test
    fun firstSampleIsTheMeasuredRate() {
        val ema = DownloadSpeedEma()
        assertNull(ema.bytesPerSecond)
        assertEquals(1_000_000.0, ema.sample(bytesTransferred = 1_000_000L, elapsedMs = 1_000L))
        assertEquals(1_000_000.0, ema.bytesPerSecond)
    }

    @Test
    fun laterSamplesMovePartWayTowardTheNewRate() {
        val ema = DownloadSpeedEma()
        ema.sample(1_000_000L, 1_000L)
        val updated = ema.sample(3_000_000L, 1_000L)
        val expected = 1_000_000.0 + DOWNLOAD_SPEED_EMA_ALPHA * (3_000_000.0 - 1_000_000.0)
        assertTrue(updated != null && abs(updated - expected) < 0.001)
        assertTrue(abs(ema.bytesPerSecond!! - expected) < 0.001)
    }

    @Test
    fun zeroAndTinySamplesLeaveTheAverageAlone() {
        val ema = DownloadSpeedEma()
        ema.sample(1_000_000L, 1_000L)
        assertNull(ema.sample(0L, 1_000L))
        assertNull(ema.sample(-5L, 1_000L))
        assertNull(ema.sample(1_000_000L, 0L))
        assertNull(ema.sample(100L, 10_000L))
        assertNull(ema.sample(32_767L, 1_000L))
        assertEquals(1_000_000.0, ema.bytesPerSecond)
        assertTrue(ema.sample(32_768L, 1_000L) != null)
        assertTrue(ema.bytesPerSecond!! < 1_000_000.0)
    }

    @Test
    fun restoreKeepsARealMeasurementAndDropsATinyOne() {
        val ema = DownloadSpeedEma()
        ema.restore(4_000_000.0)
        assertEquals(4_000_000.0, ema.bytesPerSecond)
        val updated = ema.sample(2_000_000L, 1_000L)
        val expected = 4_000_000.0 + DOWNLOAD_SPEED_EMA_ALPHA * (2_000_000.0 - 4_000_000.0)
        assertTrue(updated != null && abs(updated - expected) < 0.001)
        ema.restore(10.0)
        assertNull(ema.bytesPerSecond)
    }

    @Test
    fun samplerMeasuresBytesOverTimeAndIgnoresAGap() {
        val ema = DownloadSpeedEma()
        val sampler = ActiveDownloadSpeedSampler(ema)
        assertNull(sampler.observe(listOf("a" to 0L), nowMs = 0L))
        assertEquals(1_000_000.0, sampler.observe(listOf("a" to 1_000_000L), nowMs = 1_000L))
        assertNull(sampler.observe(listOf("a" to 1_000_000L), nowMs = 2_000L))
        assertEquals(1_000_000.0, ema.bytesPerSecond)
        sampler.observe(emptyList(), nowMs = 20_000L)
        assertNull(sampler.observe(listOf("a" to 5_000_000L), nowMs = 31_000L))
        assertEquals(1_000_000.0, ema.bytesPerSecond)
    }
}
