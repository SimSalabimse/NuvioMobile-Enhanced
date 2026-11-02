package com.nuvio.app.features.analysis

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SkipLearningMatchTest {
    @Test
    fun aFortySecondOpeningKeepsFortySecondsOfASeventySecondReference() {
        val random = Random(31)
        val shared = IntArray(400) { random.nextInt() }
        val window = shared + IntArray(300) { random.nextInt() }
        val referenceHead = window + IntArray(5_300) { random.nextInt() }
        val targetHead = IntArray(6_000) { random.nextInt() }
        shared.copyInto(targetHead, destinationOffset = 100)
        val reference = episode(
            "learncut__s1e1",
            referenceHead,
            1_470_000L,
            UserSegment(0L, 70_000L, durationMs = 1_470_000L),
        )
        val target = episode("learncut__s1e2", targetHead, 1_440_000L, null)
        val match = assertNotNull(SkipLearning.matchReference(reference, "intro", target))
        assertEquals(10_000L, match.first)
        assertEquals(50_000L, match.second)
        assertEquals(400, match.third)
    }

    @Test
    fun anExtendedCutIsNotTaughtToTheBroadcastCut() {
        val random = Random(32)
        val shared = IntArray(600) { random.nextInt() }
        val referenceHead = shared + IntArray(5_400) { random.nextInt() }
        val targetHead = IntArray(6_000) { random.nextInt() }
        shared.copyInto(targetHead, destinationOffset = 100)
        val reference = episode(
            "learnext__s1e1",
            referenceHead,
            1_471_000L,
            UserSegment(0L, 60_000L, durationMs = 1_471_000L),
        )
        val broadcast = episode("learnext__s1e2", targetHead, 1_440_000L, null)
        assertNull(SkipLearning.matchReference(reference, "intro", broadcast))
        val unknownLength = broadcast.copy(durationMs = 0L)
        val match = assertNotNull(SkipLearning.matchReference(reference, "intro", unknownLength))
        assertEquals(10_000L, match.first)
        assertEquals(70_000L, match.second)
    }

    @Test
    fun aMarkWithNoDurationDoesNotTeach() {
        val reference = MediaAnalysisResult(
            key = "nodur__s1e1",
            analyzedAtMs = 0L,
            durationMs = 1_440_000L,
            headFingerprint = encodeFingerprint(IntArray(600) { 1 }),
            userSegments = mapOf("intro" to UserSegment(0L, 70_000L, markedAtMs = 1L)),
        )
        val target = MediaAnalysisResult(
            key = "nodur__s1e2",
            analyzedAtMs = 0L,
            durationMs = 1_440_000L,
            headFingerprint = reference.headFingerprint,
        )
        assertNull(SkipLearning.matchReference(reference, "intro", target))
    }

    private fun episode(key: String, head: IntArray, durationMs: Long, mark: UserSegment?) = MediaAnalysisResult(
        key = key,
        analyzedAtMs = 0L,
        durationMs = durationMs,
        fingerprintStepMs = 100,
        headFingerprint = encodeFingerprint(head),
        userSegments = if (mark == null) emptyMap() else mapOf("intro" to mark),
    )
}
