package com.nuvio.app.features.analysis

/** Hard cap on one reference window, including the small pad around the segment. */
internal const val REFERENCE_MAX_WINDOW_MS = 50_000L

internal const val CLIP_PREROLL_MS = 2_000L
internal const val CLIP_MISSING_TIMESTAMP_FRAMES = 60
internal const val CLIP_STUCK_TIMESTAMP_FRAMES = 60

internal data class ClipDecodeLimits(
    val maxPrerollMs: Long = CLIP_PREROLL_MS,
    val maxMissingTimestampFrames: Int = CLIP_MISSING_TIMESTAMP_FRAMES,
    val maxStuckFrames: Int = CLIP_STUCK_TIMESTAMP_FRAMES,
    val maxFrames: Int,
    val maxSamples: Int,
)

/** Frame and sample budget for one window. A missing or stuck timestamp cannot spend the whole episode. */
internal fun clipDecodeLimits(windowMs: Long): ClipDecodeLimits {
    val window = windowMs.coerceIn(1L, REFERENCE_MAX_WINDOW_MS)
    val maxFrames = (window / 10L).toInt() + 200
    return ClipDecodeLimits(
        maxFrames = maxFrames,
        maxSamples = maxFrames * 4_000,
    )
}

internal data class ReferenceSampleWindow(
    val startMs: Long,
    val endMs: Long,
    val requestedMs: Long,
    /** Complete windows are decoded. A longer request is partial and is not decoded as its first 50 seconds. */
    val complete: Boolean,
)

/**
 * One decode covers the requested segment plus its pad when that fits in [REFERENCE_MAX_WINDOW_MS].
 * A longer segment does not fit in one window. [referenceDecodeSlices] splits it. The whole window is not
 * treated as its first 50 seconds.
 */
internal fun referenceSampleWindow(segmentStartMs: Long, segmentEndMs: Long, padMs: Long): ReferenceSampleWindow {
    val start = (segmentStartMs - padMs).coerceAtLeast(0L)
    val end = (segmentEndMs + padMs).coerceAtLeast(start)
    val requested = end - start
    val complete = requested in 1..REFERENCE_MAX_WINDOW_MS
    return ReferenceSampleWindow(
        startMs = start,
        endMs = if (complete) end else start,
        requestedMs = requested,
        complete = complete,
    )
}

/**
 * Decides when a reference decode must stop.
 * [onFrame] returns true while the caller may keep reading. Samples are counted only inside the window.
 */
internal class ClipDecodeGovernor(
    val startMs: Long,
    val endMs: Long,
) {
    val limits: ClipDecodeLimits = clipDecodeLimits((endMs - startMs).coerceAtLeast(1L))
    var frames: Int = 0
        private set
    var samples: Int = 0
        private set
    var stop: String? = null
        private set
    private var missingTimestamps: Int = 0
    private var stuck: Int = 0
    private var lastTimestampMs: Long? = null

    fun onFrame(timestampMs: Long?, sampleCount: Int): Boolean {
        if (stop != null) return false
        frames += 1
        if (frames > limits.maxFrames) {
            stop = "frame-budget"
            return false
        }
        if (timestampMs == null) {
            missingTimestamps += 1
            if (missingTimestamps > limits.maxMissingTimestampFrames) {
                stop = "missing-timestamp"
                return false
            }
            return true
        }
        if (timestampMs < startMs) {
            if (startMs - timestampMs > limits.maxPrerollMs) {
                stop = "preroll-budget"
                return false
            }
            return true
        }
        val previous = lastTimestampMs
        if (previous != null && timestampMs <= previous) {
            stuck += 1
            if (stuck > limits.maxStuckFrames) {
                stop = "timestamp-stuck"
                return false
            }
        } else {
            stuck = 0
        }
        lastTimestampMs = timestampMs
        if (timestampMs > endMs) {
            stop = "window-end"
            return false
        }
        samples += sampleCount.coerceAtLeast(0)
        if (samples > limits.maxSamples) {
            stop = "sample-budget"
            return false
        }
        return true
    }
}

