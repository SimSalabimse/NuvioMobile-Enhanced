package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HiddenDelightTest {
    @Test
    fun sevenTapsInsideTheWindowPlayAndThenTheCountResets() {
        val counter = HiddenDelightCounter()
        repeat(6) { index ->
            assertEquals(HiddenDelightSignal.Counting, counter.onTap(index * 100L, reduceMotion = false))
        }
        val played = counter.onTap(600L, reduceMotion = false)
        assertEquals(HiddenDelightSignal.Play, played)
        assertEquals(HiddenDelightMotionMs, played.motionMs())
        assertTrue(played.motionMs() <= 300)
        assertEquals(HiddenDelightSignal.Counting, counter.onTap(700L, reduceMotion = false))
    }

    @Test
    fun aTapAfterTheWindowStartsANewRun() {
        val counter = HiddenDelightCounter()
        repeat(6) { index ->
            assertEquals(HiddenDelightSignal.Counting, counter.onTap(index * 100L, reduceMotion = false))
        }
        assertEquals(HiddenDelightSignal.Counting, counter.onTap(HiddenDelightTapWindowMs + 1L, reduceMotion = false))
        repeat(5) { index ->
            assertEquals(
                HiddenDelightSignal.Counting,
                counter.onTap(HiddenDelightTapWindowMs + 1L + (index + 1) * 50L, reduceMotion = false),
            )
        }
        assertEquals(
            HiddenDelightSignal.Play,
            counter.onTap(HiddenDelightTapWindowMs + 1L + 400L, reduceMotion = false),
        )
    }

    @Test
    fun theLastTapOfTheWindowStillCounts() {
        val counter = HiddenDelightCounter()
        assertEquals(HiddenDelightSignal.Counting, counter.onTap(0L, reduceMotion = false))
        repeat(5) { index ->
            assertEquals(HiddenDelightSignal.Counting, counter.onTap(10L + index, reduceMotion = false))
        }
        assertEquals(
            HiddenDelightSignal.Play,
            counter.onTap(HiddenDelightTapWindowMs, reduceMotion = false),
        )
    }

    @Test
    fun reduceMotionSuppressesTheMotionAndResets() {
        val counter = HiddenDelightCounter()
        repeat(6) { index ->
            assertEquals(HiddenDelightSignal.Counting, counter.onTap(index * 40L, reduceMotion = true))
        }
        val suppressed = counter.onTap(240L, reduceMotion = true)
        assertEquals(HiddenDelightSignal.Suppressed, suppressed)
        assertEquals(0, suppressed.motionMs())
        assertEquals(0, HiddenDelightSignal.Counting.motionMs())
        assertEquals(0, hiddenDelightWiggleMs(reduceMotion = true))
        assertEquals(HiddenDelightMotionMs, hiddenDelightWiggleMs(reduceMotion = false))
        assertTrue(hiddenDelightWiggleMs(reduceMotion = false) <= 300)
        assertEquals(HiddenDelightSignal.Counting, counter.onTap(280L, reduceMotion = true))
    }
}
