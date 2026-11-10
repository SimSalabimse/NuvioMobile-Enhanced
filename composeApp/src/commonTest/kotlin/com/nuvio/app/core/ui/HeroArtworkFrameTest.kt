package com.nuvio.app.core.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroArtworkFrameTest {
    @Test
    fun tallArtFitsAColumnOnSmallAndLargePhonesWithoutExtraScale() {
        val posterWidth = 1000
        val posterHeight = 1500
        val cases = listOf(
            heroArtworkDecision(posterWidth, posterHeight, 320f, 480f, HOME_LANDSCAPE_EXTRA_SCALE) to 320f to 480f,
            heroArtworkDecision(posterWidth, posterHeight, 430f, 645f, HOME_LANDSCAPE_EXTRA_SCALE) to 430f to 645f,
            heroArtworkDecision(posterWidth, posterHeight, 320f, 426f, DETAIL_LANDSCAPE_EXTRA_SCALE) to 320f to 426f,
            heroArtworkDecision(posterWidth, posterHeight, 430f, 572f, DETAIL_LANDSCAPE_EXTRA_SCALE) to 430f to 572f,
        )
        cases.forEach { (pair, height) ->
            val (decision, width) = pair
            assertEquals(HeroArtworkFit.PosterColumn, decision.fit)
            assertEquals(1f, decision.extraScale)
            assertEquals(PORTRAIT_POSTER_WIDTH_FRACTION, decision.widthFraction)
            assertFalse(decision.scrollScaleEnabled)
            assertFalse(decision.horizontalFocus)
            val fraction = heroImageHeightFraction(decision, width, height)
            assertTrue(fraction < 1f, "poster column leaves room for the title")
            assertTrue(fraction > 0.34f)
        }
    }

    @Test
    fun phonePortraitHomeFillsTheHeroBehindTheTitle() {
        val decision = phonePortraitHeroArtwork()
        assertEquals(HeroArtworkFit.CropLandscape, decision.fit)
        assertEquals(1f, decision.extraScale)
        assertEquals(1f, decision.widthFraction)
        assertFalse(decision.scrollScaleEnabled)
        val frameWidth = 402f
        val frameHeight = 874f * 0.82f
        assertEquals(1f, heroImageHeightFraction(decision, frameWidth, frameHeight, portrait = false))
        val band = heroImageHeightFraction(
            heroArtworkDecision(1920, 1080, frameWidth, frameHeight, HOME_LANDSCAPE_EXTRA_SCALE, portrait = true),
            frameWidth,
            frameHeight,
            portrait = true,
        )
        assertTrue(band < 0.5f)
        assertTrue(frameHeight > frameWidth / PORTRAIT_WIDE_BAND_ASPECT + 200f)
    }

    @Test
    fun wideArtUsesAFullBleedBandOnAPortraitPhone() {
        val wide = 1920
        val tall = 1080
        val cases = listOf(
            heroArtworkDecision(wide, tall, 320f, 480f, HOME_LANDSCAPE_EXTRA_SCALE) to 320f to 480f,
            heroArtworkDecision(wide, tall, 430f, 645f, HOME_LANDSCAPE_EXTRA_SCALE) to 430f to 645f,
            heroArtworkDecision(wide, tall, 320f, 426f, DETAIL_LANDSCAPE_EXTRA_SCALE) to 320f to 426f,
            heroArtworkDecision(wide, tall, 430f, 572f, DETAIL_LANDSCAPE_EXTRA_SCALE) to 430f to 572f,
        )
        cases.forEach { (pair, height) ->
            val (decision, width) = pair
            assertEquals(HeroArtworkFit.WideBand, decision.fit)
            assertEquals(1f, decision.extraScale)
            assertEquals(1f, decision.widthFraction)
            assertFalse(decision.scrollScaleEnabled)
            assertTrue(decision.horizontalFocus)
            val fraction = heroImageHeightFraction(decision, width, height)
            val band = width / PORTRAIT_WIDE_BAND_ASPECT
            assertEquals(band / height, fraction, 0.001f)
            assertTrue(fraction < 0.8f, "the band is not the whole tall slot")
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
            assertEquals(1f, heroImageHeightFraction(decision, 1024f, 430f))
        }
        listOf(smallHome, largeHome).forEach { decision ->
            assertEquals(HeroArtworkFit.CropLandscape, decision.fit)
            assertEquals(HOME_LANDSCAPE_EXTRA_SCALE, decision.extraScale)
            assertTrue(decision.scrollScaleEnabled)
            assertTrue(decision.horizontalFocus)
        }
        val posterInLandscape = heroArtworkDecision(1000, 1500, 1024f, 430f, DETAIL_LANDSCAPE_EXTRA_SCALE)
        assertEquals(HeroArtworkFit.PosterColumn, posterInLandscape.fit)
        assertEquals(1f, posterInLandscape.extraScale)
        assertEquals(1f, posterInLandscape.widthFraction)
        assertFalse(posterInLandscape.scrollScaleEnabled)
        assertEquals(1f, heroImageHeightFraction(posterInLandscape, 1024f, 430f))
    }

    @Test
    fun unknownSizeDoesNotApplyTheExtraScales() {
        val unknown = heroArtworkDecision(0, 0, 390f, 520f, HOME_LANDSCAPE_EXTRA_SCALE)
        assertEquals(HeroArtworkFit.WideBand, unknown.fit)
        assertEquals(1f, unknown.extraScale)
        assertFalse(unknown.scrollScaleEnabled)
        assertTrue(unknown.horizontalFocus)
        val landscape = heroArtworkDecision(0, 0, 800f, 400f, HOME_LANDSCAPE_EXTRA_SCALE)
        assertEquals(HeroArtworkFit.CropLandscape, landscape.fit)
        assertEquals(1f, landscape.extraScale)
        assertFalse(landscape.scrollScaleEnabled)
    }

    @Test
    fun portraitSlotIsThePictureSoTheTitleIsNotLeftInAGap() {
        val width = 390f
        val wideSlot = portraitArtworkSlotHeight(width.dp, tallArt = false).value
        assertEquals(width * 9f / 16f, wideSlot, 0.01f)
        assertTrue(wideSlot < width)
        val wide = heroArtworkDecision(1920, 1080, width, wideSlot, HOME_LANDSCAPE_EXTRA_SCALE, portrait = true)
        assertEquals(HeroArtworkFit.WideBand, wide.fit)
        assertEquals(1f, wide.extraScale)
        assertFalse(wide.scrollScaleEnabled)
        assertEquals(1f, heroImageHeightFraction(wide, width, wideSlot, portrait = true), 0.001f)

        val posterSlot = portraitArtworkSlotHeight(width.dp, tallArt = true).value
        assertEquals(width * PORTRAIT_POSTER_WIDTH_FRACTION / PORTRAIT_POSTER_ASPECT, posterSlot, 0.01f)
        val poster = heroArtworkDecision(400, 600, width, posterSlot, HOME_LANDSCAPE_EXTRA_SCALE, portrait = true)
        assertEquals(HeroArtworkFit.PosterColumn, poster.fit)
        assertEquals(PORTRAIT_POSTER_WIDTH_FRACTION, poster.widthFraction)
        assertEquals(1f, poster.extraScale)
        assertEquals(1f, heroImageHeightFraction(poster, width, posterSlot, portrait = true), 0.001f)

        assertEquals(320f * 9f / 16f, portraitArtworkSlotHeight(320.dp, tallArt = false).value, 0.01f)
        assertEquals(430f * 9f / 16f, portraitArtworkSlotHeight(430.dp, tallArt = false).value, 0.01f)
    }
}
