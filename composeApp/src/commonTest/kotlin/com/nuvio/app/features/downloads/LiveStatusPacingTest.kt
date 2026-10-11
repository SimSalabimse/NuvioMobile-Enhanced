package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveStatusPacingTest {
    @Test
    fun throttleSendsStatusChangesAtOnceAndProgressAtOneHertz() {
        val t = LiveStatusThrottle()
        assertEquals(0L, t.waitBeforeSend("a|Downloading", 0L))
        t.markSent("a|Downloading", 0L)
        assertEquals(700L, t.waitBeforeSend("a|Downloading", 300L))
        assertEquals(0L, t.waitBeforeSend("a|Paused", 300L))
        assertEquals(0L, t.waitBeforeSend("a|Downloading", 1_000L))
        assertEquals(0L, t.waitBeforeSend(null, 400L))
    }

    @Test
    fun speedSettlesOnTheSteadyRateAndSurvivesAnEpisodeSwap() {
        val e = TransferSpeedEstimator()
        assertNull(e.sample("s", 0L, 0L))
        var bytes = 0L
        var speed: Double? = null
        for (second in 1..30) {
            bytes += 1_000_000L
            speed = e.sample("s", bytes, second * 1_000L)
        }
        assertTrue(speed!! in 990_000.0..1_010_000.0)
        // E3 finished, E5 joined: the summed bytes drop, the speed stays.
        assertEquals(speed, e.sample("s", 10L, 31_000L))
        assertNull(e.sample("other", 0L, 32_000L))
    }
}
