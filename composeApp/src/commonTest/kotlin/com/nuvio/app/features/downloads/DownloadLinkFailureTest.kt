package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadLinkFailureTest {
    @Test
    fun rejectedLinkStatusesRoundTrip() {
        listOf(400, 401, 403, 404, 410, 416).forEach { status ->
            assertTrue(DownloadLinkFailure.isRefreshable(status))
            assertEquals(status, DownloadLinkFailure.decode(DownloadLinkFailure.encode(status)))
        }
        assertFalse(DownloadLinkFailure.isRefreshable(500))
        assertNull(DownloadLinkFailure.decode("Request failed with HTTP 400"))
    }

    @Test
    fun legacyFailedRowsAreRefreshedOnRetry() {
        assertEquals(400, DownloadLinkFailure.legacyStatus("Request failed with HTTP 400"))
        assertEquals(410, DownloadLinkFailure.legacyStatus("Request failed with HTTP 410"))
        assertNull(DownloadLinkFailure.legacyStatus("Request failed with HTTP 503"))
        assertNull(DownloadLinkFailure.legacyStatus("Not enough space"))
        assertNull(DownloadLinkFailure.legacyStatus(null))
    }
}
