package com.nuvio.app.features.analysis

/**
 * Finds audio shared by two episodes (an intro or the end credits) from their sub-fingerprints,
 * the way Jellyfin's intro-skipper does with chromaprint: try every alignment of the two
 * sequences and keep the longest run of near-identical frames along one alignment.
 *
 * A frame matches when the two 32-bit sub-fingerprints differ in at most [maxBitErrors] bits;
 * short dropouts of up to [maxGapFrames] are bridged.
 */
internal object FingerprintMatcher {
    data class SharedSegment(
        val startA: Int,
        val endA: Int,
        val startB: Int,
        val endB: Int,
    ) {
        val length: Int get() = endA - startA
    }

    fun longestSharedSegment(
        a: IntArray,
        b: IntArray,
        minFrames: Int,
        maxFrames: Int = Int.MAX_VALUE,
        maxBitErrors: Int = 6,
        maxGapFrames: Int = 5,
    ): SharedSegment? {
        if (a.isEmpty() || b.isEmpty()) return null
        var best: SharedSegment? = null
        // shift = indexB - indexA
        for (shift in -(a.size - 1) until b.size) {
            val startI = maxOf(0, -shift)
            val endI = minOf(a.size, b.size - shift)
            if (endI - startI < minFrames) continue
            var runStart = -1
            var lastMatch = -1
            var i = startI
            while (i < endI) {
                val diff = (a[i] xor b[i + shift]).countOneBits()
                if (diff <= maxBitErrors) {
                    if (runStart < 0 || i - lastMatch > maxGapFrames + 1) {
                        runStart = i
                    }
                    lastMatch = i
                    val length = lastMatch + 1 - runStart
                    if (length >= minFrames && length <= maxFrames && length > (best?.length ?: 0)) {
                        best = SharedSegment(runStart, lastMatch + 1, runStart + shift, lastMatch + 1 + shift)
                    }
                }
                i++
            }
        }
        return best
    }
}

/** Intro and credits found for one episode by matching against other episodes of its season. */
internal data class DetectedSegments(
    val introStartMs: Long?,
    val introEndMs: Long?,
    val creditsStartMs: Long?,
    val creditsEndMs: Long?,
)

internal object SeasonSegmentDetector {
    private const val MIN_INTRO_MS = 15_000L
    private const val MAX_INTRO_MS = 150_000L
    private const val MIN_CREDITS_MS = 15_000L

    /** Best intro/credits of [episode] against each of [others]; the longest agreeing match wins. */
    fun detect(episode: MediaAnalysisResult, others: List<MediaAnalysisResult>): DetectedSegments {
        val head = decodeFingerprint(episode.headFingerprint)
        val tail = decodeFingerprint(episode.tailFingerprint)
        val step = episode.fingerprintStepMs.coerceAtLeast(1)
        var intro: FingerprintMatcher.SharedSegment? = null
        var credits: FingerprintMatcher.SharedSegment? = null
        for (other in others) {
            if (other.fingerprintStepMs != episode.fingerprintStepMs) continue
            val otherHead = decodeFingerprint(other.headFingerprint)
            FingerprintMatcher.longestSharedSegment(
                head, otherHead,
                minFrames = (MIN_INTRO_MS / step).toInt(),
                maxFrames = (MAX_INTRO_MS / step).toInt(),
            )?.let { if (it.length > (intro?.length ?: 0)) intro = it }
            val otherTail = decodeFingerprint(other.tailFingerprint)
            FingerprintMatcher.longestSharedSegment(
                tail, otherTail,
                minFrames = (MIN_CREDITS_MS / step).toInt(),
            )?.let { if (it.length > (credits?.length ?: 0)) credits = it }
        }
        val introSegment = intro
        val creditsSegment = credits
        val creditsStart = creditsSegment?.let { episode.tailStartMs + it.startA * step.toLong() }
        // Credits that run to the end of the file end at the file's duration.
        val creditsEnd = creditsSegment?.let {
            val end = episode.tailStartMs + it.endA * step.toLong()
            if (episode.durationMs > 0L && episode.durationMs - end < 10_000L) episode.durationMs else end
        }
        return DetectedSegments(
            introStartMs = introSegment?.let { it.startA * step.toLong() },
            introEndMs = introSegment?.let { it.endA * step.toLong() },
            creditsStartMs = creditsStart,
            creditsEndMs = creditsEnd,
        )
    }
}

