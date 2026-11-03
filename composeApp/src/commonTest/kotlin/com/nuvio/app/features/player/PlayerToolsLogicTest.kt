package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import com.nuvio.app.features.player.skip.IntroSubmitService
import com.nuvio.app.features.player.skip.resolveIntroSubmitTimes
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
        )
        assertEquals(0.0, clocks.startSec)
        assertEquals(0.0, clocks.endSec)
        assertEquals("0:00:00", formatMarkClock(clocks.startSec!!))
        assertEquals("0:00:00", formatMarkClock(clocks.endSec!!))
        val seriesCredits = clocksForSegment(
            segmentType = "outro",
            draft = null,
            knownStartSec = 6_760.0,
            knownEndSec = 7_101.0,
            knownSegmentType = "credits",
        )
        assertEquals(6_760.0, seriesCredits.startSec)
        assertEquals(7_101.0, seriesCredits.endSec)
        val drafted = clocksForSegment(
            segmentType = "outro",
            draft = MarkClocks(1.0, 2.0),
            knownStartSec = 12.4,
            knownEndSec = 101.0,
            knownSegmentType = "intro",
        )
        assertEquals(1.0, drafted.startSec)
        assertEquals(2.0, drafted.endSec)
    }

    @Test
    fun `every segment type defaults to zero unless detected or edited`() {
        val types = (
            SubmitPanelTab.entries.map { it.segmentId } +
                listOf("credits") +
                AutoSkipSegmentType.entries.map { it.storedValue }
            ).distinct()
        assertTrue(types.containsAll(listOf("intro", "recap", "outro", "preview", "credits", "movie-credits")))
        val detectedStart = 12.0
        val detectedEnd = 40.0
        val edited = MarkClocks(3.0, 9.0)
        for (type in types) {
            val absent = shownSegmentClocks(type, null, null, edited = null, sameEpisode = false)
            assertEquals(0.0, absent.startSec, type)
            assertEquals(0.0, absent.endSec, type)
            assertEquals("0:00:00", formatMarkClock(absent.startSec!!), type)
            assertEquals("0:00:00", formatMarkClock(absent.endSec!!), type)
            assertEquals(formatMarkClock(absent.startSec), formatMarkClock(absent.endSec), type)

            val reopenedAbsent = shownSegmentClocks(type, null, null, edited = null, sameEpisode = true)
            assertEquals(0.0, reopenedAbsent.startSec, type)
            assertEquals(0.0, reopenedAbsent.endSec, type)

            val halfDetected = shownSegmentClocks(type, detectedStart, null, edited = null, sameEpisode = true)
            assertEquals(0.0, halfDetected.startSec, type)
            assertEquals(0.0, halfDetected.endSec, type)

            val found = shownSegmentClocks(type, detectedStart, detectedEnd, edited = null, sameEpisode = true)
            assertEquals(detectedStart, found.startSec, type)
            assertEquals(detectedEnd, found.endSec, type)
            assertEquals("0:00:12", formatMarkClock(found.startSec!!), type)
            assertEquals("0:00:40", formatMarkClock(found.endSec!!), type)

            val foundOnReopen = shownSegmentClocks(type, detectedStart, detectedEnd, edited = null, sameEpisode = true)
            assertEquals(detectedStart, foundOnReopen.startSec, type)
            assertEquals(detectedEnd, foundOnReopen.endSec, type)

            val kept = shownSegmentClocks(type, detectedStart, detectedEnd, edited, sameEpisode = true)
            assertEquals(3.0, kept.startSec, type)
            assertEquals(9.0, kept.endSec, type)

            val keptWithoutDetection = shownSegmentClocks(type, null, null, edited, sameEpisode = true)
            assertEquals(3.0, keptWithoutDetection.startSec, type)
            assertEquals(9.0, keptWithoutDetection.endSec, type)

            val blankEdit = shownSegmentClocks(
                type,
                detectedStart,
                detectedEnd,
                MarkClocks(null, null),
                sameEpisode = true,
            )
            assertNull(blankEdit.startSec, type)
            assertNull(blankEdit.endSec, type)

            val otherEpisode = shownSegmentClocks(type, null, null, edited, sameEpisode = false)
            assertEquals(0.0, otherEpisode.startSec, type)
            assertEquals(0.0, otherEpisode.endSec, type)

            val otherEpisodeDetected = shownSegmentClocks(
                type,
                detectedStart,
                detectedEnd,
                edited,
                sameEpisode = false,
            )
            assertEquals(detectedStart, otherEpisodeDetected.startSec, type)
            assertEquals(detectedEnd, otherEpisodeDetected.endSec, type)
        }
        val longPreview = shownSegmentClocks("preview", 6_760.0, 7_101.0, edited = null, sameEpisode = true)
        assertEquals("1:52:40", formatMarkClock(longPreview.startSec!!))
        assertEquals("1:58:21", formatMarkClock(longPreview.endSec!!))
        assertEquals(formatMarkClock(0.0).length, formatMarkClock(6_760.0).length)
    }

    @Test
    fun `credits can run to the end of the video`() {
        val duration = 7_101.0
        val start = 6_760.0
        val end = runsToEndSec(duration)
        assertEquals(duration, end)
        assertEquals(341.0, markLengthSec(start, end))
        assertTrue(markProblems("outro", start, end, duration).isEmpty())
        val status = markRangeStatus("outro", start, end, duration)
        assertTrue(status.bothSet)
        assertNull(status.problem)
        assertEquals("341 s", status.lengthLabel)
    }

    @Test
    fun `start and end shortcuts and the range actions`() {
        val duration = 7_101.0
        assertTrue(markSegmentOffersRunsToEnd("credits"))
        assertTrue(markSegmentOffersRunsToEnd("preview"))
        assertTrue(!markSegmentOffersRunsToEnd("intro"))
        assertTrue(!markSegmentOffersRunsToEnd("recap"))
        assertTrue(markSegmentOffersStartAtZero("intro"))
        assertTrue(markSegmentOffersStartAtZero("recap"))
        assertTrue(!markSegmentOffersStartAtZero("credits"))
        assertTrue(markRunsToEndSelected(duration, duration))
        assertTrue(!markRunsToEndSelected(duration - 1.0, duration))
        assertTrue(!markRunsToEndSelected(null, duration))
        assertTrue(markPlayRangeEnabled(absent = false, timeOk = true, submitting = false))
        assertTrue(!markPlayRangeEnabled(absent = true, timeOk = true, submitting = false))
        assertTrue(!markPlayRangeEnabled(absent = false, timeOk = false, submitting = false))
        assertTrue(!markPlayRangeEnabled(absent = false, timeOk = true, submitting = true))
        assertTrue(markCopyLinkEnabled(absent = false, timeOk = true))
        assertTrue(!markCopyLinkEnabled(absent = false, timeOk = false))
        assertTrue(!markCopyLinkEnabled(absent = true, timeOk = true))
    }

    @Test
    fun `an end on the stream length is valid and a hair past it clamps`() {
        val duration = 44 * 60 + 8.9
        val start = duration - 36.7
        assertTrue(markProblems("outro", start, duration, duration).isEmpty())
        assertTrue(markProblems("outro", start, duration + 0.0004, duration).isEmpty())
        assertEquals(
            listOf(MarkProblem.PastDuration),
            markProblems("outro", start, duration + 5.0, duration).filter { it == MarkProblem.PastDuration },
        )
        val end = runsToEndSec(duration)
        assertEquals(duration, end)
        assertTrue(markProblems("credits", start, end, duration).isEmpty())
        assertTrue(
            markSubmitEnabled(
                sent = false,
                submitting = false,
                keyProblem = false,
                blocked = false,
                absent = false,
                absentAllowed = true,
                timeOk = markProblems("outro", start, end, duration).isEmpty(),
            ),
        )
        assertEquals(duration, clampMarkTimeToDuration(duration + 0.0004, duration))
        assertEquals(duration + 5.0, clampMarkTimeToDuration(duration + 5.0, duration))
        val stored = formatMarkTimestamp(end)
        assertEquals(end, parseMarkClock(stored)!!, 0.0005)
        assertEquals("0:44:08", formatMarkClock(duration))
        assertEquals("0:44:08.9", formatMarkClock(duration, showTenth = true))
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
        assertEquals("0:00:12", formatMarkClock(12.4))
        assertEquals("0:00:12.4", formatMarkClock(12.4, showTenth = true))
        assertEquals("0:01:41", formatMarkClock(101.0))
        assertEquals("0:01:41", formatMarkClock(101.0, showTenth = true))
        assertFalse(markClockShowsTenth(12.4, allowTenth = false))
        assertTrue(markClockShowsTenth(12.4, allowTenth = true))
        assertFalse(markClockShowsTenth(101.0, allowTenth = true))
        assertEquals(12.4, parseMarkClock("0:00:12.400"))
        assertEquals(12.4, parseMarkClock(formatMarkTimestamp(12.4)))
        assertEquals(12.5, nudgeMarkSeconds(12.4, 0.1, 200.0))
        assertEquals(0.0, nudgeMarkSeconds(0.4, -1.0, 200.0))
        assertEquals(200.0, nudgeMarkSeconds(199.5, 1.0, 200.0))
        assertEquals(13.4, nudgeMarkSeconds(12.4, 1.0, 200.0))
        assertEquals("88.6 s", markLengthLabel(88.6))
        assertEquals("22 s", markStatusLengthLabel(22.0))
        assertEquals("22.1 s", markStatusLengthLabel(22.14))
        assertEquals("4.9 s", markStatusLengthLabel(4.9))
        val valid = markRangeStatus("intro", 209.0, 231.0, 3_000.0)
        assertTrue(valid.bothSet)
        assertNull(valid.problem)
        assertEquals("22 s", valid.lengthLabel)
        assertEquals(5, valid.minSec)
        assertEquals(300, valid.maxSec)
        assertEquals(MarkProblem.TooShort, markRangeStatus("intro", 0.0, 4.9, 3_000.0).problem)
        assertFalse(markRangeStatus("intro", null, 12.0, 3_000.0).bothSet)
        assertNull(markRangeStatus("intro", null, 12.0, 3_000.0).problem)
    }

    @Test
    fun `a held nudge steps by a tenth and a tap keeps the labelled step`() {
        assertEquals(-0.1, holdNudgeDelta(-5.0))
        assertEquals(-0.1, holdNudgeDelta(-1.0))
        assertEquals(0.1, holdNudgeDelta(1.0))
        assertEquals(0.1, holdNudgeDelta(5.0))
        assertEquals(400L, NudgeHoldDelayMs)
        assertTrue(NudgeHoldRepeatMs in 50L..200L)
        val tapped = nudgeMarkSeconds(209.0, -5.0, 1_000.0)
        assertEquals(204.0, tapped)
        assertFalse(nudgeKeepsTenth(-5.0, alreadyShowing = false))
        assertTrue(nudgeKeepsTenth(-5.0, alreadyShowing = true))
        val held = nudgeMarkSeconds(209.0, holdNudgeDelta(-5.0), 1_000.0)
        assertEquals(208.9, held)
        assertTrue(nudgeKeepsTenth(holdNudgeDelta(-5.0), alreadyShowing = false))
        assertEquals("0:03:24", formatMarkClock(tapped, showTenth = nudgeKeepsTenth(-5.0, false)))
        assertEquals("0:03:28.9", formatMarkClock(held, showTenth = true))
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

    @Test
    fun `a live or unknown duration does not open a seek window`() {
        assertEquals(0.0 to 0.0, markingZoomWindow(10.0, 1.0, 2.0, 0.0))
        assertEquals(0.0 to 0.0, markingZoomWindow(10.0, null, null, -1.0))
        assertFalse(markingSeekAllowed(0.0))
        assertFalse(markingSeekAllowed(-5.0))
        assertTrue(markingSeekAllowed(12.0))
    }

    @Test
    fun `mark start keeps the requested seek until the clock catches up`() {
        val pending = markingPlayheadAfterSample(clockMs = 10_000, pendingSeekMs = 80_000, loadingScrubMs = null)
        assertEquals(80_000L, pending.positionMs)
        assertEquals(80_000L, pending.pendingSeekMs)
        val caughtUp = markingPlayheadAfterSample(clockMs = 80_200, pendingSeekMs = 80_000, loadingScrubMs = null)
        assertEquals(80_200L, caughtUp.positionMs)
        assertNull(caughtUp.pendingSeekMs)
        val loading = markingPlayheadAfterSample(clockMs = 10_000, pendingSeekMs = 80_000, loadingScrubMs = 80_000)
        assertEquals(80_000L, loading.positionMs)
        assertEquals(80_000L, loading.pendingSeekMs)
    }

    @Test
    fun `preview restores the position and play state it captured`() {
        assertEquals(
            MarkPreviewPlayback(positionMs = 42_000, shouldPlay = false),
            restoredMarkPreview(markPreviewSession(positionMs = 42_000, shouldPlay = false)),
        )
        assertEquals(
            MarkPreviewPlayback(positionMs = 0, shouldPlay = true),
            restoredMarkPreview(markPreviewSession(positionMs = -20, shouldPlay = true)),
        )
    }

    @Test
    fun `the no-segment button follows the selected chip and movies stay intro and credits`() {
        assertEquals(NoneChip.Intro, noneChip("intro"))
        assertEquals(NoneChip.Intro, noneChip("opening"))
        assertEquals(NoneChip.Recap, noneChip("recap"))
        assertEquals(NoneChip.Credits, noneChip("outro"))
        assertEquals(NoneChip.Credits, noneChip("credits"))
        assertEquals(NoneChip.Credits, noneChip("movie-credits"))
        assertEquals(NoneChip.Preview, noneChip("preview"))
        assertEquals(listOf("intro", "outro"), markSegmentTypes(isMovie = true))
        assertEquals(listOf("intro", "recap", "outro", "preview"), markSegmentTypes(isMovie = false))
    }

    @Test
    fun `none chip arms absent mode and the open tab or a clock edit restores the typed range`() {
        val typedStart = "00:00:30"
        val typedEnd = "00:01:30"
        assertTrue(absentAfterNoneChip())
        assertEquals(SegmentTap.Stay, segmentTap("intro", "intro", absent = false))
        assertEquals(SegmentTap.ReturnToEditing, segmentTap("intro", "opening", absent = true))
        assertEquals(SegmentTap.ReturnToEditing, segmentTap("credits", "outro", absent = true))
        assertEquals(SegmentTap.Switch, segmentTap("intro", "recap", absent = true))
        assertEquals(SegmentTap.Switch, segmentTap("outro", "preview", absent = false))
        assertFalse(absentAfterClockEdit())
        val absentTimes = resolveIntroSubmitTimes(
            IntroSubmitService.THE_INTRODB,
            absentSegment = true,
            startTimeStr = typedStart,
            endTimeStr = typedEnd,
        )
        assertEquals(0.0 to 0.0, absentTimes)
        assertNull(
            resolveIntroSubmitTimes(
                IntroSubmitService.INTRODB,
                absentSegment = true,
                startTimeStr = typedStart,
                endTimeStr = typedEnd,
            ),
        )
        val afterReturn = segmentTap("intro", "intro", absent = true) != SegmentTap.ReturnToEditing
        val afterClock = absentAfterClockEdit()
        assertEquals(
            30.0 to 90.0,
            resolveIntroSubmitTimes(
                IntroSubmitService.THE_INTRODB,
                absentSegment = afterReturn,
                startTimeStr = typedStart,
                endTimeStr = typedEnd,
            ),
        )
        assertEquals(
            30.0 to 90.0,
            resolveIntroSubmitTimes(
                IntroSubmitService.THE_INTRODB,
                absentSegment = afterClock,
                startTimeStr = typedStart,
                endTimeStr = typedEnd,
            ),
        )
        assertTrue(
            markSubmitEnabled(
                sent = false,
                submitting = false,
                keyProblem = false,
                blocked = false,
                absent = true,
                absentAllowed = true,
                timeOk = true,
            ),
        )
        assertTrue(
            markSubmitEnabled(
                sent = false,
                submitting = false,
                keyProblem = false,
                blocked = false,
                absent = true,
                absentAllowed = true,
                timeOk = false,
            ),
        )
        assertFalse(
            markSubmitEnabled(
                sent = false,
                submitting = false,
                keyProblem = false,
                blocked = false,
                absent = true,
                absentAllowed = false,
                timeOk = true,
            ),
        )
        assertFalse(
            markSubmitEnabled(
                sent = false,
                submitting = false,
                keyProblem = false,
                blocked = false,
                absent = afterClock,
                absentAllowed = true,
                timeOk = false,
            ),
        )
        assertTrue(
            markSubmitEnabled(
                sent = false,
                submitting = false,
                keyProblem = false,
                blocked = false,
                absent = afterReturn,
                absentAllowed = false,
                timeOk = true,
            ),
        )
    }

    @Test
    fun `none goes through resolveIntroSubmitTimes and introdb refuses it`() {
        assertEquals(
            0.0 to 0.0,
            resolveIntroSubmitTimes(
                IntroSubmitService.THE_INTRODB,
                absentSegment = true,
                startTimeStr = "",
                endTimeStr = "",
            ),
        )
        assertNull(
            resolveIntroSubmitTimes(
                IntroSubmitService.INTRODB,
                absentSegment = true,
                startTimeStr = "00:00:00",
                endTimeStr = "00:00:00",
            ),
        )
    }

    @Test
    fun `a dismissed or replaced submit does not count`() {
        val sent = markSubmitSnapshot("credits", absent = false, startSec = 10.0, endSec = 40.0, generation = 3)
        assertEquals("outro", sent.segmentType)
        assertFalse(sent.absent)
        assertEquals(10.0, sent.startSec)
        assertTrue(markSubmitResultApplies(sent.generation, currentGeneration = 3, stripOpen = true))
        assertFalse(markSubmitResultApplies(sent.generation, currentGeneration = 4, stripOpen = true))
        assertFalse(markSubmitResultApplies(sent.generation, currentGeneration = 3, stripOpen = false))
        val absent = markSubmitSnapshot("intro", absent = true, startSec = 5.0, endSec = 9.0, generation = 1)
        assertTrue(absent.absent)
        assertEquals("intro", absent.segmentType)
        assertEquals(5.0, absent.startSec)
    }

    @Test
    fun `the sync strip lifts the subtitle offset without replacing the saved one`() {
        assertEquals(122, subtitleBottomOffsetForSyncStrip(20))
        assertEquals(102, subtitleBottomOffsetForSyncStrip(0))
    }

    @Test
    fun `a valid range can be submitted without playing it`() {
        assertTrue(
            markSubmitEnabled(
                sent = false,
                submitting = false,
                keyProblem = false,
                blocked = false,
                absent = false,
                absentAllowed = false,
                timeOk = true,
            ),
        )
        assertFalse(
            markSubmitEnabled(
                sent = false,
                submitting = false,
                keyProblem = false,
                blocked = false,
                absent = false,
                absentAllowed = true,
                timeOk = false,
            ),
        )
        assertTrue(
            markSubmitEnabled(
                sent = false,
                submitting = false,
                keyProblem = false,
                blocked = false,
                absent = true,
                absentAllowed = true,
                timeOk = false,
            ),
        )
        assertFalse(
            markSubmitEnabled(
                sent = true,
                submitting = false,
                keyProblem = false,
                blocked = false,
                absent = false,
                absentAllowed = false,
                timeOk = true,
            ),
        )
    }

    @Test
    fun `an end equal to the stream length is valid and a real overshoot is not`() {
        val duration = 100.0
        val start = 80.0
        assertTrue(markProblems("outro", start, duration, duration).isEmpty())
        assertEquals(duration, clampMarkTimeToDuration(duration + 0.001, duration))
        assertEquals(duration, clampMarkTimeToDuration(duration + (MarkDurationSlopSec / 2), duration))
        assertEquals(duration, clampMarkTimeToDuration(duration + MarkDurationSlopSec, duration))
        val short = 12.0
        assertEquals(short, clampMarkTimeToDuration(short + 0.001, short))
        assertEquals(short, clampMarkTimeToDuration(short + MarkDurationSlopSec, short))
        assertTrue(markProblems("intro", 0.0, short + 0.001, short).isEmpty())
        assertTrue(markProblems("intro", 0.0, short + MarkDurationSlopSec, short).isEmpty())
        assertTrue(markProblems("intro", start, duration + 0.001, duration).isEmpty())
        assertTrue(markProblems("intro", start, duration + MarkDurationSlopSec, duration).isEmpty())
        val overshoot = duration + 5.0
        assertEquals(overshoot, clampMarkTimeToDuration(overshoot, duration))
        assertEquals(
            listOf(MarkProblem.PastDuration),
            markProblems("outro", start, overshoot, duration).filter { it == MarkProblem.PastDuration },
        )
        for (segment in listOf("intro", "recap", "outro", "preview", "credits")) {
            val end = runsToEndSec(duration)
            assertEquals(duration, end)
            val problems = markProblems(segment, start, end, duration)
            assertTrue(problems.none { it == MarkProblem.PastDuration })
            assertTrue(problems.isEmpty())
            assertTrue(
                markSubmitEnabled(
                    sent = false,
                    submitting = false,
                    keyProblem = false,
                    blocked = false,
                    absent = false,
                    absentAllowed = false,
                    timeOk = problems.isEmpty(),
                ),
            )
        }
        val tooLong = markProblems("intro", 0.0, runsToEndSec(2_000.0), 2_000.0)
        assertTrue(tooLong.none { it == MarkProblem.PastDuration })
        assertFalse(tooLong.isEmpty())
    }

    @Test
    fun `clocks stay on whole seconds until a tenth nudge`() {
        val duration = 1_000.0
        val tapped = nudgeMarkSeconds(209.0, -5.0, duration)
        assertEquals(204.0, tapped)
        assertFalse(nudgeKeepsTenth(-5.0, alreadyShowing = false))
        assertEquals("0:03:24", formatMarkClock(tapped, showTenth = nudgeKeepsTenth(-5.0, false)))
        val held = nudgeMarkSeconds(209.0, holdNudgeDelta(-5.0), duration)
        assertEquals(208.9, held)
        assertTrue(nudgeKeepsTenth(holdNudgeDelta(-5.0), alreadyShowing = false))
        assertEquals("0:03:28.9", formatMarkClock(held, showTenth = nudgeKeepsTenth(-0.1, false)))
        assertEquals("0:03:28", formatMarkClock(held, showTenth = false))
        for (delta in listOf(-5.0, -1.0, 1.0, 5.0)) {
            assertFalse(nudgeKeepsTenth(delta, alreadyShowing = false))
            val tenth = holdNudgeDelta(delta)
            assertEquals(if (delta < 0.0) -0.1 else 0.1, tenth)
            assertTrue(nudgeKeepsTenth(tenth, alreadyShowing = false))
        }
    }

    @Test
    fun `none sends zero times plus the stream length`() {
        val durationMs = 2_649_000L
        val times = checkNotNull(
            resolveIntroSubmitTimes(
                IntroSubmitService.THE_INTRODB,
                absentSegment = true,
                startTimeStr = "0:03:29",
                endTimeStr = "0:03:51",
            ),
        )
        assertEquals(0.0 to 0.0, times)
        val body = checkNotNull(
            theIntroDbSubmitPayload(
                tmdbId = 1396,
                type = "tv",
                segment = "intro",
                season = 1,
                episode = 22,
                startSec = times.first,
                endSec = times.second,
                videoDurationMs = durationMs,
            ),
        )
        assertTrue(body.contains("\"start_ms\":0"))
        assertTrue(body.contains("\"end_ms\":0"))
        assertTrue(body.contains("\"video_duration_ms\":$durationMs"))
        assertNull(
            resolveIntroSubmitTimes(
                IntroSubmitService.INTRODB,
                absentSegment = true,
                startTimeStr = "0:00:00",
                endTimeStr = "0:00:00",
            ),
        )
    }

    @Test
    fun `every submit sheet state can return to editing and only a valid one submits`() {
        val series = sheetCases(isMovie = false, absentAllowed = true)
        val movies = sheetCases(isMovie = true, absentAllowed = true)
        val introDb = sheetCases(isMovie = false, absentAllowed = false)
        assertEquals(4 * 2 * 2 * 2 * 2 * 2 - 4 * 2 * 2 * 2 - 4 * 2 * 2 - 4 * 2, series.size)
        assertEquals(2 * 2 * 2 * 2 * 2 * 2 - 2 * 2 * 2 * 2 - 2 * 2 * 2 - 2 * 2, movies.size)
        assertEquals(4 * 2 * 2 * 2 * 2 * 2 - 4 * 2 * 2 * 2 - 4 * 2 * 2 - 4 * 2 - 4 * 2 * 2, introDb.size)
        for (state in series + movies + introDb) {
            assertSheetInvariants(state)
            val editing = backToEditing(state)
            assertFalse(editing.closed)
            assertFalse(editing.absent)
            assertFalse(editing.submitting)
            assertFalse(editing.popoverOpen)
            assertTrue(editing.showsSegmentMarks())
            assertFalse(editing.showsNudges())
            val opened = reduceSubmitSheet(editing, SubmitSheetEvent.TapStart)
            assertTrue(opened.popoverOpen)
            assertTrue(opened.showsNudges())
            assertFalse(reduceSubmitSheet(opened, SubmitSheetEvent.DismissPopover).popoverOpen)
            assertFalse(reduceSubmitSheet(opened, SubmitSheetEvent.TapStart).popoverOpen)
            if (state.submitting) {
                assertFalse(reduceSubmitSheet(state, SubmitSheetEvent.Close).closed)
            }
            val closed = reduceSubmitSheet(editing, SubmitSheetEvent.Close)
            assertTrue(closed.closed)
            assertFalse(closed.showsSegmentMarks())
            assertFalse(closed.showsNudges())
            assertFalse(closed.canSubmit())
            for (event in sheetEvents()) {
                val next = reduceSubmitSheet(state, event)
                if (next.closed) {
                    assertFalse(next.canSubmit())
                    assertFalse(next.showsSegmentMarks())
                    assertFalse(next.showsNudges())
                } else {
                    assertTrue(submitSheetShapeAllowed(next.shape(), next.absentAllowed))
                    assertSheetInvariants(next)
                }
            }
        }
        val blocked = openSubmitSheet().copy(startSec = 10.0, endSec = 12.0)
        assertFalse(blocked.rangeValid())
        assertFalse(reduceSubmitSheet(blocked, SubmitSheetEvent.Submit).submitting)
        val refused = reduceSubmitSheet(
            openSubmitSheet(absentAllowed = false),
            SubmitSheetEvent.TapNoneChip,
        )
        assertTrue(refused.absent)
        assertFalse(refused.canSubmit())
        assertFalse(reduceSubmitSheet(refused, SubmitSheetEvent.Submit).submitting)
        val sent = reduceSubmitSheet(
            reduceSubmitSheet(openSubmitSheet(), SubmitSheetEvent.Submit),
            SubmitSheetEvent.SubmitSuccess,
        )
        assertFalse(sent.submitting)
        assertFalse(sent.canSubmit())
        val openedSent = reduceSubmitSheet(sent, SubmitSheetEvent.TapStart)
        val edited = reduceSubmitSheet(openedSent, SubmitSheetEvent.Nudge(deltaSec = 1.0, holding = false))
        assertTrue(edited.rangeValid())
        assertTrue(edited.canSubmit())
        val endOpen = reduceSubmitSheet(openSubmitSheet(), SubmitSheetEvent.TapEnd)
        val heldCoarse = reduceSubmitSheet(endOpen, SubmitSheetEvent.Nudge(deltaSec = -5.0, holding = true))
        val heldFine = reduceSubmitSheet(endOpen, SubmitSheetEvent.Nudge(deltaSec = -1.0, holding = true))
        assertEquals(nudgeMarkSeconds(40.0, -5.0, 100.0), heldCoarse.endSec)
        assertEquals(nudgeMarkSeconds(40.0, holdNudgeDelta(-1.0), 100.0), heldFine.endSec)
        val ignored = reduceSubmitSheet(openSubmitSheet(), SubmitSheetEvent.Nudge(deltaSec = -1.0, holding = true))
        assertEquals(40.0, ignored.endSec)
        assertFalse(ignored.popoverOpen)
    }

    @Test
    fun `submit bar stays inside the safe area and under an eighth of the video`() {
        val notchLeft = submitBarBounds(
            screenWidth = 844f,
            screenHeight = 390f,
            insets = SubmitBarInsets(left = 47f, top = 0f, right = 0f, bottom = 21f),
        )
        assertEquals(47f, notchLeft.left)
        assertEquals(797f, notchLeft.width)
        assertEquals(48.75f, notchLeft.height)
        assertEquals(320.25f, notchLeft.top)
        assertTrue(notchLeft.top >= 0f)
        assertTrue(notchLeft.top + notchLeft.height <= 390f - 21f)
        assertTrue(notchLeft.height <= 390f * SubmitBarMaxVideoFraction)
        assertTrue(notchLeft.height <= SubmitBarPreferredHeight)

        val notchTop = submitBarBounds(
            screenWidth = 390f,
            screenHeight = 844f,
            insets = SubmitBarInsets(left = 0f, top = 59f, right = 0f, bottom = 34f),
        )
        assertEquals(0f, notchTop.left)
        assertEquals(390f, notchTop.width)
        assertEquals(64f, notchTop.height)
        assertEquals(746f, notchTop.top)
        assertTrue(notchTop.top >= 59f)
        assertTrue(notchTop.top + notchTop.height <= 844f - 34f)
        assertTrue(notchTop.height <= 844f * SubmitBarMaxVideoFraction)

        val homeIndicator = submitBarBounds(
            screenWidth = 390f,
            screenHeight = 844f,
            insets = SubmitBarInsets(left = 0f, top = 0f, right = 0f, bottom = 34f),
        )
        assertEquals(64f, homeIndicator.height)
        assertEquals(746f, homeIndicator.top)
        assertEquals(810f, homeIndicator.top + homeIndicator.height)

        val shortWindow = submitBarBounds(
            screenWidth = 800f,
            screenHeight = 200f,
            insets = SubmitBarInsets(left = 0f, top = 59f, right = 16f, bottom = 34f),
        )
        assertEquals(0f, shortWindow.left)
        assertEquals(784f, shortWindow.width)
        assertEquals(25f, shortWindow.height)
        assertEquals(141f, shortWindow.top)
        assertTrue(shortWindow.top >= 59f)
        assertTrue(shortWindow.left + shortWindow.width <= 800f - 16f)
        assertTrue(shortWindow.top + shortWindow.height <= 200f - 34f)
        assertTrue(shortWindow.height <= 200f * SubmitBarMaxVideoFraction)
        assertTrue(shortWindow.height < SubmitBarPreferredHeight)
    }

}

