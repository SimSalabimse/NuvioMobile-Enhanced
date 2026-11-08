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
    fun ax5SeasonHeaderStacksAndTheCaptionKeepsItsLongestWord() {
        assertFalse(seasonHeaderStacks(1f))
        assertFalse(seasonHeaderStacks(1.35f))
        assertTrue(seasonHeaderStacks(1.5f))
        assertTrue(seasonHeaderStacks(3.12f))
        assertEquals(1, seasonCaptionMaxLines(1.35f))
        assertEquals(2, seasonCaptionMaxLines(3.12f))
        assertEquals(100f, seasonPosterWidthDp(1f, 100f, "Season 1", 15f))
        val wide = seasonPosterWidthDp(3.12f, 100f, "Season 1", 15f)
        assertTrue(wide > 100f)
        assertTrue(15f * 3.12f * HERO_TITLE_CHAR_EM * longestWordLength("Season 1") <= wide + 0.01f)
        assertTrue(seasonHeaderFits(3.12f, "Seasons", availableDp = 320f, headerBaseSp = 18f))
        assertFalse(seasonHeaderFits(3.12f, "Seasons", availableDp = 110f, headerBaseSp = 18f))
    }

    @Test
    fun accessibilityRowStacksAndKeepsTheHitTarget() {
        assertTrue(smartDownloadPickerStacks(3.12f))
        assertTrue(smartDownloadPickerStacks(1.65f))
        assertFalse(smartDownloadPickerStacks(1.35f))
        assertEquals(44f, ACCESSIBILITY_CONTROL_MIN_DP)
    }
}
