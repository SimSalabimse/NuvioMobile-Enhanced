package com.nuvio.app.features.downloads

import com.nuvio.app.features.analysis.AnalysisThermal

/**
 * Two download lanes share one queue.
 * Next-up is only the episode that follows the one playing. Everything else waits while a video plays.
 * URLSession has no byte-rate cap. [NEXT_UP_DUTY_CYCLE_DEFAULT] is off: one low-priority transfer
 * is the throttle, and a 30/30 cycle would fight finishing the file before the current episode ends.
 * Serious heat still uses the cycle, because "slowest" has no other control.
 */
internal const val NEXT_UP_DUTY_CYCLE_DEFAULT = false
internal const val NEXT_UP_DUTY_ON_MS = 30_000L
internal const val NEXT_UP_DUTY_OFF_MS = 30_000L
internal const val NEXT_UP_DEADLINE_LEAD_MS = 10 * 60_000L
internal const val NEXT_UP_DEADLINE_CHECK_MS = 60_000L

internal enum class DownloadGateState { Run, Throttle, Hold }

internal enum class DownloadLane { NextUp, Other }

internal enum class TransferPace { Normal, Low, Slowest, Stopped }

internal data class LaneDecision(
    val lane: DownloadLane,
    val state: DownloadGateState,
    val pace: TransferPace,
    val allowStart: Boolean,
    val reason: String,
)

internal data class DownloadGateDecision(
    val state: DownloadGateState,
    val reason: String,
    val maxActive: Int,
    val allowNewAuto: Boolean,
    val lowPriority: Boolean,
    val nextUp: LaneDecision,
    val other: LaneDecision,
    val dutyCycle: Boolean,
)

internal fun nextUpLaneDecision(
    videoPlaying: Boolean,
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
    charging: Boolean,
    onWifi: Boolean,
    behindDeadline: Boolean,
    pauseWhileWatching: Boolean,
): LaneDecision {
    if (!videoPlaying) {
        return lane(DownloadLane.NextUp, DownloadGateState.Run, TransferPace.Normal, true, "paused")
    }
    if (pauseWhileWatching) {
        return lane(DownloadLane.NextUp, DownloadGateState.Hold, TransferPace.Stopped, false, "playback")
    }
    if (thermal == AnalysisThermal.Critical) {
        return lane(DownloadLane.NextUp, DownloadGateState.Hold, TransferPace.Stopped, false, "thermal-critical")
    }
    if (charging && onWifi && thermal == AnalysisThermal.Nominal) {
        return lane(DownloadLane.NextUp, DownloadGateState.Run, TransferPace.Normal, true, "charging-wifi")
    }
    if (thermal == AnalysisThermal.Serious) {
        return lane(DownloadLane.NextUp, DownloadGateState.Throttle, TransferPace.Slowest, true, "thermal-serious")
    }
    if (behindDeadline) {
        return lane(DownloadLane.NextUp, DownloadGateState.Run, TransferPace.Normal, true, "deadline")
    }
    val reason = if (lowPowerMode) "low-power" else "playback"
    return lane(DownloadLane.NextUp, DownloadGateState.Throttle, TransferPace.Low, true, reason)
}

internal fun otherLaneDecision(
    videoPlaying: Boolean,
    pauseWhileWatching: Boolean,
): LaneDecision {
    if (videoPlaying || pauseWhileWatching && videoPlaying) {
        return lane(DownloadLane.Other, DownloadGateState.Hold, TransferPace.Stopped, false, "playback")
    }
    return lane(DownloadLane.Other, DownloadGateState.Run, TransferPace.Normal, true, "clear")
}

