package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipRangeLabelTest {
    @Test
    fun formatsRange() {
        assertEquals("1:12\u20132:41", skipRangeLabel(72.4, 161.9))
        assertEquals("0:05\u20131:00:00", skipRangeLabel(5.0, 3600.0))
        assertNull(skipRangeLabel(10.0, 10.0))
    }
}
