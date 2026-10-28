package com.nuvio.app.features.downloads

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Why a download row is not moving, when the app can see the cause.
 * [downloadSlowReason] is the only place that picks one.
 */
internal enum class DownloadSlowReason {
    PausedWhileWatching,
    ThrottledWhileBuffering,
    WaitingInQueue,
    WaitingForWifi,
    DeviceTooHot,
    Retrying,
}

/**
 * Facts the row already has. Nothing here reads the device: callers pass what they observed.
 * [pauseWhileWatching] is the setting. [playerActive] is a stream open past the idle level.
 * [bufferingThrottle] is the existing buffer throttle (downloads held while the stream buffers).
 * [activeAhead] is how many downloads are already running. [retryCount] is the manager's count.
 */
internal data class DownloadSlowSignals(
    val status: DownloadStatus,
    val pauseWhileWatching: Boolean,
    val playerActive: Boolean,
    val heldForPlayback: Boolean,
    val bufferingThrottle: Boolean,
    val activeAhead: Int,
    val onlyOnWifi: Boolean,
    val onWifi: Boolean,
    val thermalBlocks: Boolean,
    val retryCount: Int,
)

/**
 * One reason, or null when the download is healthy or the app cannot see why it is waiting.
 * Order: paused for a stream, buffer throttle, queue, Wi-Fi, heat, retry.
 * A transfer that is still downloading is healthy unless it is on a retry that has not moved.
 */
internal fun downloadSlowReason(signals: DownloadSlowSignals): DownloadSlowReason? {
    val waiting = signals.status == DownloadStatus.Queued
    val downloading = signals.status == DownloadStatus.Downloading
    if (!waiting && !downloading) return null
    if (waiting && ((signals.pauseWhileWatching && signals.playerActive) || signals.heldForPlayback)) {
        return DownloadSlowReason.PausedWhileWatching
    }
    if (waiting && signals.bufferingThrottle) return DownloadSlowReason.ThrottledWhileBuffering
    if (waiting && signals.activeAhead > 0) return DownloadSlowReason.WaitingInQueue
    if (waiting && signals.onlyOnWifi && !signals.onWifi) return DownloadSlowReason.WaitingForWifi
    if (waiting && signals.thermalBlocks) return DownloadSlowReason.DeviceTooHot
    if (signals.retryCount > 0) return DownloadSlowReason.Retrying
    return null
}

/** Bumped about every 2 s while Downloads is showing active rows, so a reason tracks the device. */
internal val LocalDownloadSlowTick = staticCompositionLocalOf { 0 }

private const val SLOW_REASON_REFRESH_MS = 2_000L

/** Refreshes [LocalDownloadSlowTick] while [active] is true. Returns the current tick. */
@Composable
internal fun rememberDownloadSlowTick(active: Boolean): Int {
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(SLOW_REASON_REFRESH_MS)
            tick.intValue++
        }
    }
    return tick.intValue
}

/**
 * One short line for [items], or nothing.
 * A season header passes every episode in the group and only speaks when they share one reason.
 */
@Composable
internal fun DownloadSlowReasonLine(items: List<DownloadItem>) {
    val interesting = items.any { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }
    if (!interesting) return
    val smart by SmartDownloadSettings.state.collectAsState()
    val downloads by DownloadsRepository.uiState.collectAsState()
    val tick = LocalDownloadSlowTick.current
    val activeAhead = downloads.items.count { it.status == DownloadStatus.Downloading }
    val held = downloads.heldForPlayback
    val signature = remember(items, held) {
        items.joinToString { "${it.id}:${it.status}:${it.id in held}" }
    }
    val reasons = remember(
        signature,
        smart.pauseDuringPlayback,
        smart.onlyOnWifi,
        activeAhead,
        tick,
    ) {
        items.mapNotNull { item ->
            downloadSlowReason(
                DownloadSlowSignals(
                    status = item.status,
                    pauseWhileWatching = smart.pauseDuringPlayback,
                    playerActive = PlaybackPriority.level != PlaybackPriority.Level.Normal,
                    heldForPlayback = item.id in held,
                    bufferingThrottle = PlaybackPriority.level == PlaybackPriority.Level.Strained,
                    activeAhead = activeAhead,
                    onlyOnWifi = smart.onlyOnWifi,
                    onWifi = DownloadNetworkGuard.isOnWifi(),
                    thermalBlocks = DownloadThermalGate.blocksNextFile(),
                    retryCount = DownloadsPlatformDownloader.retryCount(item.id),
                ),
            )
        }.toSet()
    }
    val reason = reasons.singleOrNull() ?: return
    Text(
        text = slowReasonText(reason, activeAhead),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun slowReasonText(reason: DownloadSlowReason, activeAhead: Int): String = when (reason) {
    DownloadSlowReason.PausedWhileWatching -> stringResource(Res.string.downloads_slow_paused_watching)
    DownloadSlowReason.ThrottledWhileBuffering -> stringResource(Res.string.downloads_slow_buffering)
    DownloadSlowReason.WaitingInQueue -> pluralStringResource(
        Res.plurals.downloads_slow_queue,
        activeAhead.coerceAtLeast(1),
        activeAhead.coerceAtLeast(1),
    )
    DownloadSlowReason.WaitingForWifi -> stringResource(Res.string.downloads_slow_wifi)
    DownloadSlowReason.DeviceTooHot -> stringResource(Res.string.downloads_slow_thermal)
    DownloadSlowReason.Retrying -> stringResource(Res.string.downloads_slow_retrying)
}
