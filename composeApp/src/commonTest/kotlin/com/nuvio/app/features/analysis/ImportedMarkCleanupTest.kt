package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportedMarkCleanupTest {
    @Test
    fun dropsImportedMarksAndTheirCopiedTimesButKeepsMarksMadeOnThePhone() {
        val stored = MediaAnalysisResult(
            key = "tt1__s1e16", analyzedAtMs = 0L, durationMs = 2_647_700L,
            userSegments = mapOf(
                "recap" to UserSegment(0L, 60_000L, 1L),
                "intro" to UserSegment(235_000L, 325_000L, 0L, imported = true),
                "outro" to UserSegment(1_245_000L, 1_335_000L, 0L, imported = true),
            ),
        ).withUserSegmentsApplied()
        val cleaned = stored.withoutImportedMarks()
        assertEquals(setOf("recap"), cleaned.userSegments.keys)
        assertEquals(60_000L, cleaned.recapEndMs)
        assertNull(cleaned.introStartMs)
        assertNull(cleaned.creditsStartMs)
    }

    @Test
    fun submissionsOnAnUnknownCutNeverTeachOtherEpisodes() {
        kotlin.test.assertFalse(UserSegment(30_000L, 90_000L, imported = true).canTeach)
        kotlin.test.assertTrue(UserSegment(30_000L, 90_000L, imported = true, durationMs = 2_640_000L).canTeach)
        kotlin.test.assertTrue(UserSegment(30_000L, 90_000L, markedAtMs = 1L).canTeach)
    }

    @Test
    fun aReferenceWithAnUntimedSubmissionTeachesNothing() {
        val reference = MediaAnalysisResult(
            key = "tt1__s1e1", analyzedAtMs = 0L, durationMs = 2_640_000L,
            userSegments = mapOf("intro" to UserSegment(30_000L, 90_000L, imported = true)),
        )
        val target = MediaAnalysisResult(key = "tt1__s1e2", analyzedAtMs = 0L, durationMs = 2_640_000L)
        assertNull(SkipLearning.matchReference(reference, "intro", target))
    }
}