private fun sheetCases(isMovie: Boolean, absentAllowed: Boolean): List<SubmitSheet> {
    val tabs = if (isMovie) {
        listOf(SubmitPanelTab.Intro, SubmitPanelTab.Credits)
    } else {
        SubmitPanelTab.entries
    }
    val reached = mutableListOf<SubmitSheet>()
    for (tab in tabs) {
        for (absent in listOf(false, true)) {
            for (handle in MarkHandle.entries) {
                for (popover in listOf(false, true)) {
                    for (valid in listOf(false, true)) {
                        for (submitting in listOf(false, true)) {
                            val shape = SubmitSheetShape(tab, absent, handle, popover, valid, submitting)
                            if (!submitSheetShapeAllowed(shape, absentAllowed)) continue
                            val state = reachSheet(isMovie, absentAllowed, shape)
                            assertEquals(shape, state.shape())
                            reached += state
                        }
                    }
                }
            }
        }
    }
    return reached
}

private fun reachSheet(isMovie: Boolean, absentAllowed: Boolean, shape: SubmitSheetShape): SubmitSheet {
    var state = openSubmitSheet(isMovie = isMovie, absentAllowed = absentAllowed)
    if (shape.tab != SubmitPanelTab.Intro) {
        state = reduceSubmitSheet(state, SubmitSheetEvent.SelectTab(shape.tab))
    }
    state = reduceSubmitSheet(state, SubmitSheetEvent.DragHandle(MarkHandle.Start, 10.0))
    state = reduceSubmitSheet(
        state,
        SubmitSheetEvent.DragHandle(MarkHandle.End, if (shape.valid) 40.0 else 12.0),
    )
    state = reduceSubmitSheet(
        state,
        if (shape.handle == MarkHandle.End) SubmitSheetEvent.TapEnd else SubmitSheetEvent.TapStart,
    )
    if (!shape.popover || shape.absent) {
        state = reduceSubmitSheet(state, SubmitSheetEvent.DismissPopover)
    }
    if (shape.absent) state = reduceSubmitSheet(state, SubmitSheetEvent.TapNoneChip)
    if (shape.submitting) state = reduceSubmitSheet(state, SubmitSheetEvent.Submit)
    return state
}

