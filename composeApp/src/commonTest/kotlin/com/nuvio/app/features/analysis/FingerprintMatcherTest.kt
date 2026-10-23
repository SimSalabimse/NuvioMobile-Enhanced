package com.nuvio.app.features.analysis

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FingerprintMatcherTest {
    private fun noise(random: Random, size: Int) = IntArray(size) { random.nextInt() }

    @Test
    fun findsSharedIntroAtDifferentOffsets() {
        val random = Random(7)
        val intro = noise(random, 600) // 60 s at 100 ms
        val a = noise(random, 300) + intro + noise(random, 2000)
        val b = noise(random, 900) + intro.map { it xor 0b101 } + noise(random, 1500) // 2 bit errors
        val segment = assertNotNull(FingerprintMatcher.longestSharedSegment(a, b, minFrames = 150, maxFrames = 1500))
        assertEquals(300, segment.startA)
        assertEquals(900, segment.endA)
        assertEquals(900, segment.startB)
    }

    @Test
    fun unrelatedAudioHasNoMatch() {
        val random = Random(11)
        assertNull(FingerprintMatcher.longestSharedSegment(noise(random, 1200), noise(random, 1200), minFrames = 150))
    }

    /** Episodes of one season sharing an intro and credits at the given 100 ms frames. */
    private class Season(seed: Int) {
        private val random = Random(seed)
        private val intro = IntArray(400) { random.nextInt() }
        private val credits = IntArray(500) { random.nextInt() }
        private fun noise(size: Int) = IntArray(size) { random.nextInt() }

        fun episode(key: String, introAt: Int, creditsAt: Int): MediaAnalysisResult {
            val head = noise(introAt) + intro + noise(6000 - introAt - intro.size)
            val tail = noise(creditsAt) + credits + noise(4800 - creditsAt - credits.size)
            return MediaAnalysisResult(
                key = key,
                analyzedAtMs = 0L,
                durationMs = 2_400_000L,
                headFingerprint = encodeFingerprint(head),
                tailFingerprint = encodeFingerprint(tail),
                tailStartMs = 2_400_000L - TAIL_FINGERPRINT_MS,
            )
        }
    }

    @Test
    fun seasonDetectorReportsIntroAndCreditsTimes() {
        val season = Season(3)
        val one = season.episode("s1e1", introAt = 900, creditsAt = 4300)
        val two = season.episode("s1e2", introAt = 30, creditsAt = 4000)
        val found = SeasonSegmentDetector.detect(one, listOf(two))
        assertEquals(90_000L, found.introStartMs)
        assertEquals(130_000L, found.introEndMs)
        assertEquals(one.tailStartMs + 430_000L, found.creditsStartMs)
        // The credits run to the end of the tail window, so they end at the file's duration.
        assertEquals(2_400_000L, found.creditsEndMs)
    }

    @Test
    fun deletedEpisodeStillTeachesTheNextDownload() {
        // Download E1, analyse it, delete it; then download E2 alone.
        val season = Season(5)
        val key = "seqtest__s1e1"
        MediaAnalysisStore.save(
            season.episode(key, introAt = 900, creditsAt = 4300)
                .copy(fileName = "e1.mkv", energy = "AAAA", integratedLufs = -20.0),
        )
        MediaAnalysisStore.forgetFileData(key)
        val kept = assertNotNull(MediaAnalysisStore.load(key))
        assertNull(kept.fileName)
        assertNull(kept.energy)
        assertNotNull(kept.headFingerprint)
        assertNotNull(kept.tailFingerprint)

        val two = season.episode("seqtest__s1e2", introAt = 30, creditsAt = 4000)
        val found = SeasonSegmentDetector.detect(two, listOf(kept))
        assertEquals(3_000L, found.introStartMs)
        assertEquals(43_000L, found.introEndMs)
        assertEquals(two.tailStartMs + 400_000L, found.creditsStartMs)
    }

    @Test
    fun loudnessGainIsCapped() {
        val quiet = MediaAnalysisResult(key = "k", analyzedAtMs = 0, durationMs = 1, integratedLufs = -40.0)
        val loud = quiet.copy(integratedLufs = -2.0)
        assertEquals(6.0, quiet.loudnessGainDb())
        assertEquals(-12.0, loud.loudnessGainDb())
        assertTrue(quiet.copy(integratedLufs = null).loudnessGainDb() == null)
    }
}
