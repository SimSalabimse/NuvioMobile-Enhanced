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
            AnalysisPart.NotFound,
            recapAnalysisPart(
                recapEnabled = true,
                isEpisode = true,
                derivedCurrent = true,
                recapEndMs = null,
                userRecap = mark.copy(durationMs = 2_000_000L),
                durationMs = 1_200_000L,
                siblingsEmpty = false,
            ),
        )
    }
}
