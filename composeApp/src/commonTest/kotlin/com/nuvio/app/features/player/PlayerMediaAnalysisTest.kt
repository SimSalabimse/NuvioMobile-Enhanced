package com.nuvio.app.features.player

import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.UserSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerMediaAnalysisTest {
    @Test
    fun `stored skip times apply to a stream of the same cut or of unknown length`() {
        assertTrue(sameCut(analysedMs = 2_640_000L, playingMs = 2_652_000L))
        assertTrue(sameCut(analysedMs = 2_640_000L, playingMs = 0L))
        assertTrue(sameCut(analysedMs = 0L, playingMs = 2_640_000L))
        assertFalse(sameCut(analysedMs = 2_640_000L, playingMs = 2_760_000L))
    }

    @Test
    fun `auto synced types follow the analysis and skip a mark the person made`() {
        val detected = MediaAnalysisResult(
            key = "show:1:2",
            analyzedAtMs = 1L,
            durationMs = 2_400_000L,
            introStartMs = 0L,
            introEndMs = 90_000L,
            recapStartMs = 90_000L,
            recapEndMs = 150_000L,
            creditsStartMs = 2_200_000L,
            creditsEndMs = 2_400_000L,
            learnedTypes = setOf("intro"),
            foundUsing = mapOf("outro" to 3),
        )
        assertEquals(setOf("intro", "recap", "outro"), segmentTypesSyncedByAnalysis(detected))
        assertFalse("preview" in segmentTypesSyncedByAnalysis(detected))

        val markedIntro = detected.copy(
            userSegments = mapOf("intro" to UserSegment(startMs = 0L, endMs = 80_000L)),
        )
        assertEquals(setOf("recap", "outro"), segmentTypesSyncedByAnalysis(markedIntro))

        val emptyRecap = detected.copy(recapStartMs = 10L, recapEndMs = 10L)
        assertEquals(setOf("intro", "outro"), segmentTypesSyncedByAnalysis(emptyRecap))

        val creditsOnly = MediaAnalysisResult(
            key = "movie",
            analyzedAtMs = 1L,
            durationMs = 6_000_000L,
            creditsStartMs = 5_400_000L,
            creditsEndMs = 6_000_000L,
        )
        assertEquals(setOf("outro"), segmentTypesSyncedByAnalysis(creditsOnly))
        assertEquals(emptySet(), segmentTypesSyncedByAnalysis(null))
        assertEquals(
            emptySet(),
            segmentTypesSyncedByAnalysis(MediaAnalysisResult(key = "empty", analyzedAtMs = 0L, durationMs = 0L)),
        )
    }
}

class PlayerDamagedDownloadTest {
    @Test
    fun `only a jump playback could not have made counts`() {
        kotlin.test.assertFalse(isUnaskedJump(fromMs = 60_000L, toMs = 61_000L, elapsedMs = 1_000L))
        // 2x speed over a second is fine.
        kotlin.test.assertFalse(isUnaskedJump(fromMs = 60_000L, toMs = 62_000L, elapsedMs = 1_000L))
        assertTrue(isUnaskedJump(fromMs = 60_000L, toMs = 300_000L, elapsedMs = 1_000L))
        // Backwards is never a jump ahead.
        kotlin.test.assertFalse(isUnaskedJump(fromMs = 300_000L, toMs = 60_000L, elapsedMs = 1_000L))
    }
}
