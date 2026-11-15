package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.diagnostics.DiagnosticsLogger
import com.nuvio.app.features.downloads.DownloadsRepository

/** First few minutes: the intro and recap window, not the 25-minute fingerprint. */
internal const val INTRO_WINDOW_MS = 5 * 60_000L

internal fun MediaAnalysisResult?.hasOnDeviceMarks(): Boolean {
    if (this == null) return false
    if (introStartMs != null && introEndMs != null && introEndMs > introStartMs) return true
    if (recapStartMs != null && recapEndMs != null && recapEndMs > recapStartMs) return true
    return userSegments.keys.any { it == "intro" || it == "recap" }
}

/**
 * A light pass of the next downloaded episode, only at the next-episode threshold,
 * and only while the phone is cool and not in Low Power Mode.
 */
internal fun shouldRunIntroWindow(
    atNextEpisodeThreshold: Boolean,
    nextEpisodeDownloaded: Boolean,
    hasOnDeviceMarks: Boolean,
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
): Boolean {
    if (!atNextEpisodeThreshold || !nextEpisodeDownloaded || hasOnDeviceMarks) return false
    if (lowPowerMode) return false
    return thermal == AnalysisThermal.Nominal || thermal == AnalysisThermal.Fair
}

/** The light pass stops if the phone heats up or Low Power Mode switches on. */
internal fun introWindowShouldAbort(thermal: AnalysisThermal, lowPowerMode: Boolean): Boolean {
    if (lowPowerMode) return true
    return thermal == AnalysisThermal.Serious || thermal == AnalysisThermal.Critical
}

internal fun offerNextEpisodeIntroWindow(
    parentMetaId: String,
    season: Int?,
    episode: Int?,
    videoId: String?,
    atThreshold: Boolean,
) {
    if (!atThreshold || parentMetaId.isBlank()) return
    val item = DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = season,
        episodeNumber = episode,
        videoId = videoId,
    ) ?: return
    val stored = runCatching { MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item)) }.getOrNull()
    val power = AnalysisPower.snapshot()
    val allowed = shouldRunIntroWindow(
        atNextEpisodeThreshold = true,
        nextEpisodeDownloaded = true,
        hasOnDeviceMarks = stored.hasOnDeviceMarks(),
        thermal = power.thermal,
        lowPowerMode = power.lowPowerMode,
    )
    if (!allowed) {
        logIntroWindow(item.id, if (stored.hasOnDeviceMarks()) "skip-marks" else "skip-power")
        return
    }
    val uri = DownloadsRepository.playableLocalFileUri(item) ?: return
    MediaAnalysisScheduler.offerIntroWindow(item.id, uri)
}

private fun logIntroWindow(id: String, result: String) {
    val token = id.trim().takeIf { it.isNotEmpty() && it.none { ch -> ch.isWhitespace() || ch == '/' } } ?: "-"
    val line = "intro window id=$token result=$result qos=utility"
    InAppLogger.info("Analysis", line)
    DiagnosticsLogger.logPerformance(line)
}
