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