/**
 * "Previously on" recaps: the stretch before the intro that replays material from earlier
 * episodes. The part of this episode before its intro is matched, at any alignment, against what
 * is stored of the other episodes (their first 10 and last 8 minutes, their own intros masked
 * out). Recaps are a montage of short clips, so a few seconds of shared audio are enough; the
 * recap is then taken to run from the start (when the first match is near it) to the intro.
 *
 * Needs a detected intro to bound the recap. Clips from the middle of earlier episodes are not
 * stored, so a recap made only of those is not found.
 */
internal object RecapDetector {
    private const val MIN_CLIP_MS = 4_000L
    private const val MIN_RECAP_MS = 10_000L
    private const val MAX_RECAP_MS = 240_000L
    private const val SNAP_TO_START_MS = 20_000L
    private const val SNAP_TO_INTRO_MS = 60_000L

    fun detect(episode: MediaAnalysisResult, others: List<MediaAnalysisResult>): Pair<Long, Long>? {
        val step = episode.fingerprintStepMs.coerceAtLeast(1).toLong()
        val introStart = episode.introStartMs ?: return null
        if (introStart < MIN_RECAP_MS) return null
        val head = decodeFingerprint(episode.headFingerprint)
        val windowFrames = minOf(head.size, (minOf(introStart, MAX_RECAP_MS + SNAP_TO_START_MS) / step).toInt())
        val minFrames = (MIN_CLIP_MS / step).toInt()
        if (windowFrames < minFrames) return null
        val window = head.copyOfRange(0, windowFrames)
        var firstStart = Int.MAX_VALUE
        var lastEnd = 0
        for (other in others) {
            if (other.fingerprintStepMs.toLong() != step) continue
            val sequences = listOf(
                maskRange(decodeFingerprint(other.headFingerprint), other.introStartMs, other.introEndMs, step, 0L),
                maskRange(decodeFingerprint(other.tailFingerprint), other.creditsStartMs, other.creditsEndMs, step, other.tailStartMs),
            )
            for (sequence in sequences) {
                val segment = FingerprintMatcher.longestSharedSegment(window, sequence, minFrames = minFrames) ?: continue
                firstStart = minOf(firstStart, segment.startA)
                lastEnd = maxOf(lastEnd, segment.endA)
            }
        }
        if (lastEnd == 0) return null
        val startMs = (firstStart * step).let { if (it <= SNAP_TO_START_MS) 0L else it }
        val lastMs = lastEnd * step
        val endMs = if (introStart >= lastMs && introStart - lastMs <= SNAP_TO_INTRO_MS) introStart else lastMs
        if (endMs - startMs !in MIN_RECAP_MS..MAX_RECAP_MS) return null
        return startMs to endMs
    }

    /** Replaces frames in [startMs, endMs) (relative to [offsetMs]) with values that never match. */
    private fun maskRange(frames: IntArray, startMs: Long?, endMs: Long?, step: Long, offsetMs: Long): IntArray {
        if (startMs == null || endMs == null || frames.isEmpty()) return frames
        val from = ((startMs - offsetMs) / step).toInt().coerceIn(0, frames.size)
        val to = ((endMs - offsetMs) / step).toInt().coerceIn(0, frames.size)
        if (to <= from) return frames
        val out = frames.copyOf()
        for (i in from until to) out[i] = (i * -0x61c88647) xor 0x5A5A5A5A
        return out
    }
}
