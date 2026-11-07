package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackdropFocusTest {

    @Test
    fun fullWidthWindowStaysCentered() {
        val profile = BackdropFocusProfile(FloatArray(32) { if (it > 24) 1f else 0.01f })
        assertEquals(0f, profile.horizontalBias(1f))
        assertEquals(0f, profile.horizontalBias(0.98f))
    }

    @Test
    fun uniformLandscapeCropStaysCentered() {
        val profile = BackdropFocusProfile(FloatArray(40) { 1f })
        assertEquals(0f, profile.horizontalBias(0.5f))
    }

    @Test
    fun strongOffCenterSubjectShiftsTheWindowWithoutReachingTheEdge() {
        val columns = FloatArray(40) { index -> if (index >= 30) 1f else 0.01f }
        val bias = BackdropFocusProfile(columns).horizontalBias(0.25f)
        assertTrue(bias > 0.2f, "expected a rightward bias, was $bias")
        assertTrue(bias < 1f, "bias should stay damped inside the frame, was $bias")
    }

    @Test
    fun portraitSampleHasNoVerticalCropControl() {
        val width = 8
        val height = 24
        val pixels = IntArray(width * height) { index ->
            val y = index / width
            if (y < 4) 0xFFFFCC88.toInt() else 0xFF101820.toInt()
        }
        val profile = analyzeBackdropFocus(SampledPixels(pixels, width, height))
        assertEquals(width, profile.columns.size)
        assertEquals(0f, profile.horizontalBias(1f))
    }
}
