package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisWaitLogTest {
    @Test
    fun enterAndReleaseRecordTheReasonAndElapsedTime() {
        val tracker = AnalysisWaitTracker()
        assertEquals(listOf("entered reason=charger"), tracker.enter("charger", nowMs = 1_000L))
        assertEquals(emptyList(), tracker.enter("charger", nowMs = 2_000L))
        assertEquals(AnalysisWaitSnapshot("charger", 4_000L), tracker.snapshot(5_000L))
        val released = tracker.release(5_000L)
        assertEquals("released reason=charger elapsedMs=4000", released)
        assertEquals(null, tracker.snapshot(6_000L))
        assertEquals(listOf("entered reason=retry-wait"), tracker.enter("retry-wait", 6_000L))
        assertTrue(tracker.release(7_500L)!!.contains("elapsedMs=1500"))
    }

    @Test
    fun switchingReasonReleasesThePreviousWait() {
        val tracker = AnalysisWaitTracker()
        tracker.enter("playback", 0L)
        val switched = tracker.enter("app-background", 3_000L)
        assertEquals(
            listOf(
                "released reason=playback elapsedMs=3000",
                "entered reason=app-background",
            ),
            switched,
        )
        assertEquals("3s", analysisWaitClock(3_000L))
    }
}
