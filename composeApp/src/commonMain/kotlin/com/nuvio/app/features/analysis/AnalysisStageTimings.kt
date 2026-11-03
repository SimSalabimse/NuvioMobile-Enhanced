package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.diagnostics.DiagnosticsLogger

/**
 * Per-file stage times for one on-device audio analysis, in milliseconds from a monotonic clock.
 *
 * [decodeMs] is opening the file and decoding packets, with the per-sample loop taken out.
 * It still includes time spent sleeping in the analysis throttle.
 * [decodeNetMs] is [decodeMs] minus [throttledMs], so the decode without those sleeps.
 * [sampleLoopMs] is that loop (K-weighting, energy, decimation).
 * [fingerprintMs] is the head and tail sub-fingerprint FFTs.
 * [matchMs] is intro/credits matching against the season after the file is decoded.
 * [chaptersMs] is reading chapter markers plus classifying them. It is not part of [fingerprintMs].
 * [chapterSource] is `chapters` when a segment was taken from those markers, otherwise `-`.
 * [totalMs] is the whole audio analysis of that file, from the start of the decode through matching.
 * It is not the sum of the other fields: scheduling, JSON and saving sit in the gap.
 * [throttledMs] is time spent inside the throttle sleeps during the decode.
 * [throttleLevelStart] is the throttle level when packet reading started (0 full speed, 1 playing, 2 the player is struggling).
 * [throttleYields] is how many times that throttle check ran (every 64 packets).
 * [qos] is the analyser thread's QoS class name (`utility`, `userInitiated`, and the other GCD names).
 */
internal data class AnalysisStageTimings(
    val decodeMs: Long,
    val sampleLoopMs: Long,
    val fingerprintMs: Long,
    val matchMs: Long,
    val totalMs: Long,
    val chaptersMs: Long = 0L,
    val chapterSource: String = "-",
    val throttledMs: Long,
    val throttleLevelStart: Int,
    val throttleYields: Int,
    val qos: String,
) {
    val decodeNetMs: Long
        get() = decodeMs - throttledMs

    fun line(fileId: String): String =
        "timings id=$fileId decodeMs=$decodeMs sampleLoopMs=$sampleLoopMs " +
            "fingerprintMs=$fingerprintMs matchMs=$matchMs chaptersMs=$chaptersMs " +
            "chapterSource=$chapterSource totalMs=$totalMs " +
            "throttledMs=$throttledMs decodeNetMs=$decodeNetMs " +
            "throttleLevelStart=$throttleLevelStart throttleYields=$throttleYields qos=$qos"
}

/** One line in the in-app debug log and, on iOS, the diagnostics log. */
internal fun logAnalysisStageTimings(fileId: String, timings: AnalysisStageTimings) {
    val line = timings.line(fileId)
    InAppLogger.info("Analysis", line)
    DiagnosticsLogger.logPerformance(line)
}