private fun backToEditing(state: SubmitSheet): SubmitSheet {
    var next = state
    if (next.submitting) next = reduceSubmitSheet(next, SubmitSheetEvent.SubmitFailure)
    if (next.absent) next = reduceSubmitSheet(next, SubmitSheetEvent.TapSameTab)
    if (next.popoverOpen) next = reduceSubmitSheet(next, SubmitSheetEvent.DismissPopover)
    return next
}

private fun assertSheetInvariants(state: SubmitSheet) {
    val acceptable = if (state.absent) state.absentAllowed else state.rangeValid()
    assertEquals(
        !state.closed && !state.submitting && !state.sent && !state.keyProblem && !state.blocked && acceptable,
        state.canSubmit(),
    )
    if (state.canSubmit()) assertTrue(acceptable)
    assertEquals(!state.closed && !state.absent, state.showsSegmentMarks())
    assertEquals(!state.closed && !state.absent && state.popoverOpen, state.showsNudges())
    if (!state.popoverOpen) {
        val nudged = reduceSubmitSheet(state, SubmitSheetEvent.Nudge(deltaSec = -1.0, holding = true))
        assertEquals(state.startSec, nudged.startSec)
        assertEquals(state.endSec, nudged.endSec)
    }
    if (state.popoverOpen) {
        assertFalse(reduceSubmitSheet(state, SubmitSheetEvent.DismissPopover).popoverOpen)
        val sameChip = if (state.handle == MarkHandle.End) SubmitSheetEvent.TapEnd else SubmitSheetEvent.TapStart
        assertFalse(reduceSubmitSheet(state, sameChip).popoverOpen)
    }
    if (state.absent && !state.submitting) {
        val editing = reduceSubmitSheet(state, SubmitSheetEvent.TapSameTab)
        assertFalse(editing.absent)
        assertFalse(editing.popoverOpen)
    }
}

