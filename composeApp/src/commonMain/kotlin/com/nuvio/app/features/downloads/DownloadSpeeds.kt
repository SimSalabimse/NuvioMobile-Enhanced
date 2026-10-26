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

/** A downloading episode's current speed and bytes still to fetch. Either may be unknown. */
internal data class RunningEpisodePace(
    val bytesPerSecond: Double?,
    val remainingBytes: Long?,
)

/**
 * Seconds until a season's running and queued episodes should finish, or null when that time
 * cannot be known (no speed yet, or any of those episodes has an unknown size).
 *
 * Parallel episodes keep their own speeds, so the season cannot finish before the slowest one
 * that is already moving. Queued bytes are added on top of the combined speed and can only make
 * this longer. A running episode with no speed yet is left out of the "slowest episode" term;
 * its bytes still count in the total when its size is known.
 */
internal fun seasonEtaSeconds(
    running: List<RunningEpisodePace>,
    queuedRemainingBytes: List<Long?> = emptyList(),
): Long? {
    val pendingRemaining = running.map { it.remainingBytes } + queuedRemainingBytes
    if (pendingRemaining.isEmpty() || pendingRemaining.any { it == null }) return null
    val summedSpeed = running.sumOf { pace ->
        pace.bytesPerSecond?.takeIf { it > 0.0 } ?: 0.0
    }
    if (summedSpeed <= 0.0) return null
    val totalRemaining = pendingRemaining.sumOf { it ?: 0L }
    val atCombinedSpeed = (totalRemaining / summedSpeed).toLong()
    val slowestRunning = running.mapNotNull { pace ->
        val speed = pace.bytesPerSecond?.takeIf { it > 0.0 } ?: return@mapNotNull null
        val remaining = pace.remainingBytes ?: return@mapNotNull null
        (remaining / speed).toLong()
    }.maxOrNull()
    return maxOf(slowestRunning ?: 0L, atCombinedSpeed)
}

internal fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "<1 min"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 24 * 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    else -> "${seconds / 86400} d"
}
