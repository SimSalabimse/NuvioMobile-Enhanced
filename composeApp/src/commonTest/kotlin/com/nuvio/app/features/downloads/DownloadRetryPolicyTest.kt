package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRetryPolicyTest {
    @Test
    fun `a lost connection timeout or offline is retried and an HTTP or cancel is not`() {
        listOf(-1005L, -1001L, -1009L, -1020L, -1004L).forEach {
            assertTrue(DownloadRetryPolicy.isTransient("NSURLErrorDomain", it), "code $it")
        }
        assertFalse(DownloadRetryPolicy.isTransient("NSURLErrorDomain", -999L))
        assertFalse(DownloadRetryPolicy.isTransient("NSURLErrorDomain", -1011L))
        assertFalse(DownloadRetryPolicy.isTransient("NSPOSIXErrorDomain", -1005L))
    }

    @Test
    fun `retries back off and stop after the last delay`() {
        val tracker = DownloadRetryTracker(backoffSeconds = listOf(2L, 5L), resetAfterBytes = 100L)
        assertEquals(2L, tracker.nextDelaySeconds("a", 0L))
        assertEquals(5L, tracker.nextDelaySeconds("a", 10L))
        assertNull(tracker.nextDelaySeconds("a", 20L))
        assertEquals(2L, tracker.nextDelaySeconds("a", 20L))
    }

    @Test
    fun `a retry that made real progress starts the count again`() {
        val tracker = DownloadRetryTracker(backoffSeconds = listOf(2L, 5L), resetAfterBytes = 100L)
        assertEquals(2L, tracker.nextDelaySeconds("a", 0L))
        assertEquals(5L, tracker.nextDelaySeconds("a", 50L))
        assertEquals(2L, tracker.nextDelaySeconds("a", 500L))
    }
}
