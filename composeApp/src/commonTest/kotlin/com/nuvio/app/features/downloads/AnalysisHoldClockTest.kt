package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisHoldClockTest {
    @Test
    fun unpluggedChargerOnlyDoesNotSpendTheCap() {
        assertTrue(analysisHoldClockFrozen(onlyWhileCharging = true, deviceCharging = false))
        val started = accountHoldClock(HoldClock(), nowMs = 0L, frozenNow = true, heldSinceEpochMs = null)
        val later = accountHoldClock(started, nowMs = 29L * 60L * 1000L, frozenNow = true, heldSinceEpochMs = 0L)
        assertEquals(0L, later.eligibleMs)
        assertTrue(later.eligibleMs < ANALYSIS_HOLD_CAP_MS)
    }

    @Test
    fun eligibleTimeCountsWhileCharging() {
        val started = accountHoldClock(HoldClock(), nowMs = 0L, frozenNow = false, heldSinceEpochMs = null)
        val later = accountHoldClock(started, nowMs = ANALYSIS_HOLD_CAP_MS, frozenNow = false, heldSinceEpochMs = 0L)
        assertEquals(ANALYSIS_HOLD_CAP_MS, later.eligibleMs)
    }

    @Test
    fun chargingAfterAnUnpluggedWaitStartsFromZero() {
        val unplugged = accountHoldClock(HoldClock(), nowMs = 0L, frozenNow = true, heldSinceEpochMs = null)
        val still = accountHoldClock(unplugged, nowMs = 20L * 60L * 1000L, frozenNow = false, heldSinceEpochMs = 0L)
        assertEquals(0L, still.eligibleMs)
        assertFalse(still.frozen)
    }
}
