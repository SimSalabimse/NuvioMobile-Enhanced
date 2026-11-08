package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
