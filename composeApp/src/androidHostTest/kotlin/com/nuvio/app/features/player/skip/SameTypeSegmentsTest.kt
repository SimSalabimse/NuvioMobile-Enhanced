package com.nuvio.app.features.player.skip

import android.app.Application
import com.nuvio.app.core.serialization.LenientDefaultsJson
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.UserSegment
import com.nuvio.app.features.analysis.storedUserMarks
import com.nuvio.app.features.analysis.withUserSegmentsApplied
import com.nuvio.app.features.player.MarkLimits
import com.nuvio.app.features.player.formatMarkTimestamp
import com.nuvio.app.features.player.markSegmentLimits
import com.nuvio.app.features.player.markSegmentTypes
import com.nuvio.app.features.player.mergeSkipIntervals
import com.nuvio.app.features.player.onDeviceSkipIntervalsFrom
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * More than one segment of the same type on one episode.
 * These failed when a second intro replaced the first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SameTypeSegmentsTest {
    @Test
    fun twoIntrosAreBothSkips() {
        val first = SkipInterval(10.0, 40.0, "intro", TheIntroDb.PROVIDER)
        val second = SkipInterval(600.0, 640.0, "intro", TheIntroDb.PROVIDER)
        val merged = mergeSkipProviders(listOf(listOf(first, second), listOf(SkipInterval(50.0, 80.0, "intro", "aniskip"))))
        assertEquals(listOf(first, second), merged)
        val playback = resolvePlaybackSkips(merged, emptyList(), emptyList())
        assertEquals(2, playback.count { it.type == "intro" })
        assertEquals(2, intervalsAutoSkipped(playback, setOf(AutoSkipSegmentType.INTRO)).size)
    }

    @Test
    fun gapUnderTwoSecondsJoinsAndTwoSecondsDoesNot() {
        val early = SkipInterval(0.0, 10.0, "intro", TheIntroDb.PROVIDER)
        val close = SkipInterval(11.0, 20.0, "intro", TheIntroDb.PROVIDER)
        val joined = joinCloseSkips(listOf(early, close))
        assertEquals(1, joined.size)
        assertEquals(0.0, joined.single().startTime)
        assertEquals(20.0, joined.single().endTime)
        val apart = SkipInterval(12.0, 20.0, "intro", TheIntroDb.PROVIDER)
        val separate = joinCloseSkips(listOf(early, apart))
        assertEquals(2, separate.size)
    }

    @Test
    fun thirdSubmitIsKeptAndFourthIsRefused() {
        var marks = OwnAccountMarks()
        repeat(3) { index ->
            val rows = marks.segmentRows("tt1:1:1")
            val id = nextSubmissionId("intro", rows)
            marks = marks.withSegment(
                "tt1:1:1",
                AccountSegment(id, "intro", startMs = index * 10_000L, endMs = index * 10_000L + 5_000L),
                OwnMarkStatus.Pending,
            )
        }
        assertEquals(3, marks.segmentRows("tt1:1:1").count { it.type == "intro" })
        assertTrue(canAcceptSubmission(2))
        assertFalse(canAcceptSubmission(3))
        val refused = marks.withSegment(
            "tt1:1:1",
            AccountSegment("intro-4", "intro", startMs = 90_000L, endMs = 95_000L),
            OwnMarkStatus.Pending,
        )
        assertEquals(3, refused.segmentRows("tt1:1:1").count { it.type == "intro" })
        assertEquals(SubmitChipMark.Another, submitChipMark(2))
        assertEquals(SubmitChipMark.Full, submitChipMark(3))
    }

    @Test
    fun ownMarkReplacesOnlyTheOverlappingTheIntroDbIntro() {
        val near = SkipInterval(10.0, 40.0, "intro", TheIntroDb.PROVIDER)
        val far = SkipInterval(600.0, 640.0, "intro", TheIntroDb.PROVIDER)
        val mine = SkipInterval(10.0, 40.0, "intro", "you")
        val other = SkipInterval(70.0, 90.0, "intro", "introdb")
        val playback = resolvePlaybackSkips(listOf(near, far, other), listOf(mine), emptyList())
        assertTrue(playback.any { it.provider == "you" && it.startTime == 10.0 })
        assertTrue(playback.any { it.provider == TheIntroDb.PROVIDER && it.startTime == 600.0 })
        assertFalse(playback.any { it.provider == TheIntroDb.PROVIDER && it.startTime == 10.0 })
        assertFalse(playback.any { it.provider == "introdb" })
    }

    @Test
    fun chaptersKeepEveryApiIntro() {
        val api = listOf(
            SkipInterval(10.0, 40.0, "intro", TheIntroDb.PROVIDER),
            SkipInterval(600.0, 640.0, "intro", TheIntroDb.PROVIDER),
        )
        val chapters = listOf(SkipInterval(1.0, 2.0, "recap", "chapters"))
        val merged = mergeSkipIntervals(api, chapters, isMovie = false)
        assertEquals(2, merged.count { it.type == "intro" })
        assertEquals(1, merged.count { it.type == "recap" })
    }

    @Test
    fun dismissSticksToOneSkip() {
        val first = SkipInterval(10.0, 40.0, "intro", TheIntroDb.PROVIDER)
        val second = SkipInterval(600.0, 640.0, "intro", TheIntroDb.PROVIDER)
        assertTrue(keepSkipDismissed(first, first))
        assertFalse(keepSkipDismissed(first, second))
    }

    @Test
    fun legacyLedgerAndStoredMarksLoadAsOneSegmentEach() {
        val decoded = decodeOwnAccountLedger("scope\ttt1:1:2\tc:intro,recap")
        val rows = decoded.getValue("scope").segmentRows("tt1:1:2")
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.isLegacy() })
        assertEquals(1, rows.count { it.type == "intro" })
        val encoded = encodeOwnAccountLedger(decoded)
        assertFalse(encoded.contains("@"))
        val json = """
            {"key":"show:1:2","analyzedAtMs":0,"durationMs":1000,"userSegments":{"intro":{"startMs":1000,"endMs":2000},"recap":{"startMs":3000,"endMs":4000}}}
        """.trimIndent()
        val stored = LenientDefaultsJson.decodeFromString(MediaAnalysisResult.serializer(), json)
        val marks = stored.storedUserMarks()
        assertEquals(2, marks.size)
        assertEquals("intro", marks.first { it.id == "intro" }.type)
        assertEquals("recap", marks.first { it.id == "recap" }.type)
    }

    @Test
    fun detectionStaysOneIntroWhenASecondMarkIsStored() {
        val result = MediaAnalysisResult(
            key = "show:1:2",
            analyzedAtMs = 0L,
            durationMs = 1_000_000L,
            introStartMs = 0L,
            introEndMs = 8_000L,
            userSegments = mapOf(
                "intro" to UserSegment(startMs = 1_000L, endMs = 5_000L, durationMs = 1_000_000L),
                "intro-2" to UserSegment(startMs = 20_000L, endMs = 30_000L, durationMs = 1_000_000L, type = "intro", id = "intro-2"),
            ),
        )
        val applied = result.withUserSegmentsApplied()
        assertEquals(1_000L, applied.introStartMs)
        assertEquals(5_000L, applied.introEndMs)
        val detected = onDeviceSkipIntervalsFrom(applied, recapDetection = false, introDetection = true)
        assertEquals(1, detected.count { it.type == "intro" })
    }

    @Test
    fun previewLimitsAndClockFormatStayTheSame() {
        assertEquals(MarkLimits(5.0, 1_800.0), markSegmentLimits("preview"))
        assertTrue(markSegmentTypes(isMovie = false).contains("preview"))
        assertEquals("00:00:40", formatSecondsToHms(40.0))
        assertEquals("0:00:40.000", formatMarkTimestamp(40.0))
    }

    @Test
    fun flagLedgerKeepsThreeOfOneType() {
        FlagSubmitLedger.initialize(RuntimeEnvironment.getApplication())
        val key = "tt0000002:1:1"
        val previous = FlagSubmitLedger.loadAll()
        FlagSubmitLedger.saveAll(previous - key)
        FlagSubmitLedger.rememberType(key, "intro")
        FlagSubmitLedger.rememberType(key, "intro")
        FlagSubmitLedger.rememberType(key, "intro")
        FlagSubmitLedger.rememberType(key, "intro")
        assertEquals(listOf("intro", "intro", "intro"), FlagSubmitLedger.loadSegments(key))
        FlagSubmitLedger.saveAll(previous)
    }
}
