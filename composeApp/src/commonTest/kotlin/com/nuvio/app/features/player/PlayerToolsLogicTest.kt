package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.theIntroDbSubmitPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerToolsLogicTest {
    @Test
    fun `intro accepts 5_0s and rejects 4_9s`() {
        assertTrue(markProblems("intro", 0.0, 5.0, 3_000.0).isEmpty())
        assertEquals(
            listOf(MarkProblem.TooShort),
            markProblems("intro", 0.0, 4.9, 3_000.0),
        )
    }

    @Test
    fun `intro accepts 300_0s and rejects 300_1s`() {
        assertTrue(markProblems("intro", 0.0, 300.0, 3_000.0).isEmpty())
        assertEquals(
            listOf(MarkProblem.TooLong),
            markProblems("intro", 0.0, 300.1, 3_000.0),
        )
    }

    @Test
    fun `recap credits and preview use their own limits`() {
        assertTrue(markProblems("recap", 0.0, 1_200.0, 5_000.0).isEmpty())
        assertEquals(listOf(MarkProblem.TooLong), markProblems("recap", 0.0, 1_200.1, 5_000.0))
        assertTrue(markProblems("outro", 10.0, 1_810.0, 5_000.0).isEmpty())
        assertEquals(listOf(MarkProblem.TooLong), markProblems("credits", 10.0, 1_810.1, 5_000.0))
        assertEquals(listOf(MarkProblem.TooShort), markProblems("preview", 10.0, 14.9, 5_000.0))
        assertTrue(markProblems("preview", 10.0, 15.0, 5_000.0).isEmpty())
    }

    @Test
    fun `end must be after start and a missing clock cannot be sent`() {
        assertEquals(listOf(MarkProblem.EndNotAfterStart), markProblems("intro", 10.0, 10.0, 100.0))
        assertEquals(listOf(MarkProblem.EndNotAfterStart), markProblems("intro", 20.0, 5.0, 100.0))
        assertEquals(listOf(MarkProblem.EndNotAfterStart), markProblems("intro", null, 5.0, 100.0))
        assertEquals(listOf(MarkProblem.EndNotAfterStart), markProblems("intro", 5.0, null, null))
    }

    @Test
    fun `a time past the video is rejected and the exact duration is allowed`() {
        assertEquals(listOf(MarkProblem.PastDuration), markProblems("intro", 10.0, 101.0, 100.0))
        assertEquals(
            listOf(MarkProblem.PastDuration),
            markProblems("intro", 101.0, 110.0, 100.0),
        )
        assertTrue(markProblems("intro", 10.0, 100.0, 100.0).none { it == MarkProblem.PastDuration })
        assertTrue(markProblems("intro", 10.0, 20.0, null).isEmpty())
        assertTrue(markProblems("intro", 10.0, 20.0, 0.0).isEmpty())
    }

    @Test
    fun `a movie payload has no season or episode and credits stay credits`() {
        val body = theIntroDbSubmitPayload(
            tmdbId = 42,
            type = "movie",
            segment = "outro",
            season = 1,
            episode = 8,
            startSec = 12.4,
            endSec = 101.0,
            videoDurationMs = 7_101_000,
        )
        assertTrue(body != null)
        val json = body!!
        assertFalse(json.contains("\"season\""))
        assertFalse(json.contains("\"episode\""))
        assertTrue(json.contains("\"credits\""))
        assertTrue(json.contains("\"start_ms\""))
        assertTrue(json.contains("\"end_ms\""))
        assertEquals(null to null, movieSubmitSeasonEpisode(isMovie = true, season = 1, episode = 8))
        assertEquals(1 to 8, movieSubmitSeasonEpisode(isMovie = false, season = 1, episode = 8))
    }

    @Test
    fun `movie credits do not inherit intro times`() {
        val clocks = clocksForSegment(
            segmentType = "outro",
            draft = null,
            knownStartSec = 12.4,
            knownEndSec = 101.0,
            knownSegmentType = "intro",
            isMovie = true,
        )
        assertNull(clocks.startSec)
        assertNull(clocks.endSec)
        val seriesCredits = clocksForSegment(
            segmentType = "outro",
            draft = null,
            knownStartSec = 6_760.0,
            knownEndSec = 7_101.0,
            knownSegmentType = "credits",
            isMovie = false,
        )
        assertEquals(6_760.0, seriesCredits.startSec)
        assertEquals(7_101.0, seriesCredits.endSec)
        val drafted = clocksForSegment(
            segmentType = "outro",
            draft = MarkClocks(1.0, 2.0),
            knownStartSec = 12.4,
            knownEndSec = 101.0,
            knownSegmentType = "intro",
            isMovie = true,
        )
        assertEquals(1.0, drafted.startSec)
    }

    @Test
    fun `credits can run to the end of the video`() {
        val duration = 7_101.0
        val start = 6_760.0
        val end = runsToEndSec(duration)
        assertEquals(duration, end)
        assertEquals(341.0, markLengthSec(start, end))
        assertTrue(markProblems("outro", start, end, duration).isEmpty())
        assertTrue(markSegmentOffersRunsToEnd("credits"))
        assertTrue(!markSegmentOffersRunsToEnd("intro"))
        assertTrue(markSegmentOffersStartAtZero("recap"))
    }

    @Test
    fun `an absent intro is not saved as the playhead`() {
        val absent = IntroSubmitResult("intro", startSec = 875.0, endSec = 880.0, absentSegment = true)
        assertNull(submittedSkipMarkToSave(absent))
        val posted = IntroSubmitResult("outro", startSec = 90.0, endSec = 120.0, absentSegment = false)
        assertEquals(posted, submittedSkipMarkToSave(posted))
        assertNull(submittedSkipMarkToSave(posted.copy(endSec = 90.0)))
    }

    @Test
    fun `the zoom window is about two minutes around the playhead and the range`() {
        val window = markingZoomWindow(
            playheadSec = 80.0,
            startSec = 12.4,
            endSec = 101.0,
            durationSec = 2_530.0,
        )
        assertEquals(0.0, window.first)
        assertEquals(221.0, window.second)
        val nearEnd = markingZoomWindow(2_500.0, null, null, 2_530.0)
        assertEquals(2_380.0, nearEnd.first)
        assertEquals(2_530.0, nearEnd.second)
        assertEquals(0f, zoomFraction(0.0, 0.0, 221.0))
        assertEquals(1f, zoomFraction(221.0, 0.0, 221.0))
    }

    @Test
    fun `mark clocks keep a tenth of a second`() {
        assertEquals("0:00:12.4", formatMarkClock(12.4))
        assertEquals("0:01:41.0", formatMarkClock(101.0))
        assertEquals(12.4, parseMarkClock("0:00:12.4"))
        assertEquals(12.5, nudgeMarkSeconds(12.4, 0.1, 200.0))
        assertEquals(0.0, nudgeMarkSeconds(0.4, -1.0, 200.0))
        assertEquals(200.0, nudgeMarkSeconds(199.5, 1.0, 200.0))
        assertEquals("88.6 s", markLengthLabel(88.6))
    }

    @Test
    fun `subtitle delay reset is zero and sync by ear shifts by what was heard`() {
        assertEquals(0, subtitleDelayAfterReset())
        assertEquals(400, subtitleDelayAfterEar(currentDelayMs = 100, heardMs = 5_000, sawMs = 4_700))
        assertEquals(SUBTITLE_DELAY_MIN_MS, subtitleDelayAfterEar(0, heardMs = 0, sawMs = 120_000))
        assertEquals("+0.3 s", formatSubtitleDelayLabel(300))
        assertEquals("−0.2 s", formatSubtitleDelayLabel(-200))
    }

    @Test
    fun `closing a tool does not engage the lock`() {
        assertEquals(ToolDismissState(controlsVisible = true, locked = false), dismissToolState(locked = false))
        assertEquals(ToolDismissState(controlsVisible = false, locked = true), dismissToolState(locked = true))
    }

    @Test
    fun `dim stays at 15 percent until a bright frame is sampled`() {
        assertEquals(0.15f, playerToolDimAlpha(null))
        assertEquals(0.35f, playerToolDimAlpha(0.9f))
        assertEquals(0f, playerToolDimAlpha(0.2f))
    }

    @Test
    fun `a source row reads quality size seeders and cached`() {
        val facts = sourceRowFacts(
            name = "PLL.S01E22.1080p.WEB-DL",
            description = "1080p\n1.4 GB\n312 seeders",
            cached = true,
        )
        assertEquals("1080p", facts.quality)
        assertEquals(SourceQualityBucket.P1080, facts.bucket)
        assertEquals("1.4 GB", facts.sizeLabel)
        assertEquals(312, facts.seeders)
        assertTrue(facts.cached)
        assertEquals(SourceQualityBucket.Uhd, sourceQualityBucket("4K"))
    }

    @Test
    fun `preview stops at the end of the marked range`() {
        assertFalse(markPreviewArmed(positionMs = 200_000, startMs = 12_400, endMs = 101_000))
        assertTrue(markPreviewArmed(positionMs = 20_000, startMs = 12_400, endMs = 101_000))
        assertFalse(shouldStopMarkPreview(positionMs = 200_000, endMs = 101_000, armed = false))
        assertTrue(shouldStopMarkPreview(positionMs = 101_000, endMs = 101_000, armed = true))
    }
}
