package com.nuvio.app.features.stats

import kotlin.test.Test
import kotlin.test.assertEquals

class MinutesLabelTest {
    @Test
    fun labelsStayShortEnoughForTheSevenDayChart() {
        assertEquals("–", minutesLabel(0L))
        assertEquals("1m", minutesLabel(20_000L))
        assertEquals("45m", minutesLabel(45 * 60_000L))
        assertEquals("2h", minutesLabel(120 * 60_000L))
        assertEquals("1h05", minutesLabel(65 * 60_000L))
        assertEquals("1h30", minutesLabel(90 * 60_000L))
    }
}
