package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextUpAutoplayWaitTest {
    @Test
    fun theWaitIsFiveSecondsAndPlaybackStartsAnyway() {
        assertEquals(5_000L, NEXT_UP_AUTOPLAY_WAIT_MS)
        assertTrue(autoplayStarts(waitedMs = 0L, analysisReady = true))
        assertFalse(autoplayStarts(waitedMs = 4_999L, analysisReady = false))
        assertTrue(autoplayStarts(waitedMs = 5_000L, analysisReady = false))
    }

    @Test
    fun aLateIntroIsAppliedOnlyWhileTheWindowIsStillAhead() {
        val inside = lateNextUpApply(
            positionMs = 30_000L,
            introReady = true,
            introEndMs = INTRO_WINDOW_MS,
            creditsReady = true,
            creditsStartMs = 40 * 60_000L,
        )
        assertEquals(LateMark.Apply, inside.intro)
        assertEquals(LateMark.Apply, inside.credits)

        val passed = lateNextUpApply(
            positionMs = INTRO_WINDOW_MS,
            introReady = true,
            introEndMs = INTRO_WINDOW_MS,
            creditsReady = true,
            creditsStartMs = 40 * 60_000L,
        )
        assertEquals(LateMark.Record, passed.intro)
        assertEquals(LateMark.Apply, passed.credits)

        val creditsPassed = lateNextUpApply(
            positionMs = 50 * 60_000L,
            introReady = false,
            introEndMs = INTRO_WINDOW_MS,
            creditsReady = true,
            creditsStartMs = 40 * 60_000L,
        )
        assertEquals(LateMark.Pending, creditsPassed.intro)
        assertEquals(LateMark.Record, creditsPassed.credits)
    }
}
