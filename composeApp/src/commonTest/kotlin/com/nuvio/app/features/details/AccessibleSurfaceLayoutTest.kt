package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessibleSurfaceLayoutTest {
    @Test
    fun defaultScaleKeepsTheDisplaySize() {
        assertFalse(accessibilityTextSize(1f))
        assertFalse(smartDownloadPickerStacks(1f))
        assertEquals(38f, heroTitleBaseSp(1f, contentWidthDp = 272f, defaultBaseSp = 38f, longestWordChars = 9))
        assertEquals(32f, heroTitleBaseSp(1.35f, contentWidthDp = 272f, defaultBaseSp = 32f, longestWordChars = 9))
    }

    @Test
    fun ax5SmallPhoneFitsNorthwindWithoutCuttingTheScale() {
        val base = heroTitleBaseSp(
            fontScale = 3.12f,
            contentWidthDp = 272f,
            defaultBaseSp = 36f,
            longestWordChars = longestWordLength("Northwind"),
        )
        assertTrue(base < 36f)
        assertTrue(base * 3.12f <= 272f / (HERO_TITLE_CHAR_EM * 9) + 0.01f)
        assertTrue(base * 3.12f > 36f, "AX5 title stays larger than the default display size")
    }

    @Test
    fun ax5LargeWidthKeepsADisplayBaseWhenTheWordFits() {
        val small = heroTitleBaseSp(3.12f, contentWidthDp = 272f, defaultBaseSp = 38f, longestWordChars = 9)
        val large = heroTitleBaseSp(3.12f, contentWidthDp = 900f, defaultBaseSp = 38f, longestWordChars = 9)
        assertEquals(38f, large)
        assertTrue(large > small)
    }

    @Test
    fun harborBreaksOnTheSpace() {
        assertEquals(6, longestWordLength("Harbor Lane"))
        val base = heroTitleBaseSp(3.12f, contentWidthDp = 272f, defaultBaseSp = 38f, longestWordChars = 6)
        assertTrue(base * 3.12f * HERO_TITLE_CHAR_EM * 6 <= 272f + 0.01f)
    }

    @Test
    fun accessibilityRowStacksAndKeepsTheHitTarget() {
        assertTrue(smartDownloadPickerStacks(3.12f))
        assertTrue(smartDownloadPickerStacks(1.65f))
        assertFalse(smartDownloadPickerStacks(1.35f))
        assertEquals(44f, ACCESSIBILITY_CONTROL_MIN_DP)
    }
}
