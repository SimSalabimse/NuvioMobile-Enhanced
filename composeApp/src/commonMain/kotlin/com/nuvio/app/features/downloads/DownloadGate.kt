package com.nuvio.app.features.downloads

import com.nuvio.app.features.analysis.AnalysisThermal
import com.nuvio.app.features.analysis.analysisPauseReason

/**
 * What the download queue may do.
 * [Hold] starts nothing. [Throttle] keeps one transfer, at low priority, and starts no automatic
 * download. [Run] is the normal two-at-a-time queue.
 * Heat and Low Power Mode use [analysisPauseReason]. Playback uses that same "playback" reason
 * as a throttle, not a second detector.
 */
internal enum class DownloadGateState { Run, Throttle, Hold }

internal data class DownloadGateDecision(
    val state: DownloadGateState,
    val reason: String,
    val maxActive: Int,
    val allowNewAuto: Boolean,
    val lowPriority: Boolean,
)

internal fun downloadGateDecision(
    videoPlaying: Boolean,
    strained: Boolean,
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
    charging: Boolean,
    onWifi: Boolean,
    pauseWhileWatching: Boolean,
): DownloadGateDecision {
    val power = analysisPauseReason(videoPlaying = false, thermal, lowPowerMode)
    if (power != null) {
        return hold(power)
    }
    if (!videoPlaying) {
        return run(if (strained) "paused" else "clear")
    }
    if (strained) return hold("playback-strained")
    if (pauseWhileWatching) return hold("playback")
    if (charging && onWifi && thermal == AnalysisThermal.Nominal) {
        return run("charging-wifi")
    }
    return DownloadGateDecision(
        state = DownloadGateState.Throttle,
        reason = "playback",
        maxActive = 1,
        allowNewAuto = false,
        lowPriority = true,
    )
}

private fun hold(reason: String) = DownloadGateDecision(
    state = DownloadGateState.Hold,
    reason = reason,
    maxActive = 0,
    allowNewAuto = false,
    lowPriority = false,
)

private fun run(reason: String) = DownloadGateDecision(
    state = DownloadGateState.Run,
    reason = reason,
    maxActive = MAX_ACTIVE_DOWNLOADS,
    allowNewAuto = true,
    lowPriority = false,
)

/** Tokens only. No URLs, paths, or titles. */
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

internal expect object DownloadTransferPriority {
    fun setLowPriority(low: Boolean)
}
