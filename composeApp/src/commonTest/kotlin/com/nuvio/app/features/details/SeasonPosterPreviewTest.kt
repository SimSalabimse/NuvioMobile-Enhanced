package com.nuvio.app.features.details

import com.nuvio.app.core.automation.AutomationStubs
import com.nuvio.app.core.uifixture.fixtureSeasonPosterUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonPosterPreviewTest {
    @Test
    fun blankPosterDoesNotOpenAndTmdbUsesTheOriginalRendition() {
        assertFalse(seasonPosterPreviewEnabled(null))
        assertFalse(seasonPosterPreviewEnabled("  "))
        assertNull(largestSeasonPosterUrl(null))
        assertNull(largestSeasonPosterUrl(" "))
        assertEquals(
            "fixture://art/season/2/Season_2",
            largestSeasonPosterUrl(" fixture://art/season/2/Season_2 "),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/abc.jpg",
            largestSeasonPosterUrl("https://image.tmdb.org/t/p/w500/abc.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/abc.jpg",
            largestSeasonPosterUrl("https://image.tmdb.org/t/p/w780/abc.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/abc.jpg",
            largestSeasonPosterUrl("https://image.tmdb.org/t/p/original/abc.jpg"),
        )
        assertEquals(
            "https://cdn.example/poster.jpg",
            largestSeasonPosterUrl("https://cdn.example/poster.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500",
            largestSeasonPosterUrl("https://image.tmdb.org/t/p/w500"),
        )
        assertTrue(seasonPosterPreviewEnabled("https://image.tmdb.org/t/p/w342/poster.jpg"))
    }

    @Test
    fun posterFitsTheSafeAreaWithoutCroppingOrUpscaling() {
        val phone = fitSeasonPoster(1000f, 2000f, 2000f, 3000f)
        assertEquals(1000f, phone.widthPx)
        assertEquals(1500f, phone.heightPx)

        val small = fitSeasonPoster(1000f, 2000f, 100f, 150f)
        assertEquals(100f, small.widthPx)
        assertEquals(150f, small.heightPx)

        val landscape = fitSeasonPoster(2000f, 800f, 1600f, 2400f)
        assertEquals(800f * (1600f / 2400f), landscape.widthPx, 0.01f)
        assertEquals(800f, landscape.heightPx, 0.01f)
        assertTrue(landscape.widthPx <= 1600f)
        assertTrue(landscape.heightPx <= 2400f)

        assertEquals(SeasonPosterFrame(0f, 0f), fitSeasonPoster(0f, 400f, 100f, 150f))
        val unknown = fitSeasonPoster(900f, 1800f, 0f, 0f)
        assertEquals(900f, unknown.widthPx)
        assertEquals(1350f, unknown.heightPx)
    }

    @Test
    fun motionStaysUnder300msAndReduceMotionDoesNotZoom() {
        val zoom = seasonPosterPreviewMotion(reduceMotion = false)
        val fade = seasonPosterPreviewMotion(reduceMotion = true)
        assertTrue(zoom.zoomsFromAnchor)
        assertTrue(zoom.durationMillis in 1..300)
        assertFalse(fade.zoomsFromAnchor)
        assertTrue(fade.durationMillis in 1..300)
        assertTrue(fade.durationMillis < zoom.durationMillis)
        assertFalse(seasonPosterDismisses(dragPx = 10f, thresholdPx = 72f))
        assertTrue(seasonPosterDismisses(dragPx = 72f, thresholdPx = 72f))
        assertFalse(seasonPosterDismisses(dragPx = 80f, thresholdPx = 0f))
    }

    @Test
    fun automationSeriesCarriesLocalPostersForTwoSeasons() {
        val series = AutomationStubs.series()
        val seasons = series.videos.mapNotNull { it.season }.toSet()
        assertTrue(seasons.size >= 2)
        seasons.forEach { season ->
            val url = largestSeasonPosterUrl(series.seasonPosters[season])
            assertEquals(fixtureSeasonPosterUrl(season), url)
            assertTrue(url!!.startsWith("fixture://"))
            assertFalse(url.startsWith("http://") || url.startsWith("https://"))
            assertEquals(url, series.videos.first { it.season == season }.seasonPoster)
        }
        assertEquals("season_poster_2", seasonPosterAccessibilityId(2))
        assertEquals("season_poster_overlay", SeasonPosterOverlayAccessibilityId)
    }
}
