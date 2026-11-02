package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoviePublishedCreditsTest {
    @Test
    fun openEndedCreditsCloseAtATrustedLength() {
        val media = TheIntroDbMediaResponse(
            credits = listOf(TheIntroDbSegment(startMs = 7_000_000, endMs = null)),
        )
        val credits = media.movieSkipIntervals(durationMs = 7_200_000).single()
        assertEquals("movie-credits", credits.type)
        assertEquals(TheIntroDb.PROVIDER, credits.provider)
        assertEquals(7_000.0, credits.startTime)
        assertEquals(7_200.0, credits.endTime)
        val action = credits.internalSkipAction(listOf(credits), durationMs = 7_200_000)
        assertEquals(7_200_000L, action?.targetMs)
        assertEquals(false, action?.skipsToPostCredits)
        assertEquals(7_200_000L, credits.skipSeekTargetMs(listOf(credits), 7_200_000))
    }

    @Test
    fun openEndedCreditsStayOpenWhenTheLengthIsUnknownOrAPlaceholder() {
        val media = TheIntroDbMediaResponse(
            credits = listOf(TheIntroDbSegment(startMs = 10_000, endMs = null)),
        )
        assertTrue(media.movieSkipIntervals(durationMs = 0L).isEmpty())
        assertTrue(media.movieSkipIntervals(durationMs = 60_000L).isEmpty())
        assertFalse(canCloseOpenMovieCredits(0L))
        assertFalse(canCloseOpenMovieCredits(60_000L))
        assertTrue(canCloseOpenMovieCredits(7_200_000L))
    }

    @Test
    fun openEndedCreditsStayOpenWhenTheCutDoesNotMatch() {
        val media = TheIntroDbMediaResponse(
            credits = listOf(TheIntroDbSegment(startMs = 7_000_000, endMs = null)),
        )
        assertTrue(
            media.movieSkipIntervals(
                durationMs = 10_800_000L,
                referenceDurationMs = 7_200_000L,
            ).isEmpty(),
        )
        assertFalse(canCloseOpenMovieCredits(10_800_000L, referenceDurationMs = 7_200_000L))
        assertTrue(canCloseOpenMovieCredits(7_220_000L, referenceDurationMs = 7_200_000L))
    }

    @Test
    fun aGapBetweenCreditRowsIsASceneAndAShortGapIsNot() {
        val scene = TheIntroDbMediaResponse(
            credits = listOf(
                TheIntroDbSegment(startMs = 6_000_000, endMs = 6_500_000),
                TheIntroDbSegment(startMs = 6_600_000, endMs = null),
            ),
        ).movieSkipIntervals(durationMs = 7_200_000)
        val roll = scene.first { it.type == "movie-credits" }
        val gap = scene.first { it.type == "post-credits" }
        assertEquals(6_000.0, roll.startTime)
        assertEquals(6_500.0, roll.endTime)
        assertEquals(6_500.0, gap.startTime)
        assertEquals(6_600.0, gap.endTime)
        assertEquals(TheIntroDb.PROVIDER, gap.provider)
        assertTrue(scene.none { it.type == "post-credits" && it.startTime == 6_600.0 && it.endTime > 6_600.0 })
        assertTrue(roll.showsMovieSceneNotice(scene, 7_200_000))

        val tight = movieCreditIntervals(
            rows = listOf(
                PublishedCreditRow(startSec = 100.0, endSec = 200.0),
                PublishedCreditRow(startSec = 207.0, endSec = 250.0),
            ),
            durationMs = 400_000L,
            provider = TheIntroDb.PROVIDER,
        )
        assertTrue(tight.none { it.type == "post-credits" })
        val first = tight.first { it.type == "movie-credits" }
        assertEquals(200_000L, first.skipSeekTargetMs(tight, 400_000L))
        assertFalse(first.showsMovieSceneNotice(tight, 400_000L))

        val exact = movieCreditIntervals(
            rows = listOf(
                PublishedCreditRow(startSec = 100.0, endSec = 200.0),
                PublishedCreditRow(startSec = 208.0, endSec = 250.0),
            ),
            durationMs = 400_000L,
            provider = TheIntroDb.PROVIDER,
        )
        assertEquals(1, exact.count { it.type == "post-credits" })
    }

    @Test
    fun introDbPostCreditsFieldIsASceneAndDoesNotSeekPastIt() {
        val intervals = IntroDbSegmentsResponse(
            outro = IntroDbSegment(startSec = 6_000.0, endSec = 7_000.0),
            postCredits = IntroDbSegment(startSec = 6_900.0, endSec = 7_100.0),
        ).movieSkipIntervals(durationMs = 7_200_000)
        val credits = intervals.first { it.type == "movie-credits" }
        val scene = intervals.first { it.type == "post-credits" }
        assertEquals("introdb", credits.provider)
        assertEquals("introdb", scene.provider)
        assertEquals(6_900.0, scene.startTime)
        assertTrue(credits.endTime <= scene.startTime)
        assertEquals(MovieCreditsGate.WatchCredits, credits.movieCreditsGate(intervals, 7_200_000))
        assertTrue(credits.showsMovieSceneNotice(intervals, 7_200_000))
        assertNull(credits.internalSkipAction(intervals, 7_200_000))
        assertNull(credits.skipSeekTargetMs(intervals, 7_200_000))
        assertFalse(credits.shouldAutoSkip(setOf(AutoSkipSegmentType.MOVIE_CREDITS), intervals, 7_200_000))
    }

    @Test
    fun aCreditsEndBeforeTheFileIsStillSkipCredits() {
        val credits = TheIntroDbMediaResponse(
            intro = listOf(TheIntroDbSegment(startMs = 0, endMs = 90_000)),
            credits = listOf(TheIntroDbSegment(startMs = 6_800_000, endMs = 7_100_000)),
        ).movieSkipIntervals(durationMs = 7_200_000)
        val roll = credits.first { it.type == "movie-credits" }
        val action = roll.internalSkipAction(credits, 7_200_000)
        assertEquals(7_100_000L, action?.targetMs)
        assertEquals(false, action?.skipsToPostCredits)
        assertFalse(roll.showsMovieSceneNotice(credits, 7_200_000))
        assertTrue(credits.any { it.type == "intro" })
    }

    @Test
    fun aRowThatStartsAfterTheTrustedLengthIsDropped() {
        val media = TheIntroDbMediaResponse(
            credits = listOf(TheIntroDbSegment(startMs = 8_000_000, endMs = 8_100_000)),
        )
        assertTrue(media.movieSkipIntervals(durationMs = 7_200_000).isEmpty())
    }

    @Test
    fun emptyEndWithUnknownPlaceholderOrDifferentCutShowsNoCreditsButton() {
        val open = TheIntroDbMediaResponse(
            credits = listOf(TheIntroDbSegment(startMs = 10_000, endMs = null)),
        )
        val mismatched = TheIntroDbMediaResponse(
            credits = listOf(TheIntroDbSegment(startMs = 7_000_000, endMs = null)),
        )
        assertNoCreditsButton(open.movieSkipIntervals(durationMs = 0L), 0L)
        assertNoCreditsButton(open.movieSkipIntervals(durationMs = 60_000L), 60_000L)
        assertNoCreditsButton(
            mismatched.movieSkipIntervals(durationMs = 10_800_000L, referenceDurationMs = 7_200_000L),
            10_800_000L,
        )
    }

    @Test
    fun emptyEndWithRealRuntimeClosesAtTheFileEnd() {
        val intervals = TheIntroDbMediaResponse(
            credits = listOf(TheIntroDbSegment(startMs = 7_000_000, endMs = null)),
        ).movieSkipIntervals(durationMs = 7_200_000)
        val credits = intervals.single()
        assertEquals(7_200.0, credits.endTime)
        assertEquals(MovieCreditsGate.SkipCredits, credits.movieCreditsGate(intervals, 7_200_000))
        assertEquals(7_200_000L, credits.skipSeekTargetMs(intervals, 7_200_000))
        assertEquals(false, credits.internalSkipAction(intervals, 7_200_000)?.skipsToPostCredits)
    }

    @Test
    fun creditsEndingAFewSecondsBeforeTheFileStaySkipCredits() {
        assertSkipCredits(endMs = 7_196_000, durationMs = 7_200_000)
        assertSkipCredits(endMs = 7_194_000, durationMs = 7_200_000)
    }

    @Test
    fun publishedEndExactlyEqualToDurationStaysSkipCredits() {
        assertSkipCredits(endMs = 7_200_000, durationMs = 7_200_000)
    }

    @Test
    fun gapOfSevenPointNineSecondsIsNotASceneAndEightSecondsIs() {
        val under = creditRuns(secondStartSec = 207.9)
        val roll = under.first { it.type == "movie-credits" }
        assertTrue(under.none { it.type == "post-credits" })
        assertEquals(MovieCreditsGate.SkipCredits, roll.movieCreditsGate(under, 400_000))
        assertEquals(200_000L, roll.skipSeekTargetMs(under, 400_000))
        assertTrue(roll.shouldAutoSkip(setOf(AutoSkipSegmentType.MOVIE_CREDITS), under, 400_000))

        val scene = creditRuns(secondStartSec = 208.0)
        assertSceneDoesNotSeekOrAutoSkip(scene)
    }

    @Test
    fun postCreditSceneShowsNoticeAndDoesNotSeekOrAutoSkip() {
        val gap = TheIntroDbMediaResponse(
            credits = listOf(
                TheIntroDbSegment(startMs = 6_000_000, endMs = 6_500_000),
                TheIntroDbSegment(startMs = 6_600_000, endMs = null),
            ),
        ).movieSkipIntervals(durationMs = 7_200_000)
        assertSceneDoesNotSeekOrAutoSkip(gap)

        val introDb = IntroDbSegmentsResponse(
            outro = IntroDbSegment(startSec = 6_000.0, endSec = 7_000.0),
            postCredits = IntroDbSegment(startSec = 6_900.0, endSec = 7_100.0),
        ).movieSkipIntervals(durationMs = 7_200_000)
        val credits = introDb.first { it.type == "movie-credits" }
        val scene = introDb.first { it.type == "post-credits" }
        assertTrue(credits.endTime <= scene.startTime)
        assertSceneDoesNotSeekOrAutoSkip(introDb)
    }

    @Test
    fun normalMovieIntroAndCreditsSeekToThePublishedEnd() {
        val durationMs = 7_200_000L
        val intervals = TheIntroDbMediaResponse(
            intro = listOf(TheIntroDbSegment(startMs = 0, endMs = 90_000)),
            credits = listOf(TheIntroDbSegment(startMs = 6_800_000, endMs = 7_100_000)),
        ).movieSkipIntervals(durationMs = durationMs)
        val intro = intervals.first { it.type == "intro" }
        val credits = intervals.first { it.type == "movie-credits" }
        assertEquals(90_000L, intro.skipSeekTargetMs(intervals, durationMs))
        assertEquals(90_000L, intro.internalSkipAction(intervals, durationMs)?.targetMs)
        assertTrue(intro.shouldAutoSkip(setOf(AutoSkipSegmentType.INTRO), intervals, durationMs))
        assertEquals(MovieCreditsGate.SkipCredits, credits.movieCreditsGate(intervals, durationMs))
        assertEquals(7_100_000L, credits.skipSeekTargetMs(intervals, durationMs))
        assertEquals(false, credits.internalSkipAction(intervals, durationMs)?.skipsToPostCredits)
        assertTrue(credits.shouldAutoSkip(setOf(AutoSkipSegmentType.MOVIE_CREDITS), intervals, durationMs))
    }

    @Test
    fun episodeOutroStillUsesItsOwnPostCreditsSeek() {
        val outro = SkipInterval(startTime = 6_000.0, endTime = 7_100.0, type = "outro", provider = "theintrodb")
        val tail = outro.internalSkipAction(listOf(outro), durationMs = 7_200_000)
        assertEquals(7_100_000L, tail?.targetMs)
        assertEquals(true, tail?.skipsToPostCredits)
        val scene = SkipInterval(startTime = 7_100.0, endTime = 7_300.0, type = "post-credits", provider = "introdb")
        val withScene = outro.internalSkipAction(listOf(outro, scene), durationMs = 7_400_000)
        assertEquals(7_100_000L, withScene?.targetMs)
        assertEquals(true, withScene?.skipsToPostCredits)
        assertEquals(7_100_000L, outro.skipSeekTargetMs(listOf(outro, scene), 7_400_000))
    }
}

