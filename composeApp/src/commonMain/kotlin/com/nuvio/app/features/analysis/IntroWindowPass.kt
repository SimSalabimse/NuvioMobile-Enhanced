package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.diagnostics.DiagnosticsLogger
import com.nuvio.app.features.downloads.DownloadsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** First few minutes: the intro and recap window, not the 25-minute fingerprint. */
internal const val INTRO_WINDOW_MS = 5 * 60_000L

/** Last stretch of the file. Long enough for a credits cue, short enough to stay cool. */
internal const val CREDITS_WINDOW_MS = 6 * 60_000L

/** Ask the decoder to place the window at the end of the file. */
internal const val WINDOW_AT_END = -1L

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

/**
 * Intro first, then credits. A cool phone runs them as soon as the file is downloaded.
 * Heat holds them until playback pauses. The next-episode threshold runs whatever is left.
 */
internal fun nextUpAnalysisPlan(
    introDone: Boolean,
    creditsDone: Boolean,
    atThreshold: Boolean,
    videoPlaying: Boolean,
    thermal: AnalysisThermal,
    lowPowerMode: Boolean,
): List<String> {
    val pending = buildList {
        if (!introDone) add("intro")
        if (!creditsDone) add("credits")
    }
    if (pending.isEmpty()) return emptyList()
    val cool = !lowPowerMode && (thermal == AnalysisThermal.Nominal || thermal == AnalysisThermal.Fair)
    val allowed = atThreshold || !videoPlaying || cool
    return if (allowed) pending else emptyList()
}

internal fun nextUpAutoplayReady(introDone: Boolean, creditsDone: Boolean): Boolean =
    introDone && creditsDone

/** Autoplay may pause for the next-up windows, and no longer than this. */
internal const val NEXT_UP_AUTOPLAY_WAIT_MS = 5_000L

/** Ready now, or the cap has been reached. Playback starts either way. */
internal fun autoplayStarts(waitedMs: Long, analysisReady: Boolean): Boolean =
    analysisReady || waitedMs >= NEXT_UP_AUTOPLAY_WAIT_MS

internal enum class LateMark { Apply, Record, Pending }

internal data class LateNextUp(val intro: LateMark, val credits: LateMark)

/**
 * A late intro is a skip only while the playhead is still inside that window.
 * Past the window, the result is recorded and playback is left where it is.
 * Credits are the next-episode marker when the playhead has not reached them.
 */
internal fun lateNextUpApply(
    positionMs: Long,
    introReady: Boolean,
    introEndMs: Long,
    creditsReady: Boolean,
    creditsStartMs: Long,
): LateNextUp = LateNextUp(
    intro = when {
        !introReady -> LateMark.Pending
        positionMs < introEndMs -> LateMark.Apply
        else -> LateMark.Record
    },
    credits = when {
        !creditsReady -> LateMark.Pending
        positionMs < creditsStartMs -> LateMark.Apply
        else -> LateMark.Record
    },
)

/** The light pass stops if the phone heats up or Low Power Mode switches on. */
internal fun introWindowShouldAbort(thermal: AnalysisThermal, lowPowerMode: Boolean): Boolean {
    if (lowPowerMode) return true
    return thermal == AnalysisThermal.Serious || thermal == AnalysisThermal.Critical
}

internal object NextUpAnalysis {
    private val results = mutableMapOf<String, MutableMap<String, String>>()
    private var pendingId: String? = null
    private var pendingUri: String? = null
    private val attempted = mutableSetOf<String>()
    private val revisions = MutableStateFlow(0)
    val updates: StateFlow<Int> = revisions

    fun result(id: String, kind: String): String = results[id]?.get(kind) ?: "pending"

    fun ready(id: String): Boolean {
        val intro = result(id, "intro")
        val credits = result(id, "credits")
        return intro != "pending" && credits != "pending"
    }

    fun mark(id: String, kind: String, value: String) {
        results.getOrPut(id) { mutableMapOf() }[kind] = value
        revisions.value = revisions.value + 1
    }

    fun consider(id: String, fileUri: String, atThreshold: Boolean, videoPlaying: Boolean) {
        pendingId = id
        pendingUri = fileUri
        val power = AnalysisPower.snapshot()
        val plan = nextUpAnalysisPlan(
            introDone = result(id, "intro") == "scanned" || result(id, "intro") == "unsupported",
            creditsDone = result(id, "credits") == "scanned" || result(id, "credits") == "unsupported",
            atThreshold = atThreshold,
            videoPlaying = videoPlaying,
            thermal = power.thermal,
            lowPowerMode = power.lowPowerMode,
        )
        if (plan.isEmpty()) return
        val key = "$id:${if (atThreshold) "threshold" else "cool"}:${plan.joinToString()}"
        if (!attempted.add(key)) return
        MediaAnalysisScheduler.offerWindows(id, fileUri, plan)
    }

    fun onPaused() {
        val id = pendingId ?: return
        val uri = pendingUri ?: return
        consider(id, uri, atThreshold = false, videoPlaying = false)
    }
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
    if (!allowed && stored.hasOnDeviceMarks()) {
        logIntroWindow(item.id, "skip-marks")
        return
    }
    val uri = DownloadsRepository.playableLocalFileUri(item) ?: return
    NextUpAnalysis.consider(item.id, uri, atThreshold = atThreshold, videoPlaying = true)
}

private fun logIntroWindow(id: String, result: String) {
    val token = id.trim().takeIf { it.isNotEmpty() && it.none { ch -> ch.isWhitespace() || ch == '/' } } ?: "-"
    val line = "intro window id=$token result=$result qos=utility"
    InAppLogger.info("Analysis", line)
    DiagnosticsLogger.logPerformance(line)
}
