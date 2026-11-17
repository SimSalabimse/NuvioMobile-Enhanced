package com.nuvio.app.features.analysis

/**
 * Why on-device intro, segment, and learning analysis must not run.
 * Playback wins while a video is actually playing. Heat and Low Power Mode still hold
 * after playback stops, until the phone is idle and cool.
 */
internal enum class AnalysisThermal {
    Nominal,
    Fair,
    Serious,
    Critical,
}

internal data class AnalysisPowerSnapshot(
    val thermal: AnalysisThermal,
    val lowPowerMode: Boolean,
)

internal fun analysisPauseReason(
    videoPlaying: Boolean,
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
    transferActive: Boolean = false,
): String? = when {
    videoPlaying -> "playback"
    thermal == AnalysisThermal.Serious -> "thermal-serious"
    thermal == AnalysisThermal.Critical -> "thermal-critical"
    lowPowerMode -> "low-power"
    transferActive && thermal != AnalysisThermal.Nominal -> "download"
    else -> null
}

/** Job state for a pause reason. Heat, downloading, and Low Power are not playback. */
internal fun analysisWaitJob(reason: String): AnalysisJobState = when (reason) {
    "download" -> AnalysisJobState.WaitingWhileDownloading
    "thermal-serious", "thermal-critical" -> AnalysisJobState.WaitingForHeat
    "low-power" -> AnalysisJobState.WaitingForLowPower
    else -> AnalysisJobState.WaitingForPlayback
}

/** The settings row for this hold. Heat is not worded as playback. */
internal fun analysisHoldMessage(reason: String): String = when (reason) {
    "thermal-serious", "thermal-critical" ->
        "Paused while the phone is hot. It continues when it cools down."
    "download" -> "Waiting while the phone is warm and downloading."
    "low-power" -> "Paused while Low Power Mode is on. It continues when you turn it off."
    "playback", "playback-strained" ->
        "Paused while something is playing. It continues when you stop watching."
    else -> "Paused while the phone is hot. It continues when it cools down."
}

/** Live heat. A stored throttle of 0 does not allow a start while this is false. */
internal fun analysisMayStart(
    videoPlaying: Boolean,
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
    transferActive: Boolean = false,
): Boolean = analysisPauseReason(videoPlaying, thermal, lowPowerMode, transferActive) == null

/** Same job and reason. qos and thermal flicker do not count as a new stop. */
internal fun sameAnalysisStop(previous: String?, line: String): Boolean {
    if (previous == null || !line.startsWith("analysis stop") || !previous.startsWith("analysis stop")) return false
    return analysisStopIdentity(line) == analysisStopIdentity(previous)
}

private fun analysisStopIdentity(line: String): String {
    val job = line.substringAfter("job=", "").substringBefore(" ")
    val reason = line.substringAfter("reason=", "").substringBefore(" ")
    return "$job|$reason"
}

/** 0 runs. 1 pauses for playback. 2 pauses because playback is strained. 3 pauses for heat or Low Power Mode. */
internal fun analysisThrottleLevel(
    level: com.nuvio.app.features.downloads.PlaybackPriority.Level,
    videoPlaying: Boolean,
    thermal: AnalysisThermal = AnalysisThermal.Nominal,
    lowPowerMode: Boolean = false,
    transferActive: Boolean = false,
): Int {
    if (videoPlaying) {
        return if (level == com.nuvio.app.features.downloads.PlaybackPriority.Level.Strained) 2 else 1
    }
    return if (analysisPauseReason(videoPlaying = false, thermal, lowPowerMode, transferActive) != null) 3 else 0
}

/** Utility while a player is open, analysis is held, or any download is transferring. */
internal fun analysisQosLabel(
    playerSessionOpen: Boolean,
    paused: Boolean,
    transferActive: Boolean = false,
): String = if (playerSessionOpen || paused || transferActive) "utility" else "userInitiated"

/**
 * One diagnostics line. Tokens only: no URLs, paths, or secrets.
 * [event] is start, stop, or thermal.
 */
internal fun analysisDiagnosticsLine(
    event: String,
    job: String,
    reason: String,
    qos: String,
    power: AnalysisPowerSnapshot,
): String {
    val thermal = power.thermal.name.lowercase()
    val lowPower = if (power.lowPowerMode) "true" else "false"
    return "analysis ${token(event)} job=${token(job)} reason=${token(reason)} qos=${token(qos)} " +
        "thermal=$thermal lowPower=$lowPower"
}

private fun token(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > 64) return "-"
    if (trimmed.any { it.isWhitespace() || it == '/' || it == '\\' || it == '?' || it == '&' || it == '@' }) return "-"
    if (!trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':' }) return "-"
    return trimmed
}
