package com.nuvio.app.features.downloads

/** Weight of each new sample in the download-speed average. The previous average keeps the rest. */
internal const val DOWNLOAD_SPEED_EMA_ALPHA = 0.2

/** Samples slower than this are treated as idle (a stall, a pause, a clock tick with no bytes). */
internal const val DOWNLOAD_SPEED_MIN_SAMPLE_BYTES_PER_SECOND = 32_768.0

/**
 * Exponential moving average of one transfer's bytes per second.
 * The first accepted sample is the average. Later samples smooth toward it. Zero and tiny rates are ignored.
 */
internal class DownloadSpeedEma(
    private val alpha: Double = DOWNLOAD_SPEED_EMA_ALPHA,
) {
    var bytesPerSecond: Double? = null
        private set

    fun restore(saved: Double?) {
        bytesPerSecond = saved?.takeIf { it.isFinite() && it >= DOWNLOAD_SPEED_MIN_SAMPLE_BYTES_PER_SECOND }
    }

    /**
     * [bytesTransferred] over [elapsedMs]. Returns the updated average.
     * Zero, non-positive, and tiny rates return null and leave the average where it was.
     */
    fun sample(bytesTransferred: Long, elapsedMs: Long): Double? {
        if (bytesTransferred <= 0L || elapsedMs <= 0L) return null
        val instant = bytesTransferred * 1000.0 / elapsedMs.toDouble()
        if (!instant.isFinite() || instant < DOWNLOAD_SPEED_MIN_SAMPLE_BYTES_PER_SECOND) return null
        val previous = bytesPerSecond
        val next = if (previous == null) instant else previous + alpha * (instant - previous)
        bytesPerSecond = next
        return next
    }
}

/**
 * Turns per-download byte counters into [DownloadSpeedEma] samples while those downloads are active.
 * A download that disappears is forgotten, so the next look does not treat the gap as a slow sample.
 */
internal class ActiveDownloadSpeedSampler(
    private val ema: DownloadSpeedEma = DownloadSpeedEma(),
) {
    private val lastBytes = mutableMapOf<String, Long>()
    private val lastAtMs = mutableMapOf<String, Long>()

    /**
     * [active] is id to bytes downloaded, only for transfers that are running.
     * Returns the average when a sample was accepted, otherwise null.
     */
    fun observe(active: List<Pair<String, Long>>, nowMs: Long): Double? {
        val ids = active.mapTo(mutableSetOf()) { it.first }
        lastBytes.keys.retainAll(ids)
        lastAtMs.keys.retainAll(ids)
        var accepted: Double? = null
        for ((id, bytes) in active) {
            val previousBytes = lastBytes[id]
            val previousAt = lastAtMs[id]
            lastBytes[id] = bytes
            lastAtMs[id] = nowMs
            if (previousBytes == null || previousAt == null || bytes < previousBytes) continue
            accepted = ema.sample(bytes - previousBytes, nowMs - previousAt) ?: accepted
        }
        return accepted
    }
}
