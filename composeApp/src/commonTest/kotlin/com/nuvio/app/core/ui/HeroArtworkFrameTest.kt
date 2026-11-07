package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroArtworkFrameTest {
    @Test
    fun portraitArtFitsOnSmallAndLargePhonesWithoutExtraScale() {
        val posterWidth = 1000
        val posterHeight = 1500
        val smallDetails = heroArtworkDecision(posterWidth, posterHeight, 320f, 426f, DETAIL_LANDSCAPE_EXTRA_SCALE)
        val largeDetails = heroArtworkDecision(posterWidth, posterHeight, 430f, 572f, DETAIL_LANDSCAPE_EXTRA_SCALE)
        val smallHome = heroArtworkDecision(posterWidth, posterHeight, 320f, 480f, HOME_LANDSCAPE_EXTRA_SCALE)
        val largeHome = heroArtworkDecision(posterWidth, posterHeight, 430f, 645f, HOME_LANDSCAPE_EXTRA_SCALE)
        listOf(smallDetails, largeDetails, smallHome, largeHome).forEach { decision ->
            assertEquals(HeroArtworkFit.FitPortrait, decision.fit)
            assertEquals(1f, decision.extraScale)
            assertFalse(decision.scrollScaleEnabled)
            assertFalse(decision.horizontalFocus)
        }
    }

    @Test
    fun landscapeArtKeepsCropFocusAndTheExistingExtraScale() {
        val wide = 1920
        val tall = 1080
        val details = heroArtworkDecision(wide, tall, 390f, 520f, DETAIL_LANDSCAPE_EXTRA_SCALE)
        val home = heroArtworkDecision(wide, tall, 390f, 585f, HOME_LANDSCAPE_EXTRA_SCALE)
        val largeHome = heroArtworkDecision(wide, tall, 430f, 645f, HOME_LANDSCAPE_EXTRA_SCALE)
        assertEquals(HeroArtworkFit.CropLandscape, details.fit)
        assertEquals(DETAIL_LANDSCAPE_EXTRA_SCALE, details.extraScale)
        assertTrue(details.horizontalFocus)
        assertTrue(details.scrollScaleEnabled)
        assertEquals(HOME_LANDSCAPE_EXTRA_SCALE, home.extraScale)
        assertEquals(home, largeHome.copy())
        assertEquals(HeroArtworkFit.CropLandscape, largeHome.fit)
    }

    @Test
    fun unknownSizeDoesNotApplyTheExtraScales() {
        val unknown = heroArtworkDecision(0, 0, 390f, 520f, HOME_LANDSCAPE_EXTRA_SCALE)
        assertEquals(HeroArtworkFit.FitPortrait, unknown.fit)
        assertEquals(1f, unknown.extraScale)
        assertFalse(unknown.scrollScaleEnabled)
        assertFalse(unknown.horizontalFocus)
    }
}