private fun sheetEvents(): List<SubmitSheetEvent> = buildList {
    SubmitPanelTab.entries.forEach { add(SubmitSheetEvent.SelectTab(it)) }
    add(SubmitSheetEvent.TapSameTab)
    add(SubmitSheetEvent.TapNoneChip)
    add(SubmitSheetEvent.TapStart)
    add(SubmitSheetEvent.TapEnd)
    add(SubmitSheetEvent.DismissPopover)
    add(SubmitSheetEvent.SetToNow)
    for (delta in listOf(-5.0, -1.0, 1.0, 5.0)) {
        add(SubmitSheetEvent.Nudge(delta, holding = false))
        add(SubmitSheetEvent.Nudge(delta, holding = true))
    }
    for (seconds in listOf(0.0, 10.0, 12.0, 20.0, 40.0, 100.0, 100.001, 105.0)) {
        add(SubmitSheetEvent.DragHandle(MarkHandle.Start, seconds))
        add(SubmitSheetEvent.DragHandle(MarkHandle.End, seconds))
    }
    add(SubmitSheetEvent.ResetMarks)
    add(SubmitSheetEvent.Submit)
    add(SubmitSheetEvent.SubmitSuccess)
    add(SubmitSheetEvent.SubmitFailure)
    add(SubmitSheetEvent.Close)
}
