package com.nuvio.app.features.downloads

import com.nuvio.app.features.analysis.AnalysisThermal

/**
 * Two download lanes share one queue.
 * Next-up is only the episode that follows the one playing. Everything else waits while a video plays.
 * URLSession has no byte-rate cap. [NEXT_UP_DUTY_CYCLE_DEFAULT] is off: one low-priority transfer
 * is the throttle. Serious heat during playback does not use the 30/30 cycle. That cycle left the
 * transfer running in the off window and restarted it over and over. The next episode waits, then
 * one low-priority transfer runs until the file is done.
 */
internal const val NEXT_UP_DUTY_CYCLE_DEFAULT = false
internal const val NEXT_UP_DUTY_ON_MS = 30_000L
internal const val NEXT_UP_DUTY_OFF_MS = 30_000L
internal const val NEXT_UP_DEADLINE_LEAD_MS = 10 * 60_000L
internal const val NEXT_UP_DEADLINE_CHECK_MS = 60_000L
/** Room for the next episode to be analysed before it starts. */
internal const val NEXT_UP_ANALYSIS_RESERVE_MS = 3 * 60_000L
/** Start once the finish would land inside this lead of the current episode's end. */
internal const val NEXT_UP_JUST_IN_TIME_LEAD_MS = 15 * 60_000L
/** Conservative rate. A faster measurement is not trusted. */
internal const val NEXT_UP_CONSERVATIVE_FLOOR_BPS = 2L * 1024L * 1024L

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

/**
 * Nothing is playing.
 * Nominal keeps two transfers at normal priority. Fair keeps two at low priority.
 * Serious keeps one at low priority. Critical holds every transfer.
 * Low Power Mode keeps one transfer.
 */
internal fun idleLaneDecision(
    laneName: DownloadLane,
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
    coolReason: String,
): LaneDecision {
    if (thermal == AnalysisThermal.Critical) {
        return lane(laneName, DownloadGateState.Hold, TransferPace.Stopped, false, "thermal-critical")
    }
    if (thermal == AnalysisThermal.Serious) {
        return lane(laneName, DownloadGateState.Throttle, TransferPace.Low, true, "thermal-serious")
    }
    if (thermal == AnalysisThermal.Fair) {
        return lane(laneName, DownloadGateState.Throttle, TransferPace.Low, true, "thermal-fair")
    }
    if (lowPowerMode) {
        return lane(laneName, DownloadGateState.Throttle, TransferPace.Low, true, "low-power")
    }
    return lane(laneName, DownloadGateState.Run, TransferPace.Normal, true, coolReason)
}

/** Two at nominal or fair, one at serious or Low Power Mode, none at critical. */
internal fun idleMaxActive(thermal: AnalysisThermal, lowPowerMode: Boolean): Int = when {
    thermal == AnalysisThermal.Critical -> 0
    thermal == AnalysisThermal.Serious || lowPowerMode -> 1
    else -> MAX_ACTIVE_DOWNLOADS
}

/**
 * Serious or critical heat does not hand files to the background session.
 * Running transfers are suspended when the app goes to the background hot.
 */
internal fun backgroundSessionHolds(thermal: AnalysisThermal): Boolean =
    thermal == AnalysisThermal.Serious || thermal == AnalysisThermal.Critical

/** Critical heat starts nothing. Serious heat still allows the one low-priority slot. */
internal fun thermalBlocksNextFile(thermal: AnalysisThermal): Boolean =
    thermal == AnalysisThermal.Critical