internal fun playbackDownloadGate(
    videoPlaying: Boolean,
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
    charging: Boolean,
    onWifi: Boolean,
    behindDeadline: Boolean,
    pauseWhileWatching: Boolean,
    dutyCycleDefault: Boolean = NEXT_UP_DUTY_CYCLE_DEFAULT,
): DownloadGateDecision {
    val nextUp = nextUpLaneDecision(
        videoPlaying, thermal, lowPowerMode, charging, onWifi, behindDeadline, pauseWhileWatching,
    )
    val other = if (videoPlaying) {
        otherLaneDecision(videoPlaying, pauseWhileWatching)
    } else {
        lane(DownloadLane.Other, DownloadGateState.Run, TransferPace.Normal, true, "clear")
    }
    val maxActive = when {
        !videoPlaying -> MAX_ACTIVE_DOWNLOADS
        nextUp.allowStart -> 1
        else -> 0
    }
    val duty = dutyCycleActive(nextUp.pace, dutyCycleDefault)
    return DownloadGateDecision(
        state = if (videoPlaying) nextUp.state else other.state,
        reason = if (videoPlaying) nextUp.reason else other.reason,
        maxActive = maxActive,
        allowNewAuto = other.allowStart,
        lowPriority = nextUp.pace == TransferPace.Low || nextUp.pace == TransferPace.Slowest,
        nextUp = nextUp,
        other = other,
        dutyCycle = duty,
    )
}

/** True when this instant of a 30s/30s cycle should be transferring. */
internal fun dutyCycleTransferring(
    elapsedMs: Long,
    onMs: Long = NEXT_UP_DUTY_ON_MS,
    offMs: Long = NEXT_UP_DUTY_OFF_MS,
): Boolean {
    val period = (onMs + offMs).coerceAtLeast(1L)
    val at = elapsedMs.mod(period)
    return at < onMs
}

internal fun dutyCycleActive(pace: TransferPace, defaultEnabled: Boolean): Boolean =
    pace == TransferPace.Slowest || (pace == TransferPace.Low && defaultEnabled)

/**
 * ETA is bytes left divided by the measured rate.
 * Raise priority when that ETA is later than the time left in the episode minus 10 minutes.
 * A non-positive rate does not raise priority.
 */
internal fun nextUpBehindDeadline(
    bytesLeft: Long,
    bytesPerSecond: Long,
    episodeRemainingMs: Long,
    leadMs: Long = NEXT_UP_DEADLINE_LEAD_MS,
): Boolean {
    if (bytesLeft <= 0L) return false
    if (bytesPerSecond <= 0L) return false
    val etaMs = bytesLeft * 1000L / bytesPerSecond
    return etaMs > episodeRemainingMs - leadMs
}

internal fun nextUpEtaSeconds(bytesLeft: Long, bytesPerSecond: Long): Long {
    if (bytesLeft <= 0L || bytesPerSecond <= 0L) return -1L
    return bytesLeft / bytesPerSecond
}

/** Tokens only. No titles, paths, or plain ids. */
internal fun nextUpLine(
    idHash: String,
    state: String,
    etaSeconds: Long,
    remainingSeconds: Long,
    bytesPerSecond: Long,
): String {
    val id = idHash.takeIf { it.length == 12 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } } ?: "-"
    return "next-up id=$id lane=priority state=$state eta=${etaSeconds}s remaining=${remainingSeconds}s rate=$bytesPerSecond"
}

internal fun downloadGateLine(
    gate: DownloadGateDecision,
    active: Int,
    queued: Int,
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
): String {
    val state = gate.state.name.lowercase()
    val lowPower = if (lowPowerMode) "true" else "false"
    return "download gate state=$state reason=${gate.reason} active=$active queued=$queued " +
        "thermal=${thermal.name.lowercase()} lowPower=$lowPower"
}

private fun lane(
    lane: DownloadLane,
    state: DownloadGateState,
    pace: TransferPace,
    allowStart: Boolean,
    reason: String,
) = LaneDecision(lane, state, pace, allowStart, reason)

internal expect object DownloadTransferPriority {
    fun setLowPriority(low: Boolean)
}
