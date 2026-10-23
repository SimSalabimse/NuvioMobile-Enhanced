package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class CompactByteCountTest {
    @Test
    fun megabytesAreWholeAndGigabytesHaveOneDecimal() {
        assertEquals(134_000_000L, compactByteCount(134_216_000L))
        assertEquals(1_900_000_000L, compactByteCount(1_874_000_000L))
        assertEquals(12_000L, compactByteCount(12_345L))
        assertEquals(512L, compactByteCount(512L))
    }
}
