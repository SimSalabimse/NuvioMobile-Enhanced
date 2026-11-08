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
 * The decode covers the requested segment plus its pad when that fits in [REFERENCE_MAX_WINDOW_MS].
 * A longer known segment is marked partial. It is not shortened to the first 50 seconds and then treated as done.
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

/** A clip that reports work past the budget is discarded. Missing counters are not treated as over budget. */
internal fun reportedClipExceedsBudget(frames: Int, samples: Int, windowMs: Long): Boolean {
    if (frames <= 0 && samples <= 0) return false
    val limits = clipDecodeLimits(windowMs)
    return frames > limits.maxFrames || samples > limits.maxSamples
}
