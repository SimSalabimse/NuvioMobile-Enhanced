package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalysisStoreLockTest {
    @Test
    fun updateDoesNotReenterTheLock() {
        MediaAnalysisFiles.retainForTests = true
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisStore.clearCacheForTests()
        val key = MediaAnalysisStore.keyFor("tt0000001", 1, 6)
        val saved = MediaAnalysisStore.update(key) {
            MediaAnalysisResult(key = key, analyzedAtMs = 1L, durationMs = 1_000L, fileName = "e6.mkv")
        }
        assertEquals("e6.mkv", saved?.fileName)
        assertEquals(0, MediaAnalysisStore.load(key)?.analysisAttempts)
        MediaAnalysisStore.update(key) { current -> current?.copy(analysisAttempts = 2) }
        assertEquals(2, MediaAnalysisStore.load(key)?.analysisAttempts)
        MediaAnalysisStore.linkShow("tt0000001", 42)
        assertEquals(key, MediaAnalysisStore.keyFor("tt0000001", 1, 6))
        assertNull(MediaAnalysisStore.load("missing"))
    }
}
