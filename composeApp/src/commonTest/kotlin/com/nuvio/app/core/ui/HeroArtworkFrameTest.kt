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
    fun wideArtFitsAPortraitFrameOnSmallAndLargePhones() {
        val wide = 1920
        val tall = 1080
        val cases = listOf(
            heroArtworkDecision(wide, tall, 320f, 426f, DETAIL_LANDSCAPE_EXTRA_SCALE),
            heroArtworkDecision(wide, tall, 430f, 572f, DETAIL_LANDSCAPE_EXTRA_SCALE),
            heroArtworkDecision(wide, tall, 320f, 480f, HOME_LANDSCAPE_EXTRA_SCALE),
            heroArtworkDecision(wide, tall, 430f, 645f, HOME_LANDSCAPE_EXTRA_SCALE),
        )
        cases.forEach { decision ->
            assertEquals(HeroArtworkFit.FitPortrait, decision.fit)
            assertEquals(1f, decision.extraScale)
            assertFalse(decision.scrollScaleEnabled)
            assertFalse(decision.horizontalFocus)
        }
        assertTrue(portraitHeroFrame(320f, 426f))
        assertFalse(portraitHeroFrame(667f, 280f))
    }

    @Test
    fun wideArtKeepsCropOnALandscapeFrame() {
        val wide = 1920
        val tall = 1080
        val smallDetails = heroArtworkDecision(wide, tall, 667f, 280f, DETAIL_LANDSCAPE_EXTRA_SCALE)
        val largeDetails = heroArtworkDecision(wide, tall, 1024f, 430f, DETAIL_LANDSCAPE_EXTRA_SCALE)
        val smallHome = heroArtworkDecision(wide, tall, 667f, 320f, HOME_LANDSCAPE_EXTRA_SCALE)
        val largeHome = heroArtworkDecision(wide, tall, 1366f, 500f, HOME_LANDSCAPE_EXTRA_SCALE)
        listOf(smallDetails, largeDetails).forEach { decision ->
            assertEquals(HeroArtworkFit.CropLandscape, decision.fit)
            assertEquals(DETAIL_LANDSCAPE_EXTRA_SCALE, decision.extraScale)
            assertTrue(decision.scrollScaleEnabled)
            assertTrue(decision.horizontalFocus)
        }
        listOf(smallHome, largeHome).forEach { decision ->
            assertEquals(HeroArtworkFit.CropLandscape, decision.fit)
            assertEquals(HOME_LANDSCAPE_EXTRA_SCALE, decision.extraScale)
            assertTrue(decision.scrollScaleEnabled)
            assertTrue(decision.horizontalFocus)
        }
        val posterInLandscape = heroArtworkDecision(1000, 1500, 1024f, 430f, DETAIL_LANDSCAPE_EXTRA_SCALE)
        assertEquals(HeroArtworkFit.FitPortrait, posterInLandscape.fit)
        assertEquals(1f, posterInLandscape.extraScale)
        assertFalse(posterInLandscape.scrollScaleEnabled)
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
