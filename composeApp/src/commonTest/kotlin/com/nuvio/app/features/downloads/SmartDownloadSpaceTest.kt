package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class SmartDownloadSpaceTest {
    private val gb = 1_000_000_000L

    @Test
    fun `queues only the episodes that fit with their reserve`() {
        val sizes = listOf(4 * gb, 4 * gb, 4 * gb)
        // 9 GB free: 4 + 0.5 reserve fits, then 4 + 0.5 fits in the remaining 5 GB, the third does not.
        assertEquals(2, smartDownloadsThatFit(sizes, { it }, null, 9 * gb, 0L).size)
    }

    @Test
    fun `running downloads take their remaining bytes first`() {
        assertEquals(0, smartDownloadsThatFit(listOf(4 * gb), { it }, null, 7 * gb, 3 * gb).size)
        assertEquals(1, smartDownloadsThatFit(listOf(4 * gb), { it }, null, 8 * gb, 3 * gb).size)
    }

    @Test
    fun `unknown size uses the show's last episode, unknown free space lets it through`() {
        val rows = listOf<Long?>(null)
        assertEquals(0, smartDownloadsThatFit(rows, { it }, 4 * gb, 360_000_000L, 0L).size)
        assertEquals(1, smartDownloadsThatFit(rows, { it }, 4 * gb, null, 0L).size)
    }
}
