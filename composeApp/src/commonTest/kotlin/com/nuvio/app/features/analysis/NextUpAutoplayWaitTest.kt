package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextUpAutoplayWaitTest {
    @Test
    fun theWaitIsFiveSecondsAndPlaybackStartsAnyway() {
        assertEquals(5_000L, NEXT_UP_AUTOPLAY_WAIT_MS)
        assertTrue(autoplayStarts(waitedMs = 0L, analysisReady = true))
        assertFalse(autoplayStarts(waitedMs = 4_999L, analysisReady = false))
        assertTrue(autoplayStarts(waitedMs = 5_000L, analysisReady = false))
        assertEquals(200L, autoplayWaitMillis(elapsedMs = 0L))
        assertEquals(100L, autoplayWaitMillis(elapsedMs = 4_900L))
        assertEquals(0L, autoplayWaitMillis(elapsedMs = 5_000L))
    }

    @Test
    fun aWindowIsNotACreditsCueAndAnUnknownDurationDoesNotScanTheOpening() {
        assertNull(lateDetectedSkip(LateMark.Apply, detectedStartSec = null, detectedEndSec = null))
        assertNull(lateDetectedSkip(LateMark.Record, detectedStartSec = 10.0, detectedEndSec = 20.0))
        assertEquals(LateSkip(10.0, 12.0), lateDetectedSkip(LateMark.Apply, 10.0, 12.0))
        assertNull(creditsWindowOriginMs(durationMs = 0L, windowMs = CREDITS_WINDOW_MS, startMs = -1L))
        assertEquals(34 * 60_000L, creditsWindowOriginMs(durationMs = 40 * 60_000L, windowMs = CREDITS_WINDOW_MS, startMs = -1L))
    }

    @Test
    fun aBusyPassDoesNotConsumeTheAttempt() {
        assertFalse(windowOfferAccepted(slotFree = false, plan = listOf("intro"), alreadyAttempted = false))
        assertFalse(windowOfferAccepted(slotFree = true, plan = emptyList(), alreadyAttempted = false))
        assertFalse(windowOfferAccepted(slotFree = true, plan = listOf("credits"), alreadyAttempted = true))
        assertTrue(windowOfferAccepted(slotFree = true, plan = listOf("intro", "credits"), alreadyAttempted = false))
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
