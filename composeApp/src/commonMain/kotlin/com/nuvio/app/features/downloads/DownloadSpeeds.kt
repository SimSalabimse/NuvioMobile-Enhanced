package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Smoothed speed per running download for the Downloads page, fed from the same updates and with
 * the same estimator ([TransferSpeedEstimator]) as the Live Activity. The average is linear, so the
 * sum of a season's per-episode speeds equals the Live Activity's speed over their summed bytes.
 */
internal object DownloadSpeeds {
    private val estimators = mutableMapOf<String, TransferSpeedEstimator>()
    private val state = MutableStateFlow<Map<String, Double>>(emptyMap())
    val speeds: StateFlow<Map<String, Double>> = state.asStateFlow()

    fun onItems(items: List<DownloadItem>, nowMs: Long) {
        val running = items.filter { it.status == DownloadStatus.Downloading }
        val ids = running.mapTo(mutableSetOf()) { it.id }
        estimators.keys.retainAll(ids)
        val next = mutableMapOf<String, Double>()
        running.forEach { item ->
            val estimator = estimators.getOrPut(item.id) { TransferSpeedEstimator() }
            estimator.sample(item.id, item.downloadedBytes, nowMs)?.let { next[item.id] = it }
        }
        if (next != state.value) state.value = next
    }
}

internal fun DownloadItem.remainingBytesOrNull(): Long? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    return (total - downloadedBytes.coerceIn(0L, total)).coerceAtLeast(0L)
}

/** "2.4 MB/s · 6 min left", or null without a speed yet. */
internal fun speedAndEta(bytesPerSecond: Double?, remainingBytes: Long?): String? {
    val speed = bytesPerSecond?.takeIf { it >= 1024.0 } ?: return null
    val parts = mutableListOf("${formatDownloadBytes(speed.toLong())}/s")
    if (remainingBytes != null) parts += "${formatEta((remainingBytes / speed).toLong())} left"
    return parts.joinToString(" · ")
}

internal fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "<1 min"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 24 * 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    else -> "${seconds / 86400} d"
}
