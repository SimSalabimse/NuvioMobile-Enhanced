package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptioningReadTest {
    @Test
    fun aThrowingCaptioningReadStaysOff() {
        assertEquals(false, captioningFlagOrFalse { error("captioning") })
        assertNull(captioningValueOrNull<String> { error("locale") })
    }

    @Test
    fun aCaptioningReadKeepsTheValueWhenItSucceeds() {
        assertEquals(true, captioningFlagOrFalse { true })
        assertEquals("eng", captioningValueOrNull { "eng" })
    }
}
