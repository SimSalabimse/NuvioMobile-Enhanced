package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecapMarkStatusTest {
    @Test
    fun aSameCutUserMarkIsReadyWhenTheStoredEndIsMissing() {
        val mark = UserSegment(startMs = 0L, endMs = 80_000L, markedAtMs = 5L, durationMs = 1_200_000L)
        assertEquals(
            AnalysisPart.Ready,
            recapAnalysisPart(
                recapEnabled = true,
                isEpisode = true,
                derivedCurrent = true,
                recapEndMs = null,
                userRecap = mark,
                durationMs = 1_200_000L,
                siblingsEmpty = false,
            ),
        )
        assertEquals(
            AnalysisPart.NotChecked,
            recapAnalysisPart(
                recapEnabled = true,
                isEpisode = true,
                derivedCurrent = true,
                recapEndMs = null,
                userRecap = mark.copy(durationMs = 2_000_000L),
                durationMs = 1_200_000L,
                siblingsEmpty = false,
                outcome = "rejected-no-match",
            ),
        )
        assertEquals(RecapChipSentence.Ready, recapChipSentence(AnalysisPart.Ready))
        assertEquals(RecapChipSentence.NotChecked, recapChipSentence(AnalysisPart.NotChecked))
    }

    @Test
    fun aMissingOutcomeIsNotACompletedMiss() {
        val checkedNone = recapAnalysisPart(
            recapEnabled = true,
            isEpisode = true,
            derivedCurrent = true,
            recapEndMs = null,
            userRecap = null,
            durationMs = 1_200_000L,
            siblingsEmpty = false,
            outcome = "rejected-no-match",
        )
        val notRecorded = recapAnalysisPart(
            recapEnabled = true,
            isEpisode = true,
            derivedCurrent = true,
            recapEndMs = null,
            userRecap = null,
            durationMs = 1_200_000L,
            siblingsEmpty = false,
            outcome = null,
        )
        val skipped = recapAnalysisPart(
            recapEnabled = true,
            isEpisode = true,
            derivedCurrent = true,
            recapEndMs = null,
            userRecap = null,
            durationMs = 1_200_000L,
            siblingsEmpty = false,
            outcome = "not-checked-no-print",
        )
        val tooEarly = recapAnalysisPart(
            recapEnabled = true,
            isEpisode = true,
            derivedCurrent = true,
            recapEndMs = null,
            userRecap = null,
            durationMs = 1_200_000L,
            siblingsEmpty = false,
            outcome = "rejected-intro-too-early",
        )
        assertEquals(AnalysisPart.NotFound, checkedNone)
        assertEquals(RecapChipSentence.NoRecapFound, recapChipSentence(checkedNone))
        assertEquals(AnalysisPart.NotChecked, notRecorded)
        assertEquals(AnalysisPart.NotChecked, skipped)
        assertEquals(AnalysisPart.NotChecked, tooEarly)
        assertEquals(RecapChipSentence.NotChecked, recapChipSentence(skipped))
    }

    @Test
    fun anEmptyOpeningPrintIsNotACompletedMiss() {
        val episode = MediaAnalysisResult(
            key = "show__s3e13",
            analyzedAtMs = 1L,
            durationMs = 2_400_000L,
            introStartMs = 40_000L,
        )
        val previous = episode.copy(key = "show__s3e12")
        val trace = SegmentTrace()
        assertEquals(null, RecapDetector.detect(episode, listOf(previous), trace))
        assertEquals("not-checked-no-print", trace.decisions.single().why)
        assertEquals("not-checked-no-print", recapOutcomeToStore(trace.decisions))
    }

    @Test
    fun aShortOpeningPrintIsNotACompletedMiss() {
        val episode = episode(frames = 5, episode = 13)
        val previous = episode(frames = 40, episode = 12)
        val trace = SegmentTrace()
        assertEquals(null, RecapDetector.detect(episode, listOf(previous), trace))
        val why = trace.decisions.single().why
        assertEquals("not-checked-short-window", why)
        assertTrue(why !in RECAP_CHECKED_NONE)
        val stored = recapOutcomeToStore(trace.decisions)
        val part = recapAnalysisPart(
            recapEnabled = true,
            isEpisode = true,
            derivedCurrent = true,
            recapEndMs = null,
            userRecap = null,
            durationMs = episode.durationMs,
            siblingsEmpty = false,
            outcome = stored,
        )
        assertEquals(AnalysisPart.NotChecked, part)
        assertEquals(RecapChipSentence.NotChecked, recapChipSentence(part))
        val boundary = SegmentTrace()
        assertEquals(null, RecapDetector.detect(episode(frames = 19, episode = 13), listOf(previous), boundary))
        assertEquals("not-checked-short-window", boundary.decisions.single().why)
        val compared = SegmentTrace()
        assertEquals(null, RecapDetector.detect(episode(frames = 20, episode = 13), listOf(previous), compared))
        val comparedWhy = compared.decisions.single().why
        assertTrue(comparedWhy in RECAP_CHECKED_NONE)
        assertEquals(AnalysisPart.NotFound, recapAnalysisPart(
            recapEnabled = true,
            isEpisode = true,
            derivedCurrent = true,
            recapEndMs = null,
            userRecap = null,
            durationMs = episode.durationMs,
            siblingsEmpty = false,
            outcome = recapOutcomeToStore(compared.decisions),
        ))
    }

    @Test
    fun aCancelledPassDoesNotKeepAStaleCompletedMiss() {
        val episode = episode(frames = 5, episode = 13)
        val trace = SegmentTrace()
        RecapDetector.detect(episode, listOf(episode(frames = 40, episode = 12)), trace)
        val persisted = recapOutcomeToPersist("rejected-no-match", trace.decisions, finished = false)
        assertEquals("not-checked-short-window", persisted)
        assertEquals(AnalysisPart.NotChecked, recapAnalysisPart(
            recapEnabled = true,
            isEpisode = true,
            derivedCurrent = true,
            recapEndMs = null,
            userRecap = null,
            durationMs = episode.durationMs,
            siblingsEmpty = false,
            outcome = persisted,
        ))
        assertEquals(null, recapOutcomeToPersist("rejected-no-match", emptyList(), finished = false))
        assertEquals("rejected-no-match", recapOutcomeToPersist("rejected-no-match", emptyList(), finished = true))
    }

    @Test
    fun aSameCutMarkStaysReadyWhenTheSearchRecordedAMiss() {
        val mark = UserSegment(startMs = 0L, endMs = 80_000L, markedAtMs = 5L, durationMs = 1_200_000L)
        val part = recapAnalysisPart(
            recapEnabled = true,
            isEpisode = true,
            derivedCurrent = true,
            recapEndMs = null,
            userRecap = mark,
            durationMs = 1_200_000L,
            siblingsEmpty = false,
            outcome = "rejected-no-match",
        )
        assertEquals(AnalysisPart.Ready, part)
        assertEquals(RecapChipSentence.Ready, recapChipSentence(part))
    }

    @Test
    fun aUserNoneBeatsTheDetectorMiss() {
        val mark = UserSegment(
            startMs = 0L,
            endMs = 0L,
            markedAtMs = 9L,
            durationMs = 1_200_000L,
            absent = true,
        )
        val part = recapAnalysisPart(
            recapEnabled = true,
            isEpisode = true,
            derivedCurrent = true,
            recapEndMs = 80_000L,
            userRecap = mark,
            durationMs = 1_200_000L,
            siblingsEmpty = false,
            outcome = "rejected-no-match",
        )
        assertEquals(AnalysisPart.UserMarkedNone, part)
        assertEquals(RecapChipSentence.YouMarkedNone, recapChipSentence(part))
        val stored = MediaAnalysisResult(
            key = "marked__s1e3",
            analyzedAtMs = 1L,
            durationMs = 1_200_000L,
            recapStartMs = 10_000L,
            recapEndMs = 80_000L,
            recapOutcome = "rejected-no-match",
            userSegments = mapOf("recap" to mark),
        ).withUserSegmentsApplied()
        assertEquals(null, stored.recapStartMs)
        assertEquals(null, stored.recapEndMs)
    }

    private fun episode(frames: Int, episode: Int) = MediaAnalysisResult(
        key = "show__s3e$episode",
        analyzedAtMs = 1L,
        durationMs = 2_400_000L,
        fingerprintStepMs = 100,
        headFingerprint = encodeFingerprint(IntArray(frames) { index -> index + episode }),
        introStartMs = 40_000L,
    )
}
