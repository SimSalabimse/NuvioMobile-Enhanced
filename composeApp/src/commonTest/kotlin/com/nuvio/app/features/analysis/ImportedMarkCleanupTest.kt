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
}
