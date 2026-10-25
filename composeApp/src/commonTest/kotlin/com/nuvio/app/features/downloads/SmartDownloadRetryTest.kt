package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartDownloadRetryTest {
    private val now = 1_000_000L
    private val hour = 60 * 60_000L

    @Test
    fun `a failed resolve keeps the request for an hour and reports why`() {
        val reports = mutableListOf<String>()

        val retryAt = smartDownloadRetryAfterEnqueue(
            result = SeasonEnqueueResult(queued = 0, failureMessage = "Not cached on Torbox."),
            fitCount = 1,
            nowMs = now,
        ) { reports += it }

        assertEquals(now + hour, retryAt)
        assertEquals(listOf("Not cached on Torbox. Trying again in 1 h"), reports)
    }

    @Test
    fun `some episodes queued and one failed still retries the rest`() {
        val reports = mutableListOf<String>()

        val retryAt = smartDownloadRetryAfterEnqueue(
            result = SeasonEnqueueResult(queued = 1, failureMessage = "Could not open this link."),
            fitCount = 2,
            nowMs = now,
        ) { reports += it }

        assertEquals(now + hour, retryAt)
        assertEquals(1, reports.size)
    }

    @Test
    fun `a failure without a message is still reported`() {
        val reports = mutableListOf<String>()

        val retryAt = smartDownloadRetryAfterEnqueue(SeasonEnqueueResult(0, null), fitCount = 1, nowMs = now) { reports += it }

        assertEquals(now + hour, retryAt)
        assertTrue(reports.single().endsWith("Trying again in 1 h"))
    }

    @Test
    fun `everything queued needs no retry and reports nothing`() {
        val reports = mutableListOf<String>()

        val retryAt = smartDownloadRetryAfterEnqueue(SeasonEnqueueResult(2, null), fitCount = 2, nowMs = now) { reports += it }

        assertNull(retryAt)
        assertTrue(reports.isEmpty())
    }
}
