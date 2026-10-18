package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DownloadHealthTest {
    private val gb = 1_000_000_000L

    @Test
    fun `a finished file must match the announced size`() {
        assertNull(downloadProblem(expectedBytes = 2 * gb, actualBytes = 2 * gb, probedDurationMs = null, expectedDurationMs = null))
        assertNotNull(downloadProblem(expectedBytes = 2 * gb, actualBytes = gb, probedDurationMs = null, expectedDurationMs = null))
        // Bytes of another file glued on make it larger than announced.
        val glued = downloadProblem(expectedBytes = 2 * gb, actualBytes = 2_500_000_000L, probedDurationMs = null, expectedDurationMs = null)
        assertEquals(true, glued?.message?.startsWith("File damaged"))
        assertNull(downloadProblem(expectedBytes = null, actualBytes = gb, probedDurationMs = null, expectedDurationMs = null))
    }
}
