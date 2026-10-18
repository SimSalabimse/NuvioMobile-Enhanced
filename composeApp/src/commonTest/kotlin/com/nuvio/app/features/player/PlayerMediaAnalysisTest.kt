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
