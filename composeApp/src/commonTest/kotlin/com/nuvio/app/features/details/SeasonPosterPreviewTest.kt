package com.nuvio.app.features.details

import com.nuvio.app.core.automation.AUTOMATION_SEASON_POSTER_URL
import com.nuvio.app.core.automation.AutomationStubs
import com.nuvio.app.core.automation.automationSeasonPosterUrl
import com.nuvio.app.core.perf.TmdbImageSizing
import com.nuvio.app.core.ui.automationImageRequestAllowed
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
            "season-poster-full:$original@1290x1935",
            seasonPosterFullImageCacheKey(original, 1290, 1935),
        )
        assertEquals(SeasonPosterFrame(1290f, 1935f), seasonPosterDecodeSizePx(1290, 2500))
        assertEquals(SeasonPosterFrame(1600f, 2400f), seasonPosterDecodeSizePx(2000, 4000))
        assertEquals(SeasonPosterFrame(1080f, 1620f), seasonPosterDecodeSizePx(1080, 2400))
        assertEquals(SeasonPosterFrame(0f, 0f), seasonPosterDecodeSizePx(0, 800))
        assertEquals(300L, seasonPosterScrollDelayMillis(defer = true, alreadyPositioned = true))
        assertEquals(0L, seasonPosterScrollDelayMillis(defer = true, alreadyPositioned = false))
        assertEquals(0L, seasonPosterScrollDelayMillis(defer = false, alreadyPositioned = true))
        assertEquals("image.tmdb.org/t/p/w500", seasonPosterLogTarget(
            "https://image.tmdb.org/t/p/w500/1E5baAaEse26fej7uHcjOgEE2t2.jpg?token=secret",
        ))
        assertEquals("image.tmdb.org/t/p/original", seasonPosterLogTarget(original))
        assertEquals("cdn.example", seasonPosterLogTarget("https://cdn.example/poster.jpg?x=1"))
        assertEquals("none", seasonPosterLogTarget(null))
        assertTrue(seasonPosterDiagnosticsEnabled(automationActive = true, diagnosticsEnabled = false))
        assertFalse(seasonPosterDiagnosticsEnabled(automationActive = false, diagnosticsEnabled = false))
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
        val gesture = SeasonPosterGesture()
        gesture.onDown(season = 3, timeMillis = 1_000L, x = 10f, y = 20f, slopPx = slop)
        val first = gesture.onUp(season = 3, timeMillis = 1_040L, x = 10f, y = 20f)
        assertTrue(first!!.selectSeason)
        assertFalse(first.enlargePoster)

        assertFalse(
            seasonPosterSecondDownOpens(
                previousUp = SeasonPosterTap(1_040L, 10f, 20f, season = 3),
                season = 3,
                downTimeMillis = 1_200L,
                x = 80f,
                y = 20f,
                slopPx = slop,
            ),
        )
        gesture.onDown(season = 3, timeMillis = 1_200L, x = 80f, y = 20f, slopPx = slop)
        assertFalse(gesture.onUp(season = 3, timeMillis = 1_240L, x = 80f, y = 20f)!!.enlargePoster)

        gesture.onDown(season = 3, timeMillis = 1_040L, x = 12f, y = 22f, slopPx = slop)
        gesture.onDrag()
        assertNull(gesture.consumeDecision())

        val fresh = SeasonPosterGesture()
        fresh.onDown(season = 3, timeMillis = 0L, x = 0f, y = 0f, slopPx = slop)
        fresh.onUp(season = 3, timeMillis = 20L, x = 0f, y = 0f)
        fresh.noteSelectionChanged()
        fresh.onDown(season = 3, timeMillis = 20L + SeasonPosterDoubleTapWindowMillis, x = slop, y = 0f, slopPx = slop)
        val second = fresh.onUp(season = 3, timeMillis = 400L, x = slop, y = 0f)
        assertTrue(second!!.selectSeason)
        assertTrue(second.enlargePoster)

        fresh.onDown(
            season = 3,
            timeMillis = 400L + SeasonPosterDoubleTapWindowMillis + 1,
            x = slop,
            y = 0f,
            slopPx = slop,
        )
        assertFalse(fresh.onUp(season = 3, timeMillis = 800L, x = slop, y = 0f)!!.enlargePoster)

        fresh.onDown(season = 3, timeMillis = 900L, x = slop, y = 0f, slopPx = slop)
        fresh.onLongPress()
        assertNull(fresh.onUp(season = 3, timeMillis = 1_400L, x = slop, y = 0f))
        fresh.onDown(season = 3, timeMillis = 1_420L, x = slop, y = 0f, slopPx = slop)
        assertFalse(fresh.onUp(season = 3, timeMillis = 1_450L, x = slop, y = 0f)!!.enlargePoster)

        val dragged = SeasonPosterGesture()
        dragged.onDown(season = 3, timeMillis = 0L, x = 0f, y = 0f, slopPx = slop)
        dragged.onUp(season = 3, timeMillis = 10L, x = 0f, y = 0f)
        dragged.onDown(season = 3, timeMillis = 80L, x = 4f, y = 4f, slopPx = slop)
        dragged.onDrag()
        assertFalse(dragged.onUp(season = 3, timeMillis = 100L, x = 40f, y = 0f)!!.enlargePoster)

        val scrolled = SeasonPosterGesture()
        scrolled.onDown(season = 3, timeMillis = 0L, x = 0f, y = 0f, slopPx = slop)
        scrolled.onUp(season = 3, timeMillis = 10L, x = 0f, y = 0f)
        scrolled.onDrag()
        scrolled.onDown(season = 3, timeMillis = 40L, x = 0f, y = 0f, slopPx = slop)
        assertFalse(scrolled.onUp(season = 3, timeMillis = 50L, x = 0f, y = 0f)!!.enlargePoster)

        val otherSeason = SeasonPosterGesture()
        otherSeason.onDown(season = 1, timeMillis = 0L, x = 0f, y = 0f, slopPx = slop)
        otherSeason.onUp(season = 1, timeMillis = 10L, x = 0f, y = 0f)
        otherSeason.onDown(season = 2, timeMillis = 40L, x = 0f, y = 0f, slopPx = slop)
        assertFalse(otherSeason.onUp(season = 2, timeMillis = 50L, x = 0f, y = 0f)!!.enlargePoster)
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
        assertTrue(zoom.durationMillis in 1..280)
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
        listOf(1, 2).forEach { season ->
            val url = largestSeasonPosterUrl(series.seasonPosters[season])
            assertEquals(fixtureSeasonPosterUrl(season), url)
            assertTrue(url!!.startsWith("fixture://"))
            assertEquals(url, series.videos.first { it.season == season }.seasonPoster)
            assertEquals(automationSeasonPosterUrl(season), url)
        }
        assertEquals(AUTOMATION_SEASON_POSTER_URL, series.seasonPosters[3])
        assertEquals(AUTOMATION_SEASON_POSTER_URL, series.videos.first { it.season == 3 }.seasonPoster)
        val enlarged = largestSeasonPosterUrl(AUTOMATION_SEASON_POSTER_URL)
        assertEquals("https://image.tmdb.org/t/p/original/1E5baAaEse26fej7uHcjOgEE2t2.jpg", enlarged)
        assertTrue(automationImageRequestAllowed(AUTOMATION_SEASON_POSTER_URL))
        assertTrue(automationImageRequestAllowed(enlarged))
        assertTrue(automationImageRequestAllowed(seasonPosterFallbackUrl(enlarged)))
        assertFalse(automationImageRequestAllowed("https://image.tmdb.org/t/p/w500/other.jpg"))
        assertFalse(automationImageRequestAllowed("https://cdn.example/poster.jpg"))
        assertEquals("season_poster_3", seasonPosterAccessibilityId(3))
        assertEquals("season_poster_overlay", SeasonPosterOverlayAccessibilityId)
    }
}
