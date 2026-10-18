package com.nuvio.app.features.player

import kotlin.test.Test
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
}