internal fun nextUpLaneDecision(
    videoPlaying: Boolean,
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
    charging: Boolean,
    onWifi: Boolean,
    behindDeadline: Boolean,
    pauseWhileWatching: Boolean,
    justInTime: Boolean = false,
): LaneDecision {
    if (!videoPlaying) {
        return idleLaneDecision(DownloadLane.NextUp, thermal, lowPowerMode, "paused")
    }
    if (pauseWhileWatching) {
        return lane(DownloadLane.NextUp, DownloadGateState.Hold, TransferPace.Stopped, false, "playback")
    }
    // Critical holds. The deadline does not start a transfer while the phone is critical.
    if (thermal == AnalysisThermal.Critical) {
        return lane(DownloadLane.NextUp, DownloadGateState.Hold, TransferPace.Stopped, false, "thermal-critical")
    }
    if (charging && onWifi && thermal == AnalysisThermal.Nominal) {
        return lane(DownloadLane.NextUp, DownloadGateState.Run, TransferPace.Normal, true, "charging-wifi")
    }
    if (thermal == AnalysisThermal.Serious) {
        if (justInTime) {
            return lane(DownloadLane.NextUp, DownloadGateState.Throttle, TransferPace.Low, true, "deadline")
        }
        return lane(DownloadLane.NextUp, DownloadGateState.Hold, TransferPace.Stopped, false, "thermal-serious")
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
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
): LaneDecision {
    if (videoPlaying || pauseWhileWatching && videoPlaying) {
        return lane(DownloadLane.Other, DownloadGateState.Hold, TransferPace.Stopped, false, "playback")
    }
    return idleLaneDecision(DownloadLane.Other, thermal, lowPowerMode, "clear")
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
    justInTime: Boolean = false,
): DownloadGateDecision {
    val nextUp = nextUpLaneDecision(
        videoPlaying, thermal, lowPowerMode, charging, onWifi, behindDeadline, pauseWhileWatching, justInTime,
    )
    val other = otherLaneDecision(videoPlaying, pauseWhileWatching, thermal, lowPowerMode)
    val maxActive = if (videoPlaying) {
        if (nextUp.allowStart) 1 else 0
    } else {
        idleMaxActive(thermal, lowPowerMode)
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
 * ETA is bytes left divided by the measured rate, in milliseconds.
 * A negative rate has not been measured yet and does not raise priority.
 * A measured zero is an infinite ETA, so the file is behind.
 * A product that does not fit in a millisecond count is behind as well.
 */
/**
 * The lower of the measured rate and 2 MiB/s.
 * No sample yet uses the floor. A measured zero cannot finish in time.
 */
internal fun nextUpConservativeRate(measuredBytesPerSecond: Long): Long = when {
    measuredBytesPerSecond < 0L -> NEXT_UP_CONSERVATIVE_FLOOR_BPS
    measuredBytesPerSecond == 0L -> 0L
    else -> minOf(measuredBytesPerSecond, NEXT_UP_CONSERVATIVE_FLOOR_BPS)
}

/**
 * True when the projected finish, plus about 3 minutes of analysis, would land within
 * 15 minutes of the end. The rate is [nextUpConservativeRate].
 */
internal fun nextUpJustInTime(
    bytesLeft: Long,
    bytesPerSecond: Long,
    episodeRemainingMs: Long,
    analysisReserveMs: Long = NEXT_UP_ANALYSIS_RESERVE_MS,
    leadMs: Long = NEXT_UP_JUST_IN_TIME_LEAD_MS,
): Boolean {
    if (bytesLeft <= 0L) return false
    val rate = nextUpConservativeRate(bytesPerSecond)
    if (rate == 0L) return true
    if (bytesLeft > Long.MAX_VALUE / 1000L) return true
    val etaMs = bytesLeft * 1000L / rate
    return etaMs + analysisReserveMs >= episodeRemainingMs - leadMs
}

/** Bytes accepted while the cap is 0. A suspended window moves none. */
internal fun offWindowBytes(cap: Int, reportedDelta: Long): Long =
    if (cap <= 0) 0L else reportedDelta.coerceAtLeast(0L)

internal fun transferBytesPerSecond(
    previousBytes: Long,
    previousMs: Long,
    bytes: Long,
    nowMs: Long,
): Long {
    if (previousBytes < 0L || previousMs <= 0L || nowMs <= previousMs) return 0L
    val delta = bytes - previousBytes
    if (delta <= 0L) return 0L
    return delta * 1000L / (nowMs - previousMs)
}

/** Device error log and behaviour log. Tokens only. */
internal fun transferActivityLine(
    where: String,
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
    active: Int,
    bytesPerSecond: Long,
): String {
    val place = where.trim().lowercase().filter { it.isLetter() }.take(16).ifBlank { "app" }
    val lowPower = if (lowPowerMode) "true" else "false"
    val count = active.coerceAtLeast(0)
    val rate = bytesPerSecond.coerceAtLeast(0L)
    return "transfers where=$place thermal=${thermal.name.lowercase()} lowPower=$lowPower active=$count rate=$rate"
}

internal fun nextUpBehindDeadline(
    bytesLeft: Long,
    bytesPerSecond: Long,
    episodeRemainingMs: Long,
    leadMs: Long = NEXT_UP_DEADLINE_LEAD_MS,
): Boolean {
    if (bytesLeft <= 0L) return false
    if (bytesPerSecond < 0L) return false
    if (bytesPerSecond == 0L) return true
    if (bytesLeft > Long.MAX_VALUE / 1000L) return true
    val etaMs = bytesLeft * 1000L / bytesPerSecond
    return etaMs > episodeRemainingMs - leadMs
}

/**
 * Bytes still to fetch. An unknown length keeps a full episode guess ahead of the playhead
 * so a missing Content-Length cannot sit at low priority for the whole episode.
 */
internal fun nextUpBytesLeft(totalBytes: Long?, downloadedBytes: Long): Long {
    val downloaded = downloadedBytes.coerceAtLeast(0L)
    val known = totalBytes?.takeIf { it > 0L }
    if (known != null) return (known - downloaded).coerceAtLeast(0L)
    if (downloaded > Long.MAX_VALUE - NEXT_EPISODE_SIZE_WHEN_UNKNOWN_BYTES) {
        return NEXT_EPISODE_SIZE_WHEN_UNKNOWN_BYTES
    }
    return NEXT_EPISODE_SIZE_WHEN_UNKNOWN_BYTES
}

/** Bytes per second, or -1 before the first sample. Zero means the window moved no bytes. */
internal fun nextUpMeasuredRate(downloadedBytes: Long, baselineBytes: Long, elapsedMs: Long): Long {
    if (baselineBytes < 0L || elapsedMs <= 0L) return -1L
    val delta = downloadedBytes - baselineBytes
    if (delta <= 0L) return 0L
    if (delta > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE / 1000L
    return delta * 1000L / elapsedMs
}

/**
 * The minute timer, plus a seek or episode change that moves the end closer than playback explains.
 * [lastRemainingMs] below zero means this episode has not been sampled yet.
 */
internal fun deadlineRecheckDue(
    nowMs: Long,
    lastCheckMs: Long,
    episodeRemainingMs: Long,
    lastRemainingMs: Long,
    intervalMs: Long = NEXT_UP_DEADLINE_CHECK_MS,
): Boolean {
    if (lastCheckMs <= 0L || nowMs - lastCheckMs >= intervalMs) return true
    if (lastRemainingMs < 0L) return true
    val wall = (nowMs - lastCheckMs).coerceAtLeast(0L)
    return lastRemainingMs - episodeRemainingMs > wall + 2_000L
}

/** NSURLSession task priority. 0.25 is low. 0.5 is the session default. */
internal fun transferTaskPriority(low: Boolean): Float = if (low) 0.25f else 0.5f

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
