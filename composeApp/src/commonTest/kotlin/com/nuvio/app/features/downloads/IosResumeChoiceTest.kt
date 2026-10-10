package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IosResumeChoiceTest {
    @Test
    fun `a live system task is reattached`() {
        val action = chooseIosResume(
            signals(
                systemTaskStillRunning = true,
                partLength = 4_000_000L,
                resumeBlobExists = false,
            ),
        )

        assertEquals(IosResumeAction.Reattach, action)
    }

    @Test
    fun `a missing system task ranges from the prefix`() {
        val action = chooseIosResume(
            signals(
                systemTaskStillRunning = false,
                partLength = 4_000_000L,
                resumeBlobExists = false,
            ),
        )

        assertEquals(IosResumeAction.RangeFromPrefix, action)
        assertNotEquals(IosResumeAction.StartAtZero, action)
    }

    @Test
    fun `a user pause stays paused`() {
        val action = chooseIosResume(
            signals(
                userPaused = true,
                systemTaskStillRunning = true,
                partLength = 4_000_000L,
                resumeBlobExists = true,
            ),
        )

        assertEquals(IosResumeAction.StayPaused, action)
    }

    @Test
    fun `a failed resume blob ranges from the prefix`() {
        val action = chooseIosResume(
            signals(
                systemTaskStillRunning = false,
                partLength = 4_000_000L,
                resumeBlobExists = true,
                resumeBlobFailed = true,
            ),
        )

        assertEquals(IosResumeAction.RangeFromPrefix, action)
        assertNotEquals(IosResumeAction.StartAtZero, action)
    }

    @Test
    fun `a missing resume blob does not start at byte 0 while the prefix has data`() {
        val action = chooseIosResume(
            signals(
                partLength = 1L,
                resumeBlobExists = false,
                resumeBlobFailed = false,
            ),
        )

        assertEquals(IosResumeAction.RangeFromPrefix, action)
    }

    @Test
    fun `start at 0 is only allowed when the prefix is empty and no system task is live`() {
        assertEquals(
            IosResumeAction.StartAtZero,
            chooseIosResume(signals(partLength = 0L, systemTaskStillRunning = false)),
        )
        assertEquals(
            IosResumeAction.Reattach,
            chooseIosResume(signals(partLength = 0L, systemTaskStillRunning = true)),
        )
        assertEquals(
            IosResumeAction.RangeFromPrefix,
            chooseIosResume(
                signals(partLength = 32L, systemTaskStillRunning = false, resumeBlobFailed = true),
            ),
        )
    }

    @Test
    fun `an explicit resume uses the prefix offset`() {
        val action = chooseIosResume(
            signals(
                userPaused = false,
                systemTaskStillRunning = false,
                partLength = 9_000L,
                resumeBlobExists = false,
            ),
        )

        assertEquals(IosResumeAction.RangeFromPrefix, action)
    }

    @Test
    fun `a ranged 200 keeps the prefix`() {
        assertEquals(
            PartialPrefixDisposition.Keep,
            partialPrefixDisposition(statusCode = 200, requestedRange = 4_000_000L, prefixLength = 4_000_000L),
        )
    }

    @Test
    fun `a ranged 206 still appends and a full 200 may replace an empty prefix`() {
        assertEquals(
            PartialPrefixDisposition.Append,
            partialPrefixDisposition(statusCode = 206, requestedRange = 4_000_000L, prefixLength = 4_000_000L),
        )
        assertEquals(
            PartialPrefixDisposition.Replace,
            partialPrefixDisposition(statusCode = 200, requestedRange = 0L, prefixLength = 0L),
        )
    }

    @Test
    fun `opening the app does not show zero bytes when the prefix is still on disk`() {
        assertEquals(4_000_000L, resumedByteCount(storedBytes = 0L, partLength = 4_000_000L))
        assertEquals(5_000_000L, resumedByteCount(storedBytes = 5_000_000L, partLength = 4_000_000L))
        assertTrue(resumedByteCount(storedBytes = 0L, partLength = 1L) > 0L)
    }

    private fun signals(
        userPaused: Boolean = false,
        systemTaskStillRunning: Boolean = false,
        partLength: Long = 0L,
        resumeBlobExists: Boolean = false,
        resumeBlobFailed: Boolean = false,
    ) = IosResumeSignals(
        userPaused = userPaused,
        systemTaskStillRunning = systemTaskStillRunning,
        partLength = partLength,
        resumeBlobExists = resumeBlobExists,
        resumeBlobFailed = resumeBlobFailed,
    )
}
