package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ReferenceAlignmentTest {
    @Test
    fun aNinetySecondIntroIsMappedFromTwoSlicesOntoTheFullSegment() = runBlocking {
        val segmentStart = 20_000L
        val segmentEnd = 110_000L
        val offset = 3_000L
        val slices = referenceDecodeSlices(segmentStart, segmentEnd, padMs = 5_000L)
        assertEquals(2, slices.size)
        assertTrue(slices.all { it.decodeEndMs - it.decodeStartMs <= 50_000L })
        val decoded = mutableListOf<Pair<Long, Long>>()
        val aligned = alignReferenceSegment(
            segmentStartMs = segmentStart,
            segmentEndMs = segmentEnd,
            padMs = 5_000L,
            frames = targetFrames(offset),
            framesOffsetMs = 0L,
            stepMs = 1_000L,
            targetDurationMs = 0L,
            referenceDurationMs = 0L,
            decode = { start, end ->
                decoded += start to end
                clipFor(start, slices)
            },
        )
        assertTrue(aligned is ReferenceAlignment.Matched, "$aligned")
        val range = (aligned as ReferenceAlignment.Matched).range
        assertEquals(segmentStart + offset, range.first)
        assertEquals(segmentEnd + offset, range.second)
        assertEquals(listOf(15_000L to 65_000L, 65_000L to 115_000L), decoded)
        assertTrue(range.second - range.first > 50_000L)
    }

    @Test
    fun oneSliceOfALongIntroIsNotStoredAsTheWholeIntro() = runBlocking {
        val aligned = alignReferenceSegment(
            segmentStartMs = 20_000L,
            segmentEndMs = 110_000L,
            padMs = 5_000L,
            frames = targetFrames(3_000L),
            framesOffsetMs = 0L,
            stepMs = 1_000L,
            targetDurationMs = 0L,
            referenceDurationMs = 0L,
            decode = { start, _ ->
                val slices = referenceDecodeSlices(20_000L, 110_000L, 5_000L)
                if (start == slices.first().decodeStartMs) clipFor(start, slices) else null
            },
        )
        assertTrue(aligned is ReferenceAlignment.Unreadable || aligned is ReferenceAlignment.Partial, "$aligned")
    }

    @Test
    fun creditsUseTheLocatedRunAndABudgetStopDoesNotTrustTheSegment() = runBlocking {
        val creditsStart = 2_200_000L
        val creditsEnd = 2_220_000L
        val offset = 4_000L
        val matched = alignReferenceSegment(
            segmentStartMs = creditsStart,
            segmentEndMs = creditsEnd,
            padMs = 5_000L,
            frames = IntArray(30) { index -> if (index in 4..23) lane(index - 4, 6) else lane(index, 14) },
            framesOffsetMs = creditsStart,
            stepMs = 1_000L,
            targetDurationMs = 2_400_000L,
            referenceDurationMs = 2_400_000L,
            decode = { start, end ->
                fingerprint(
                    startMs = start,
                    values = IntArray(((end - start) / 1_000L).toInt()) { index ->
                        val contentIndex = index - 5
                        if (contentIndex in 0..19) lane(contentIndex, 6) else lane(index, 2)
                    },
                )
            },
        )
        assertTrue(matched is ReferenceAlignment.Matched, "$matched")
        val range = (matched as ReferenceAlignment.Matched).range
        assertEquals(creditsStart + offset, range.first)
        assertTrue(range.second - range.first <= 20_000L)

        val stopped = alignReferenceSegment(
            segmentStartMs = creditsStart,
            segmentEndMs = creditsEnd,
            padMs = 5_000L,
            frames = IntArray(30) { lane(it, 14) },
            framesOffsetMs = creditsStart,
            stepMs = 1_000L,
            targetDurationMs = 2_400_000L,
            referenceDurationMs = 2_400_000L,
            decode = { start, _ ->
                fingerprint(start, IntArray(10) { lane(it, 6) }).copy(
                    windowComplete = true,
                    stopReason = "missing-timestamp",
                )
            },
        )
        assertTrue(stopped is ReferenceAlignment.Partial, "$stopped")
        assertEquals(false, clipCoversRequestedWindow(windowComplete = true, stopReason = "timestamp-stuck"))
        assertEquals(false, clipCoversRequestedWindow(windowComplete = false, stopReason = "window-end"))
        assertEquals(true, clipCoversRequestedWindow(windowComplete = true, stopReason = "window-end"))
    }

    @Test
    fun aSharedThemePastTheOpeningKeepsTheLocatedRun() = runBlocking {
        val segmentStart = 20_000L
        val segmentEnd = 80_000L
        val frames = IntArray(100) { lane(it, 14) }
        for (index in 45 until 80) frames[index] = lane(index - 45, 6)
        val baseline = SiblingClipReference.locate(
            frames = frames,
            framesOffsetMs = 0L,
            clip = tone(15_000L, 85_000L),
            clipStartMs = 15_000L,
            step = 1_000L,
            segment = segmentStart to segmentEnd,
            targetDurationMs = 2_400_000L,
            referenceDurationMs = 2_400_000L,
        )
        assertEquals(45_000L to 80_000L, baseline)
        val aligned = alignReferenceSegment(
            segmentStartMs = segmentStart,
            segmentEndMs = segmentEnd,
            padMs = 5_000L,
            frames = frames,
            framesOffsetMs = 0L,
            stepMs = 1_000L,
            targetDurationMs = 2_400_000L,
            referenceDurationMs = 2_400_000L,
            decode = { start, end -> fingerprint(start, tone(start, end)) },
        )
        assertTrue(aligned is ReferenceAlignment.Matched, "$aligned")
        val range = (aligned as ReferenceAlignment.Matched).range
        assertEquals(baseline, range)
        assertTrue(range.second - range.first < segmentEnd - segmentStart)
    }

    @Test
    fun aSharedRunShorterThanHalfTheMarkIsNotStored() = runBlocking {
        val segmentStart = 20_000L
        val segmentEnd = 80_000L
        val frames = IntArray(100) { lane(it, 14) }
        for (index in 55 until 80) frames[index] = lane(index - 55, 6)
        val baseline = SiblingClipReference.locate(
            frames = frames,
            framesOffsetMs = 0L,
            clip = tone(15_000L, 85_000L, themeStart = 55, themeEnd = 80),
            clipStartMs = 15_000L,
            step = 1_000L,
            segment = segmentStart to segmentEnd,
            targetDurationMs = 2_400_000L,
            referenceDurationMs = 2_400_000L,
        )
        assertEquals(null, baseline)
        val aligned = alignReferenceSegment(
            segmentStartMs = segmentStart,
            segmentEndMs = segmentEnd,
            padMs = 5_000L,
            frames = frames,
            framesOffsetMs = 0L,
            stepMs = 1_000L,
            targetDurationMs = 2_400_000L,
            referenceDurationMs = 2_400_000L,
            decode = { start, end -> fingerprint(start, tone(start, end, themeStart = 55, themeEnd = 80)) },
        )
        assertTrue(aligned is ReferenceAlignment.Partial, "$aligned")
    }

    @Test
    fun localWorkPreemptsAReferenceAndAnUnreadWindowIsNotFinished() {
        val lane = AnalysisLane()
        assertTrue(lane.beginReference())
        assertTrue(lane.referenceHeld)
        assertEquals(false, lane.beginReference())
        assertTrue(lane.beginLocal())
        assertEquals(false, lane.referenceHeld)
        lane.finish()
        assertEquals(
            ReferenceDisposition.Preempted,
            referenceDisposition(SiblingClipAttempt.Preempted, attemptsSoFar = 0, maxAttempts = 2),
        )
        assertEquals(
            ReferenceDisposition.Retry,
            referenceDisposition(SiblingClipAttempt.Unavailable, attemptsSoFar = 0, maxAttempts = 2),
        )
        assertEquals(
            ReferenceDisposition.GaveUp,
            referenceDisposition(SiblingClipAttempt.Unavailable, attemptsSoFar = 1, maxAttempts = 2),
        )
        assertEquals(
            ReferenceDisposition.Settled,
            referenceDisposition(SiblingClipAttempt.Finished, attemptsSoFar = 0, maxAttempts = 2),
        )
    }

    private fun targetFrames(offsetMs: Long): IntArray {
        val frames = IntArray(130) { lane(it, 14) }
        place(frames, 20_000L + offsetMs, pattern(6, 40))
        place(frames, 70_000L + offsetMs, pattern(13, 40))
        return frames
    }

    private fun clipFor(decodeStart: Long, slices: List<ReferenceSlice>): RawClipFingerprint {
        val slice = slices.first { it.decodeStartMs == decodeStart }
        val count = ((slice.decodeEndMs - slice.decodeStartMs) / 1_000L).toInt()
        val values = IntArray(count) { lane(it, 2) }
        val marker = if (slice.contentStartMs == 20_000L) 6 else 13
        val origin = ((slice.contentStartMs - slice.decodeStartMs) / 1_000L).toInt()
        pattern(marker, 40).forEachIndexed { index, value -> values[origin + index] = value }
        return fingerprint(slice.decodeStartMs, values)
    }

    private fun place(frames: IntArray, atMs: Long, values: IntArray) {
        val origin = (atMs / 1_000L).toInt()
        values.forEachIndexed { index, value -> frames[origin + index] = value }
    }

    private fun pattern(salt: Int, count: Int): IntArray = IntArray(count) { lane(it, salt) }

    /** Synthetic tones. The shared run is [themeStart, themeEnd) seconds. This is not a programme. */
    private fun tone(startMs: Long, endMs: Long, themeStart: Int = 45, themeEnd: Int = 80): IntArray {
        val count = ((endMs - startMs) / 1_000L).toInt()
        return IntArray(count) { index ->
            val second = (startMs / 1_000L).toInt() + index
            if (second in themeStart until themeEnd) lane(second - themeStart, 6) else lane(index, 2)
        }
    }

    /** Frames far enough apart that a 6-bit match cannot slide onto a neighbour. */
    private fun lane(index: Int, salt: Int): Int {
        var mixed = 0x12345678 xor (index * 0x45D9F3B) xor (salt * 0x6C8E9CF5.toInt())
        mixed = mixed xor (mixed ushr 16)
        mixed *= 0x7FEB352D
        mixed = mixed xor (mixed ushr 15)
        mixed *= 0x846CA68B.toInt()
        mixed = mixed xor (mixed ushr 16)
        return mixed or 1
    }

    private fun fingerprint(startMs: Long, values: IntArray) = RawClipFingerprint(
        startMs = startMs,
        stepMs = 1_000,
        decodedFrames = values.size,
        decodedSamples = values.size,
        fingerprint = encodeFingerprint(values),
        windowComplete = true,
        stopReason = "window-end",
    )
}