private fun assertNoCreditsButton(intervals: List<SkipInterval>, durationMs: Long) {
    assertFalse(intervals.hasMovieCreditsButton(durationMs))
    assertTrue(intervals.none { it.type == "movie-credits" })
}

private fun assertSkipCredits(endMs: Long, durationMs: Long) {
    val intervals = TheIntroDbMediaResponse(
        credits = listOf(TheIntroDbSegment(startMs = 6_000_000, endMs = endMs)),
    ).movieSkipIntervals(durationMs = durationMs)
    val credits = intervals.single { it.type == "movie-credits" }
    assertEquals(endMs / 1000.0, credits.endTime)
    assertEquals(MovieCreditsGate.SkipCredits, credits.movieCreditsGate(intervals, durationMs))
    assertEquals(false, credits.internalSkipAction(intervals, durationMs)?.skipsToPostCredits)
    assertEquals(endMs, credits.skipSeekTargetMs(intervals, durationMs))
    assertTrue(credits.shouldAutoSkip(setOf(AutoSkipSegmentType.MOVIE_CREDITS), intervals, durationMs))
}

private fun creditRuns(secondStartSec: Double): List<SkipInterval> =
    movieCreditIntervals(
        rows = listOf(
            PublishedCreditRow(startSec = 100.0, endSec = 200.0),
            PublishedCreditRow(startSec = secondStartSec, endSec = 250.0),
        ),
        durationMs = 400_000L,
        provider = TheIntroDb.PROVIDER,
    )

private fun assertSceneDoesNotSeekOrAutoSkip(intervals: List<SkipInterval>) {
    val durationMs = 7_200_000L
    val credits = intervals.first { it.type == "movie-credits" }
    assertTrue(intervals.any { it.type == "post-credits" })
    assertEquals(MovieCreditsGate.WatchCredits, credits.movieCreditsGate(intervals, durationMs))
    assertNull(credits.internalSkipAction(intervals, durationMs))
    assertNull(credits.skipSeekTargetMs(intervals, durationMs))
    assertFalse(credits.shouldAutoSkip(setOf(AutoSkipSegmentType.MOVIE_CREDITS), intervals, durationMs))
}
