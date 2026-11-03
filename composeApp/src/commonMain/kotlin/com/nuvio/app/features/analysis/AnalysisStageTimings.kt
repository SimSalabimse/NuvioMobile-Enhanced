package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.diagnostics.DiagnosticsLogger

/**
 * Per-file stage times for one on-device audio analysis, in milliseconds from a monotonic clock.
 *
 * [decodeMs] is opening the file and decoding packets, with the per-sample loop taken out.
 * [sampleLoopMs] is that loop (K-weighting, energy, decimation).
 * [fingerprintMs] is the head and tail sub-fingerprint FFTs.
 * [matchMs] is intro/credits matching against the season after the file is decoded.
 * [totalMs] is the whole audio analysis of that file, from the start of the decode through matching.
 * It is not the sum of the other four: scheduling, JSON and saving sit in the gap.
 */
internal data class AnalysisStageTimings(
    val decodeMs: Long,
    val sampleLoopMs: Long,
    val fingerprintMs: Long,
    val matchMs: Long,
    val totalMs: Long,
) {
    fun line(fileId: String): String =
        "timings id=$fileId decodeMs=$decodeMs sampleLoopMs=$sampleLoopMs " +
            "fingerprintMs=$fingerprintMs matchMs=$matchMs totalMs=$totalMs"
}

/** One line in the in-app debug log and, on iOS, the diagnostics log. */
internal fun logAnalysisStageTimings(fileId: String, timings: AnalysisStageTimings) {
    val line = timings.line(fileId)
    InAppLogger.info("Analysis", line)
    DiagnosticsLogger.logPerformance(line)
}
