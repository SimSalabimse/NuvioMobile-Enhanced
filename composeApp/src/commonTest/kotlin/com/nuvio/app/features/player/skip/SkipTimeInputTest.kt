package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipTimeInputTest {
    @Test
    fun `timestamps always render as HH MM SS`() {
        assertEquals("00:00:00", formatSecondsToHms(0.0))
        assertEquals("00:01:05", formatSecondsToHms(65.0))
        assertEquals("01:30:00", formatSecondsToHms(5_400.0))
        assertEquals("02:05:09", formatSecondsToHms(7_509.9))
        assertEquals("00:00:00", formatSecondsToHms(-4.0))
    }

    @Test
    fun `HH MM SS and legacy MM SS both parse`() {
        assertEquals(65.0, parseTimeToSeconds("00:01:05"))
        assertEquals(65.0, parseTimeToSeconds("1:05"))
        assertEquals(5_400.0, parseTimeToSeconds("90:00"))
        assertEquals(5_400.0, parseTimeToSeconds("01:30:00"))
        assertEquals(5_400.0, parseTimeToSeconds("1:30:00"))
        assertEquals(65.0, parseTimeToSeconds("65"))
        assertEquals(90.0, parseTimeToSeconds("1.30"))
        assertNull(parseTimeToSeconds(""))
        assertNull(parseTimeToSeconds("00:60:00"))
        assertNull(parseTimeToSeconds("00:00:60"))
        assertNull(parseTimeToSeconds("1:2:3:4"))
    }

    @Test
    fun `nudges wrap parts and stay inside the clock`() {
        assertEquals("00:00:00", stepSkipTimestamp("00:00:05", -10))
        assertEquals("01:00:00", stepSkipTimestamp("00:59:30", 30))
        assertEquals("01:00:00", stepSkipTimePart("00:59:00", SkipTimePart.MINUTES, 1))
        assertEquals("00:59:59", stepSkipTimePart("01:00:00", SkipTimePart.SECONDS, -1))
        assertEquals("99:59:59", stepSkipTimePart("99:00:00", SkipTimePart.HOURS, 1))
    }

    @Test
    fun `editing one part keeps the others`() {
        assertEquals("01:02:03", replaceSkipTimePart("01:00:03", SkipTimePart.MINUTES, "2"))
        assertEquals("01:00:59", replaceSkipTimePart("01:00:03", SkipTimePart.SECONDS, "99"))
        assertEquals("12:00:03", replaceSkipTimePart("01:00:03", SkipTimePart.HOURS, "12"))
        assertEquals("00:00:03", replaceSkipTimePart("01:00:03", SkipTimePart.HOURS, ""))
        assertEquals("01:30:00", replaceSkipTimePart("90:00", SkipTimePart.SECONDS, "0"))
    }

    @Test
    fun `wheel centers the row closest to the viewport middle`() {
        val rows = listOf(
            WheelRow(index = 0, offset = 0, size = 36),
            WheelRow(index = 1, offset = 36, size = 36),
            WheelRow(index = 2, offset = 72, size = 36),
        )
        assertEquals(1, centeredWheelIndex(viewportStart = 0, viewportEnd = 108, rows = rows))
        assertEquals(2, centeredWheelIndex(viewportStart = 36, viewportEnd = 144, rows = rows))
        assertNull(centeredWheelIndex(viewportStart = 0, viewportEnd = 108, rows = emptyList()))
    }
}