internal data class ReferenceSlice(
    val contentStartMs: Long,
    val contentEndMs: Long,
    val decodeStartMs: Long,
    val decodeEndMs: Long,
)

/**
 * Windows to fingerprint for one known segment. Each decode window is at most [REFERENCE_MAX_WINDOW_MS].
 * A segment that fits is one window. A longer segment is a start slice and an end slice, not the first 50 seconds
 * standing in for the whole segment.
 */
internal fun referenceDecodeSlices(segmentStartMs: Long, segmentEndMs: Long, padMs: Long): List<ReferenceSlice> {
    val start = segmentStartMs.coerceAtLeast(0L)
    val end = segmentEndMs.coerceAtLeast(start + 1L)
    val whole = referenceSampleWindow(start, end, padMs)
    if (whole.complete && whole.endMs > whole.startMs) {
        return listOf(ReferenceSlice(start, end, whole.startMs, whole.endMs))
    }
    val pad = padMs.coerceAtLeast(0L)
    val contentBudget = (REFERENCE_MAX_WINDOW_MS - pad * 2).coerceAtLeast(1_000L)
    val sliceLen = contentBudget.coerceAtMost(end - start)
    val first = start to (start + sliceLen).coerceAtMost(end)
    val tailStart = (end - sliceLen).coerceAtLeast(start)
    val pieces = ArrayList<Pair<Long, Long>>(2)
    pieces += first
    val tail = tailStart to end
    if (tail != first) pieces += tail
    return pieces.mapNotNull { (contentStart, contentEnd) ->
        val window = referenceSampleWindow(contentStart, contentEnd, pad)
        if (!window.complete || window.endMs <= window.startMs) return@mapNotNull null
        if (window.endMs - window.startMs > REFERENCE_MAX_WINDOW_MS) return@mapNotNull null
        ReferenceSlice(contentStart, contentEnd, window.startMs, window.endMs)
    }
}

/**
 * Full known segment bounds, shifted by the offset the slices agree on.
 * One slice that covers the whole segment keeps the located shared run.
 * A sliced segment is not stored as the slice itself. Disagreeing slices store nothing.
 */
internal fun verifiedSegmentBounds(
    segmentStartMs: Long,
    segmentEndMs: Long,
    slices: List<ReferenceSlice>,
    matches: List<Pair<ReferenceSlice, Pair<Long, Long>>>,
): Pair<Long, Long>? {
    if (matches.isEmpty() || slices.isEmpty()) return null
    val only = slices.singleOrNull()
    if (only != null && only.contentStartMs == segmentStartMs && only.contentEndMs == segmentEndMs) {
        val range = matches.firstOrNull { it.first == only }?.second ?: return null
        return range.takeIf { it.second > it.first }
    }
    if (matches.size < 2) return null
    val offsets = matches.map { (slice, range) -> range.first - slice.contentStartMs }
    val median = offsets.sorted()[offsets.size / 2]
    if (offsets.any { kotlin.math.abs(it - median) > 2_000L }) return null
    val start = segmentStartMs + median
    val end = segmentEndMs + median
    return if (end > start) start to end else null
}

/**
 * A fingerprint is the requested window only when decoding reached the window end.
 * A budget, stuck timestamp, or abort stop is partial even if some samples were fingerprinted.
 */
internal fun clipCoversRequestedWindow(windowComplete: Boolean, stopReason: String?): Boolean {
    if (!windowComplete) return false
    return stopReason == null || stopReason == "window-end"
}

/** A clip that reports work past the budget is discarded. Missing counters are not treated as over budget. */
internal fun reportedClipExceedsBudget(frames: Int, samples: Int, windowMs: Long): Boolean {
    if (frames <= 0 && samples <= 0) return false
    val limits = clipDecodeLimits(windowMs)
    return frames > limits.maxFrames || samples > limits.maxSamples
}
