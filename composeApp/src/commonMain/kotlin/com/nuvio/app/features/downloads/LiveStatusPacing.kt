package com.nuvio.app.features.downloads

import kotlin.math.exp

/**
 * Smoothed transfer speed for the Live Activity, an exponentially weighted average with a time
 * constant of about 12 s, so the last 10–20 s count most. Samples closer than [minSampleMs] are
 * skipped. When the summed bytes drop (an episode finished and a new one joined) the baseline
 * restarts but the speed is kept. A new activity [key] starts from scratch.
 */
internal class TransferSpeedEstimator(
    private val tauMs: Double = 12_000.0,
    private val minSampleMs: Long = 500L,
) {
    private var key: String? = null
    private var lastBytes = 0L
    private var lastAtMs = 0L
    private var speed: Double? = null

    /** Bytes per second, or null until there are two samples. */
    fun sample(key: String?, bytes: Long, nowMs: Long): Double? {
        if (key == null) {
            this.key = null
            speed = null
            return null
        }
        if (key != this.key) {
            this.key = key
            lastBytes = bytes
            lastAtMs = nowMs
            speed = null
            return null
        }
        val dt = nowMs - lastAtMs
        if (dt < minSampleMs) return speed
        if (bytes < lastBytes) {
            lastBytes = bytes
            lastAtMs = nowMs
            return speed
        }
        val instant = (bytes - lastBytes) * 1000.0 / dt
        val alpha = 1.0 - exp(-dt / tauMs)
        speed = speed?.let { it + alpha * (instant - it) } ?: instant
        lastBytes = bytes
        lastAtMs = nowMs
        return speed
    }
}

/**
 * At most one Live Activity update per [intervalMs]; a change of activity, status, episode set
 * or known size goes out at once. The caller always keeps the latest state and flushes it when
 * the returned wait has passed, so the final state is never dropped.
 */
internal class LiveStatusThrottle(private val intervalMs: Long = 1_000L) {
    private var sentAny = false
    private var lastKey: String? = null
    private var lastSentAtMs = 0L

    /** 0 to send now, otherwise how long to wait before sending the latest state. */
    fun waitBeforeSend(structuralKey: String?, nowMs: Long): Long {
        if (!sentAny || structuralKey != lastKey) return 0L
        val since = nowMs - lastSentAtMs
        return if (since >= intervalMs || since < 0L) 0L else intervalMs - since
    }

    fun markSent(structuralKey: String?, nowMs: Long) {
        sentAny = true
        lastKey = structuralKey
        lastSentAtMs = nowMs
    }
}
