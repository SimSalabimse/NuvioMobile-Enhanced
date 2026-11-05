package com.nuvio.app.features.details

import com.nuvio.app.core.automation.AutomationStubs
import com.nuvio.app.core.perf.TmdbImageSizing
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
        val original = "https://image.tmdb.org/t/p/original/abc.jpg"
        assertEquals(original, largestSeasonPosterUrl("https://image.tmdb.org/t/p/w185/abc.jpg"))
        assertEquals(original, largestSeasonPosterUrl("https://image.tmdb.org/t/p/w342/abc.jpg"))
        assertEquals(original, largestSeasonPosterUrl("https://image.tmdb.org/t/p/w500/abc.jpg"))
        assertEquals(original, largestSeasonPosterUrl("https://image.tmdb.org/t/p/w780/abc.jpg"))
        assertEquals(original, largestSeasonPosterUrl("https://image.tmdb.org/t/p/h632/abc.jpg"))
        assertEquals(original, largestSeasonPosterUrl(original))
        assertEquals(
            "https://cdn.example/poster.jpg",
            largestSeasonPosterUrl("https://cdn.example/poster.jpg"),
        )
        assertNull(largestSeasonPosterUrl(null))
        assertEquals(
            "fixture://art/season/2/Season_2",
            largestSeasonPosterUrl(" fixture://art/season/2/Season_2 "),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500",
            largestSeasonPosterUrl("https://image.tmdb.org/t/p/w500"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/abc.jpg",
            seasonPosterFallbackUrl(original),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w780/abc.jpg",
            seasonPosterFallbackUrl("https://image.tmdb.org/t/p/w1280/abc.jpg"),
        )
        assertNull(seasonPosterFallbackUrl("https://image.tmdb.org/t/p/w780/abc.jpg"))
        assertNull(seasonPosterFallbackUrl("https://cdn.example/poster.jpg"))
        assertNull(seasonPosterFallbackUrl(null))
        assertEquals(
            "season-poster-full:$original",
            seasonPosterFullImageCacheKey(original),
        )
        // A measured slot rewrites `original` back down. Width 0 (Size.ORIGINAL) does not.
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/abc.jpg",
            TmdbImageSizing.forWidth(original, 1_080),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            TmdbImageSizing.forWidth(original, 420),
        )
        assertNull(TmdbImageSizing.forWidth(original, 0))
        assertTrue(seasonPosterPreviewEnabled("https://image.tmdb.org/t/p/w342/poster.jpg"))
    }

    @Test
    fun secondTapNearTheFirstOpensAndALongPressDoesNot() {
        val slop = 48f
        val tracker = SeasonPosterTapTracker()
        val first = tracker.onTap(timeMillis = 1_000L, x = 10f, y = 20f, slopPx = slop)
        assertTrue(first.selectSeason)
        assertFalse(first.enlargePoster)

        val tooSoonFar = seasonPosterTapDecision(
            previous = SeasonPosterTap(1_000L, 10f, 20f),
            timeMillis = 1_200L,
            x = 80f,
            y = 20f,
            slopPx = slop,
        )
        assertTrue(tooSoonFar.selectSeason)
        assertFalse(tooSoonFar.enlargePoster)

        val second = tracker.onTap(timeMillis = 1_300L, x = 40f, y = 30f, slopPx = slop)
        assertTrue(second.selectSeason)
        assertTrue(second.enlargePoster)

        val late = tracker.onTap(timeMillis = 1_300L + SeasonPosterDoubleTapWindowMillis + 1, x = 40f, y = 30f, slopPx = slop)
        assertFalse(late.enlargePoster)

        tracker.onLongPress()
        val afterMenu = tracker.onTap(timeMillis = 1_400L, x = 40f, y = 30f, slopPx = slop)
        assertTrue(afterMenu.selectSeason)
        assertFalse(afterMenu.enlargePoster)

        val edge = seasonPosterTapDecision(
            previous = SeasonPosterTap(0L, 0f, 0f),
            timeMillis = SeasonPosterDoubleTapWindowMillis,
            x = slop,
            y = 0f,
            slopPx = slop,
        )
        assertTrue(edge.enlargePoster)
    }

    @Test
    fun dragAndLongPressDoNotCountAsATap() {
        val pointer = SeasonPosterPointerState()
        pointer.down(4f, 8f)
        pointer.move(10f, 8f, slopPx = 16f)
        pointer.up()
        assertEquals(10f to 8f, pointer.consumeTap())

        pointer.down(0f, 0f)
        pointer.move(40f, 0f, slopPx = 16f)
        pointer.up()
        assertNull(pointer.consumeTap())

        pointer.down(1f, 1f)
        pointer.cancel()
        pointer.up()
        assertNull(pointer.consumeTap())
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
        assertTrue(seasonPosterCrossfadeMillis(reduceMotion = false) in 1..180)
        assertEquals(0, seasonPosterCrossfadeMillis(reduceMotion = true))
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
