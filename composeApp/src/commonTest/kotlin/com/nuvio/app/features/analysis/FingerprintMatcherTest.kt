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

    @Test
    fun seasonDetectorReportsIntroAndCreditsTimes() {
        val random = Random(3)
        val intro = noise(random, 400)
        val credits = noise(random, 500)
        fun episode(key: String, introAt: Int, creditsAt: Int): MediaAnalysisResult {
            val head = noise(random, introAt) + intro + noise(random, 6000 - introAt - intro.size)
            val tail = noise(random, creditsAt) + credits + noise(random, 4800 - creditsAt - credits.size)
            return MediaAnalysisResult(
                key = key,
                analyzedAtMs = 0L,
                durationMs = 2_400_000L,
                headFingerprint = encodeFingerprint(head),
                tailFingerprint = encodeFingerprint(tail),
                tailStartMs = 2_400_000L - TAIL_FINGERPRINT_MS,
            )
        }
        val one = episode("s1e1", introAt = 900, creditsAt = 4300)
        val two = episode("s1e2", introAt = 30, creditsAt = 4000)
        val found = SeasonSegmentDetector.detect(one, listOf(two))
        assertEquals(90_000L, found.introStartMs)
        assertEquals(130_000L, found.introEndMs)
        assertEquals(one.tailStartMs + 430_000L, found.creditsStartMs)
        // The credits run to the end of the tail window, so they end at the file's duration.
        assertEquals(2_400_000L, found.creditsEndMs)
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
