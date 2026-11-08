package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipDecodeGovernorTest {
    @Test
    fun missingTimestampsStopBeforeAWholeEpisodeIsDecoded() {
        val governor = ClipDecodeGovernor(startMs = 0L, endMs = 40_000L)
        var kept = 0
        repeat(100_000) {
            if (!governor.onFrame(timestampMs = null, sampleCount = 48_000)) return@repeat
            kept += 1
        }
        assertEquals("missing-timestamp", governor.stop)
        assertTrue(governor.frames <= CLIP_MISSING_TIMESTAMP_FRAMES + 1)
        assertEquals(0, governor.samples)
        assertTrue(kept < 200)
    }

    @Test
    fun aSeekThatLandsAtTheStartDoesNotDecodeUpToALaterWindow() {
        val governor = ClipDecodeGovernor(startMs = 1_200_000L, endMs = 1_240_000L)
        var read = true
        repeat(500) {
            read = governor.onFrame(timestampMs = it * 20L, sampleCount = 2_000)
            if (!read) return@repeat
        }
        assertEquals("preroll-budget", governor.stop)
        assertEquals(0, governor.samples)
        assertTrue(governor.frames < 50)
    }

    @Test
    fun stuckTimestampsStopAndTheWindowEndStopsAHealthyRead() {
        val stuck = ClipDecodeGovernor(startMs = 0L, endMs = 40_000L)
        repeat(200) {
            if (!stuck.onFrame(timestampMs = 10L, sampleCount = 100)) return@repeat
        }
        assertEquals("timestamp-stuck", stuck.stop)
        assertTrue(stuck.samples < 10_000)

        val healthy = ClipDecodeGovernor(startMs = 1_000L, endMs = 2_000L)
        var ms = 800L
        while (healthy.stop == null && ms < 5_000L) {
            healthy.onFrame(timestampMs = ms, sampleCount = 10)
            ms += 20L
        }
        assertEquals("window-end", healthy.stop)
        assertTrue(healthy.samples > 0)
        assertTrue(healthy.frames < 500)
    }

    @Test
    fun reportedWorkPastTheBudgetIsRejected() {
        val window = 40_000L
        val limits = clipDecodeLimits(window)
        assertEquals(false, reportedClipExceedsBudget(frames = 0, samples = 0, windowMs = window))
        assertEquals(false, reportedClipExceedsBudget(frames = limits.maxFrames, samples = 1, windowMs = window))
        assertEquals(true, reportedClipExceedsBudget(frames = limits.maxFrames + 1, samples = 0, windowMs = window))
        val short = referenceSampleWindow(10_000L, 40_000L, padMs = 5_000L)
        assertEquals(true, short.complete)
        assertEquals(5_000L, short.startMs)
        assertEquals(45_000L, short.endMs)
        val longer = referenceSampleWindow(0L, 90_000L, padMs = 5_000L)
        assertEquals(false, longer.complete)
        assertEquals(95_000L, longer.requestedMs)
        assertEquals(longer.startMs, longer.endMs)
    }
}
