package com.nuvio.app.features.player.skip

import com.nuvio.app.features.analysis.SkipLearning
import com.nuvio.app.features.player.skipFeedbackOwnLedger
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The review scenarios for more than one segment of the same type.
 * Each one failed while a type was still a single slot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SameTypeSegmentsAdversarialTest {

    @Test
    fun feedbackPlusBarAllowsAFourthServerSubmit() {
        val key = "tt1:1:1"
        var marks = OwnAccountMarks()
        repeat(3) {
            marks = skipFeedbackOwnLedger(
                marks = marks,
                contentKey = key,
                segmentType = "intro",
                service = IntroSubmitService.THE_INTRODB,
                sent = true,
            )
        }
        val stored = marks.segmentRows(key).filter { it.type == "intro" }
        assertEquals(3, stored.size)
        val fourth = skipFeedbackOwnLedger(
            marks = marks,
            contentKey = key,
            segmentType = "intro",
            service = IntroSubmitService.THE_INTRODB,
            sent = true,
        )
        assertEquals(3, fourth.segmentRows(key).count { it.type == "intro" })
        assertFalse(canAcceptSubmission(accountSubmissionCount(stored, "intro", remoteCount = 0)))
        val introDb = skipFeedbackOwnLedger(
            marks = OwnAccountMarks(),
            contentKey = key,
            segmentType = "intro",
            service = IntroSubmitService.INTRODB,
            sent = true,
        )
        assertEquals(OwnAccountMarks(), introDb)
    }

    @Test
    fun otherAccountIsNotFullBecauseThisProfileFlaggedThree() {
        val key = "tt-flag:1:1"
        val previous = FlagSubmitLedger.loadAll()
        try {
            FlagSubmitLedger.saveAll(previous + (key to listOf("intro", "intro", "intro")))
            assertEquals(3, FlagSubmitLedger.loadSegments(key).count { it == "intro" })
            assertTrue(canAcceptSubmission(accountSubmissionCount(emptyList(), "intro", remoteCount = 0)))
            val scope = "account-other"
            OwnTheIntroDbLedger.rememberSegment(
                scope = scope,
                contentKey = key,
                segmentType = "intro",
                status = OwnMarkStatus.Pending,
                startMs = 0L,
                endMs = 5_000L,
            )
            assertEquals(1, OwnTheIntroDbLedger.load(scope).segmentRows(key).count { it.type == "intro" })
        } finally {
            FlagSubmitLedger.saveAll(previous)
        }
    }

    @Test
    fun partialOverlapKeepsTheRemoteTail() {
        val remote = SkipInterval(0.0, 90.0, "intro", TheIntroDb.PROVIDER)
        val mine = SkipInterval(40.0, 50.0, "intro", "you")
        val playback = resolvePlaybackSkips(listOf(remote), listOf(mine), emptyList())
        assertEquals(0.0, playback.first().startTime)
        assertEquals(90.0, playback.last().endTime)
        assertTrue(playback.any { it.startTime < 40.0 })
        assertTrue(playback.any { it.endTime > 50.0 })
    }

    @Test
    fun movieCreditsMarkWinsWhereItOverlaps() {
        val remote = SkipInterval(100.0, 200.0, "movie-credits", TheIntroDb.PROVIDER)
        val scene = SkipInterval(210.0, 230.0, "post-credits", TheIntroDb.PROVIDER)
        val mine = SkipInterval(100.0, 200.0, "outro", "you")
        val playback = resolvePlaybackSkips(listOf(remote, scene), listOf(mine), emptyList())
        assertTrue(playback.any { it.provider == "you" && it.startTime == 100.0 })
        assertFalse(playback.any { it.type == "movie-credits" })
        assertTrue(playback.any { it.type == "post-credits" && it.startTime == 210.0 })
    }

    @Test
    fun oneRejectedIntroDoesNotDropTheOtherConfirmedIntros() {
        val buckets = ownListBucketsForTitle(
            records = listOf(
                FlagSubmissionRecord(7, "tv", 1, 1, "intro", "approved", submissionId = "a"),
                FlagSubmissionRecord(7, "tv", 1, 1, "intro", "approved", submissionId = "b"),
                FlagSubmissionRecord(7, "tv", 1, 1, "intro", "rejected", submissionId = "c"),
            ),
            tmdbId = 7,
            isMovie = false,
            season = 1,
            episode = 1,
        )
        assertEquals(setOf("a", "b"), buckets.confirmedRows.map { it.id }.toSet())
        assertFalse(buckets.confirmedRows.any { it.id == "c" })
        assertFalse("intro" in buckets.rejected)
    }

    @Test
    fun setOnlyListingKeepsTimedRows() {
        val stored = OwnAccountMarks().withSegment(
            "tt1:1:1",
            AccountSegment("intro", "intro", startMs = 1_000L, endMs = 5_000L),
            OwnMarkStatus.Pending,
        )
        val listed = applyAccountListing(
            marks = stored,
            contentKey = "tt1:1:1",
            confirmed = setOf("intro"),
            pending = emptySet(),
            rejected = emptySet(),
        )
        val row = listed.segmentRows("tt1:1:1").single { it.type == "intro" }
        assertEquals(1_000L, row.startMs)
        assertEquals(5_000L, row.endMs)
    }

    @Test
    fun laterMovieCreditsStaySkippable() {
        val media = TheIntroDbMediaResponse(
            credits = listOf(
                TheIntroDbSegment(startMs = 100_000L, endMs = 130_000L),
                TheIntroDbSegment(startMs = 200_000L, endMs = 230_000L),
            ),
        )
        val intervals = media.movieSkipIntervals()
        assertEquals(listOf("movie-credits", "movie-credits"), intervals.map { it.type })
        assertNotNull(intervals[0].internalSkipAction(intervals))
        assertNotNull(intervals[1].internalSkipAction(intervals))
        val scene = SkipInterval(240.0, 260.0, "post-credits", "introdb")
        assertNull(scene.internalSkipAction(intervals + scene))
    }

    @Test
    fun replaceTypesKeepsTheRepeatedTypeList() {
        val key = "tt-list:1:1"
        val previous = FlagSubmitLedger.loadAll()
        try {
            FlagSubmitLedger.saveAll(previous + (key to listOf("intro", "intro", "intro")))
            FlagSubmitLedger.replaceTypes(key, setOf("intro", "recap"))
            assertEquals(listOf("intro", "intro", "intro", "recap"), FlagSubmitLedger.loadSegments(key))
        } finally {
            FlagSubmitLedger.saveAll(previous)
        }
    }

    @Test
    fun aZeroCountIsNotTheFullCheck() {
        assertEquals(SubmitChipMark.Open, shownSubmitChip(count = 0, alreadySubmitted = true))
        assertEquals(SubmitChipMark.Full, shownSubmitChip(count = null, alreadySubmitted = true))
        assertEquals(SubmitChipMark.Open, shownSubmitChip(count = null, alreadySubmitted = false))
        assertFalse(TheIntroDbSegmentCapMessage == TheIntroDbAlreadySubmittedMessage)
    }

    @Test
    fun recordMarkKeepsASecondStoredIntro() = runBlocking {
        val show = "show-second-intro-adversarial"
        SkipLearning.recordMark(show, 1, 2, "intro", 1_000L, 8_000L, 1_000_000L)
        assertTrue(SkipLearning.addSegment(show, 1, 2, "intro", 20_000L, 30_000L, 1_000_000L))
        SkipLearning.recordMark(show, 1, 2, "intro", 1_000L, 9_000L, 1_000_000L)
        val marks = SkipLearning.userMarks(show, 1, 2)
        assertEquals(20_000L, marks["intro-2"]?.startMs)
        assertEquals(9_000L, marks["intro"]?.endMs)
    }

    @Test
    fun corruptLedgerLineKeepsValidSegments() {
        val raw = "acct\ttt1:1:1\tc:@@@,intro,intro-2@intro:nope:5000\tp:recap\ngarbage\nshort"
        val rows = decodeOwnAccountLedger(raw)["acct"]!!.segmentRows("tt1:1:1")
        assertTrue(rows.any { it.type == "intro" && it.isLegacy() })
        assertTrue(rows.any { it.type == "intro" && it.id == "intro-2" && it.endMs == 5_000L })
        assertTrue(rows.any { it.type == "recap" && it.isLegacy() })
    }
}
