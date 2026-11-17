package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.perf.currentProcessMemoryBytes
import com.nuvio.app.features.diagnostics.DiagnosticsLogger

/**
 * PCM and fingerprint storage for one analysis pass.
 * A pause or a finished pass releases both. A hold larger than the window is clamped.
 * 25 minutes and 15 minutes of mono at 6000 Hz are 36 MB and 21.6 MB of floats.
 */
internal const val ANALYSIS_PCM_BYTE_CAP = 64L * 1024 * 1024
internal const val ANALYSIS_FINGERPRINT_BYTE_CAP = 2L * 1024 * 1024

internal class AnalysisWorkingBuffers {
    var pcmBytes: Long = 0L
        private set
    var fingerprintBytes: Long = 0L
        private set

    val retainedBytes: Long
        get() = pcmBytes + fingerprintBytes

    fun holdPcm(bytes: Long) {
        pcmBytes = bytes.coerceIn(0L, ANALYSIS_PCM_BYTE_CAP)
    }

    fun holdFingerprint(bytes: Long) {
        fingerprintBytes = bytes.coerceIn(0L, ANALYSIS_FINGERPRINT_BYTE_CAP)
    }

    /** Pause and finish both drop the working set. Idle does not keep the bytes. */
    fun release() {
        pcmBytes = 0L
        fingerprintBytes = 0L
    }

    fun applyGate(paused: Boolean) {
        if (paused) release()
    }
}

/**
 * Local files do not need a stream readahead.
 * `cache=no`, 8 MiB forward, 2 MiB back.
 * Network streams stay at the existing 64 MiB forward and 32 MiB back, with cache=auto.
 */
internal data class DemuxerCacheOptions(
    val cache: String,
    val maxBytes: Long,
    val maxBackBytes: Long,
)

internal fun demuxerCacheOptions(url: String): DemuxerCacheOptions {
    val trimmed = url.trim()
    val local = trimmed.startsWith("file:") ||
        trimmed.startsWith("/") ||
        trimmed.startsWith("content:")
    return if (local) {
        DemuxerCacheOptions(
            cache = "no",
            maxBytes = 8L * 1024 * 1024,
            maxBackBytes = 2L * 1024 * 1024,
        )
    } else {
        DemuxerCacheOptions(
            cache = "auto",
            maxBytes = 64L * 1024 * 1024,
            maxBackBytes = 32L * 1024 * 1024,
        )
    }
}

/** phys_footprint in MB, the same figure jetsam reports. No URL or path. */
internal fun memoryFootprintLine(event: String, bytes: Long?): String {
    val mb = when {
        bytes == null || bytes < 0L -> -1L
        else -> bytes / (1024L * 1024L)
    }
    val name = event.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(32).ifEmpty { "-" }
    return "memory event=$name footprintMb=$mb"
}

internal fun logProcessMemory(event: String) {
    val line = memoryFootprintLine(event, currentProcessMemoryBytes())
    InAppLogger.info("Memory", line)
    DiagnosticsLogger.logPerformance(line)
}
