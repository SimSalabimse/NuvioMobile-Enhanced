package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubmitTimestampChecksTest {
    @Test
    fun `end must be after start and a missing time cannot be sent`() {
        assertEquals(
            listOf(SubmitTimestampProblem.EndNotAfterStart),
            submitTimestampProblems(10.0, 10.0, durationSec = 100.0),
        )
        assertEquals(
            listOf(SubmitTimestampProblem.EndNotAfterStart),
            submitTimestampProblems(20.0, 5.0, durationSec = 100.0),
        )
        assertEquals(
            listOf(SubmitTimestampProblem.EndNotAfterStart),
            submitTimestampProblems(null, 5.0, durationSec = 100.0),
        )
        assertEquals(
            listOf(SubmitTimestampProblem.EndNotAfterStart),
            submitTimestampProblems(5.0, null, durationSec = null),
        )
        assertTrue(submitTimestampProblems(0.0, 1.0, durationSec = 100.0).isEmpty())
    }

    @Test
    fun `a time past the video is rejected and the exact duration is allowed`() {
        assertEquals(
            listOf(SubmitTimestampProblem.EndPastDuration),
            submitTimestampProblems(10.0, 101.0, durationSec = 100.0),
        )
        assertEquals(
            listOf(SubmitTimestampProblem.StartPastDuration, SubmitTimestampProblem.EndPastDuration),
            submitTimestampProblems(101.0, 110.0, durationSec = 100.0),
        )
        assertEquals(
            listOf(
                SubmitTimestampProblem.EndNotAfterStart,
                SubmitTimestampProblem.StartPastDuration,
                SubmitTimestampProblem.EndPastDuration,
            ),
            submitTimestampProblems(120.0, 110.0, durationSec = 100.0),
        )
        assertTrue(submitTimestampProblems(10.0, 100.0, durationSec = 100.0).isEmpty())
        assertTrue(submitTimestampProblems(10.0, 500.0, durationSec = null).isEmpty())
        assertTrue(submitTimestampProblems(10.0, 500.0, durationSec = 0.0).isEmpty())
    }

    @Test
    fun `intro and recap warn outside 3 seconds and 5 minutes without blocking`() {
        assertEquals(
            SubmitTimestampWarning.LongerThanUsual,
            submitTimestampWarning("intro", 0.0, SubmitTimestampSoftMaxSeconds + 1.0),
        )
        assertNull(submitTimestampWarning("intro", 0.0, SubmitTimestampSoftMaxSeconds))
        assertEquals(
            SubmitTimestampWarning.ShorterThanUsual,
            submitTimestampWarning("recap", 0.0, SubmitTimestampSoftMinSeconds - 0.1),
        )
        assertNull(submitTimestampWarning("Recap", 0.0, SubmitTimestampSoftMinSeconds))
        assertNull(submitTimestampWarning("outro", 0.0, 1_000.0))
        assertNull(submitTimestampWarning("intro", 10.0, 10.0))
    }

    @Test
    fun `nudges step by the given seconds and clamp at zero and the duration`() {
        assertEquals("00:00:10", nudgeSkipTimestamp("00:00:05", 5, durationSec = 120.0))
        assertEquals("00:00:04", nudgeSkipTimestamp("00:00:05", -1, durationSec = 120.0))
        assertEquals("00:00:00", nudgeSkipTimestamp("00:00:02", -5, durationSec = 120.0))
        assertEquals("00:00:30", nudgeSkipTimestamp("00:00:28", 5, durationSec = 30.0))
        assertEquals("00:00:30", nudgeSkipTimestamp("00:00:30", 5, durationSec = 30.0))
        assertEquals("00:01:05", nudgeSkipTimestamp("00:01:00", 5, durationSec = null))
        assertEquals("99:59:59", nudgeSkipTimestamp("99:59:58", 5, durationSec = null))
    }

    @Test
    fun `a fresh credits or outro form is not submittable`() {
        val fresh = initialSubmitClocks(draft = null, knownStartSec = null, knownEndSec = null)
        assertEquals(EMPTY_SKIP_TIMESTAMP to EMPTY_SKIP_TIMESTAMP, fresh)
        assertTrue(
            submitTimestampProblems(
                parseTimeToSeconds(fresh.first),
                parseTimeToSeconds(fresh.second),
                durationSec = 7_200.0,
            ).isNotEmpty(),
        )
        val cleared = initialSubmitClocks(
            draft = EMPTY_SKIP_TIMESTAMP to EMPTY_SKIP_TIMESTAMP,
            knownStartSec = null,
            knownEndSec = null,
        )
        assertEquals(EMPTY_SKIP_TIMESTAMP to EMPTY_SKIP_TIMESTAMP, cleared)
        assertEquals(
            "00:01:00" to "00:02:00",
            initialSubmitClocks(draft = null, knownStartSec = 60.0, knownEndSec = 120.0),
        )
        assertEquals(
            "00:00:10" to "00:00:20",
            initialSubmitClocks(
                draft = "00:00:10" to "00:00:20",
                knownStartSec = 60.0,
                knownEndSec = 120.0,
            ),
        )
    }

    @Test
    fun `an absent intro does not become a saved skip mark`() {
        val absent = IntroSubmitResult(
            segmentType = "intro",
            startSec = 0.0,
            endSec = 0.0,
            absentSegment = true,
        )
        assertNull(submittedSkipMarkToSave(absent))
        assertNull(
            submittedSkipMarkToSave(
                absent.copy(startSec = 12.0, endSec = 40.0),
            ),
        )
        val posted = IntroSubmitResult(
            segmentType = "outro",
            startSec = 90.0,
            endSec = 120.0,
            absentSegment = false,
        )
        assertEquals(posted, submittedSkipMarkToSave(posted))
        assertNull(submittedSkipMarkToSave(posted.copy(endSec = 90.0)))
    }

    @Test
    fun `the range bar spans the chosen fraction of the video`() {
        assertEquals(0.1f to 0.25f, submitRangeSpan(10.0, 25.0, 100.0))
        assertNull(submitRangeSpan(25.0, 10.0, 100.0))
        assertNull(submitRangeSpan(10.0, 25.0, null))
        assertNull(submitRangeSpan(null, 25.0, 100.0))
    }

    @Test
    fun `movies show the year and credits while series show the episode line`() {
        assertEquals(listOf("intro", "outro"), submitPanelSegmentTypes(isMovie = true))
        assertEquals(listOf("intro", "recap", "outro", "preview"), submitPanelSegmentTypes(isMovie = false))
        assertEquals("2019", submitPanelSubtitle(true, 0, 0, null, "Released 2019 in the US"))
        assertNull(submitPanelSubtitle(true, 1, 2, "Ignored", "unknown"))
        assertEquals(
            "S1 E22 · For Whom the Bell Tolls",
            submitPanelSubtitle(false, 1, 22, "For Whom the Bell Tolls", "2010"),
        )
        assertEquals("S1 E22", submitPanelSubtitle(false, 1, 22, "  ", null))
        assertTrue(submitShowsEndOfVideo("outro"))
        assertTrue(submitShowsEndOfVideo("Outro"))
        assertTrue(!submitShowsEndOfVideo("intro"))
    }
}
