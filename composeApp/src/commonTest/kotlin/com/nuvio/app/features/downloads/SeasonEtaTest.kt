package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonEtaTest {
    @Test
    fun `two parallel episodes finish no sooner than the slower one`() {
        val fasterSpeed = 801_000.0
        val slowerSpeed = 729_000.0
        val fasterRemaining = 4_490_000_000L
        val slowerRemaining = 4_860_000_000L
        val slowerEta = (slowerRemaining / slowerSpeed).toLong()
        val naiveCombined = ((fasterRemaining + slowerRemaining) / (fasterSpeed + slowerSpeed)).toLong()

        val eta = seasonEtaSeconds(
            running = listOf(
                RunningEpisodePace(fasterSpeed, fasterRemaining),
                RunningEpisodePace(slowerSpeed, slowerRemaining),
            ),
        )

        assertEquals("1 h 33 min", formatEta((fasterRemaining / fasterSpeed).toLong()))
        assertEquals("1 h 51 min", formatEta(slowerEta))
        assertEquals("1 h 41 min", formatEta(naiveCombined))
        assertTrue(naiveCombined < slowerEta)
        assertEquals(slowerEta, eta)
        assertEquals("1 h 51 min", formatEta(eta!!))
    }

    @Test
    fun `a queued episode makes the season longer than the one that is running`() {
        val runningSpeed = 801_000.0
        val runningRemaining = 4_490_000_000L
        val queuedRemaining = 4_860_000_000L
        val runningEta = (runningRemaining / runningSpeed).toLong()

        val eta = seasonEtaSeconds(
            running = listOf(RunningEpisodePace(runningSpeed, runningRemaining)),
            queuedRemainingBytes = listOf(queuedRemaining),
        )

        val expected = ((runningRemaining + queuedRemaining) / runningSpeed).toLong()
        assertEquals(expected, eta)
        assertTrue(eta!! > runningEta)
    }

    @Test
    fun `unknown size gives no eta`() {
        assertNull(
            seasonEtaSeconds(
                running = listOf(
                    RunningEpisodePace(801_000.0, remainingBytes = null),
                    RunningEpisodePace(729_000.0, 4_860_000_000L),
                ),
            ),
        )
        assertNull(
            seasonEtaSeconds(
                running = listOf(RunningEpisodePace(801_000.0, 4_490_000_000L)),
                queuedRemainingBytes = listOf(null),
            ),
        )
    }

    @Test
    fun `a single episode matches remaining bytes divided by its speed`() {
        val remaining = 4_490_000_000L
        val speed = 801_000.0

        assertEquals(
            (remaining / speed).toLong(),
            seasonEtaSeconds(running = listOf(RunningEpisodePace(speed, remaining))),
        )
    }

    @Test
    fun `a running episode with no speed yet still adds its known bytes`() {
        val moving = RunningEpisodePace(801_000.0, 1_000_000_000L)
        val notYet = RunningEpisodePace(bytesPerSecond = null, remainingBytes = 1_000_000_000L)

        assertEquals(
            ((1_000_000_000L + 1_000_000_000L) / 801_000.0).toLong(),
            seasonEtaSeconds(running = listOf(moving, notYet)),
        )
    }
}
