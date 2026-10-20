package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.MediaAnalysisSettings
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.analysis.decodeEnergy
import com.nuvio.app.features.analysis.loudnessGainDb
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.downloadOpenedBySource
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.formatSecondsToHms
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Uses the on-device analysis (see features/analysis). Loudness gain, subtitle-to-audio sync and
 * scrub previews apply only when the file on screen is the one that was analysed. Intro, recap and
 * credits are kept per episode, so they also apply to a stream of it ([episodeSkipAnalysis]).
 */
internal fun PlayerScreenRuntime.currentLocalAnalysis(): MediaAnalysisResult? {
    val url = activeSourceUrl.trim()
    if (url.isEmpty()) return null
    val item = downloadOpenedBySource(url, DownloadsRepository.uiState.value.items) ?: return null
    val result = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item)) ?: return null
    return result.takeIf { it.fileName == item.fileName }
}

/**
 * The analysis to take skip times from: the downloaded file on screen, else what is stored for the
 * episode (kept after its download is deleted), unless the stream's length shows another cut.
 */
private fun PlayerScreenRuntime.episodeSkipAnalysis(): MediaAnalysisResult? {
    currentLocalAnalysis()?.let { return it }
    if (parentMetaId.isBlank()) return null
    val stored = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(parentMetaId, activeSeasonNumber, activeEpisodeNumber))
    return stored?.takeIf { sameCut(it.durationMs, playbackSnapshot.durationMs) }
}

/** True unless both lengths are known and differ by more than 30 s (another cut of the episode). */
internal fun sameCut(analysedMs: Long, playingMs: Long): Boolean =
    analysedMs <= 0L || playingMs <= 0L || abs(analysedMs - playingMs) <= SAME_CUT_TOLERANCE_MS

private const val SAME_CUT_TOLERANCE_MS = 30_000L

/** Skip sources the user allows: everything, or TheIntroDB only (Settings > Playback). */
internal fun filterSkipSources(intervals: List<SkipInterval>): List<SkipInterval> =
    if (MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.TheIntroDbOnly)) {
        intervals.filter { it.provider == TheIntroDb.PROVIDER }
    } else {
        intervals
    }

internal const val ON_DEVICE_SKIP_PROVIDER = "on-device"

/**
 * Intro, recap and credits found on this phone (when the fallback is on). Intro and credits need
 * intro detection, the recap needs recap detection.
 */
internal fun PlayerScreenRuntime.onDeviceSkipIntervals(): List<SkipInterval> {
    if (!MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.OnDeviceSkipFallback)) return emptyList()
    val analysis = episodeSkipAnalysis() ?: return emptyList()
    val out = mutableListOf<SkipInterval>()
    if (MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.RecapDetection)) {
        val recapStart = analysis.recapStartMs
        val recapEnd = analysis.recapEndMs
        if (recapStart != null && recapEnd != null && recapEnd > recapStart) {
            out += SkipInterval(recapStart / 1000.0, recapEnd / 1000.0, "recap", ON_DEVICE_SKIP_PROVIDER)
        }
    }
    if (MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection)) {
        val introStart = analysis.introStartMs
        val introEnd = analysis.introEndMs
        if (introStart != null && introEnd != null && introEnd > introStart) {
            out += SkipInterval(introStart / 1000.0, introEnd / 1000.0, "intro", ON_DEVICE_SKIP_PROVIDER)
        }
        val creditsStart = analysis.creditsStartMs
        val creditsEnd = analysis.creditsEndMs
        if (creditsStart != null && creditsEnd != null && creditsEnd > creditsStart) {
            out += SkipInterval(creditsStart / 1000.0, creditsEnd / 1000.0, "outro", ON_DEVICE_SKIP_PROVIDER)
        }
    }
    return out
}

/** Segments the user marked or confirmed for the episode on screen (streamed or downloaded). */
internal fun PlayerScreenRuntime.userMarkedSkipIntervals(): List<SkipInterval> =
    com.nuvio.app.features.analysis.SkipLearning.userMarks(parentMetaId, activeSeasonNumber, activeEpisodeNumber)
        .filter { (_, mark) -> mark.endMs > mark.startMs && sameCut(mark.durationMs, playbackSnapshot.durationMs) }
        .map { (type, mark) ->
            SkipInterval(
                mark.startMs / 1000.0,
                mark.endMs / 1000.0,
                type,
                if (mark.imported) com.nuvio.app.features.analysis.SkipLearning.PROVIDER_IMPORTED
                else com.nuvio.app.features.analysis.SkipLearning.PROVIDER,
            )
        }

private fun skipCategory(type: String): String = when (type.lowercase()) {
    "intro", "op", "mixed-op", "opening" -> "intro"
    "recap" -> "recap"
    "outro", "ed", "mixed-ed", "credits", "ending", "movie-credits" -> "outro"
    else -> type.lowercase()
}

/**
 * The allowed online segments, plus on-device ones for each kind (intro, recap, credits) the
 * online sources did not cover. An on-device intro pre-fills Submit Timestamps.
 */
internal fun PlayerScreenRuntime.withOnDeviceSkips(online: List<SkipInterval>): List<SkipInterval> =
    mergeOnDeviceSkips(online).also { used ->
        // Downloads cards show where each segment's times came from.
        com.nuvio.app.features.analysis.SkipSourceLedger.record(parentMetaId, activeSeasonNumber, activeEpisodeNumber, used)
    }

private fun PlayerScreenRuntime.mergeOnDeviceSkips(online: List<SkipInterval>): List<SkipInterval> {
    // The user's own marks for this episode replace every other source of that kind.
    val mine = userMarkedSkipIntervals()
    val mineCovered = mine.map { skipCategory(it.type) }.toSet()
    val allowed = (online.filter { skipCategory(it.type) !in mineCovered } + mine).sortedBy { it.startTime }
    val covered = allowed.map { skipCategory(it.type) }.toSet()
    val extra = onDeviceSkipIntervals().filter { skipCategory(it.type) !in covered }
    if (extra.isEmpty()) return allowed
    extra.firstOrNull { it.type == "intro" }?.takeIf {
        // Only fill an untouched intro form; never switch the type the user picked.
        submitIntroSegmentType == "intro" &&
            submitIntroStartTimeStr == com.nuvio.app.features.player.skip.EMPTY_SKIP_TIMESTAMP &&
            submitIntroEndTimeStr == com.nuvio.app.features.player.skip.EMPTY_SKIP_TIMESTAMP
    }?.let { intro ->
        submitIntroStartTimeStr = formatSecondsToHms(intro.startTime)
        submitIntroEndTimeStr = formatSecondsToHms(intro.endTime)
    }
    InAppLogger.info("Analysis", "on-device skip intervals ${extra.joinToString { "${it.type} ${it.startTime}-${it.endTime}" }}")
    return (allowed + extra).sortedBy { it.startTime }
}

/** Seek preview from the thumbnail sheets of the downloaded file on screen, when there are any. */
internal fun PlayerScreenRuntime.localThumbnailSource(): com.nuvio.app.features.player.seekpreview.SeekPreviewFrameSource? {
    if (!MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.ScrubPreviews)) return null
    val analysis = currentLocalAnalysis()?.takeIf { it.thumbnails != null } ?: return null
    return com.nuvio.app.features.player.seekpreview.ThumbnailSheetFrameSource(analysis)
}

private const val SYNC_WINDOW_BEFORE_MS = 20_000L
private const val SYNC_WINDOW_AFTER_MS = 40_000L
private const val SYNC_AGREEMENT_MS = 300

/** Stored speech energy around [centerMs], in the shape the live capture produces. */
private fun storedEnergyWindow(levels: DoubleArray, stepMs: Int, centerMs: Long): List<AudioEnergySample> {
    val step = stepMs.coerceAtLeast(1)
    val from = ((centerMs - SYNC_WINDOW_BEFORE_MS).coerceAtLeast(0L) / step).toInt()
    val to = ((centerMs + SYNC_WINDOW_AFTER_MS) / step).toInt().coerceAtMost(levels.size)
    if (to - from < 100) return emptyList()
    return (from until to).map { AudioEnergySample(timestampMs = it.toLong() * step, energy = levels[it]) }
}

/**
 * Offset that lines [cues] up with the file's speech, measured at several points of the episode
 * from the stored energy. Null unless at least two windows agree within 300 ms.
 */
internal fun storedEnergySubtitleOffset(analysis: MediaAnalysisResult, cues: List<SubtitleSyncCue>): Int? {
    val levels = decodeEnergy(analysis.energy)
    if (levels.isEmpty() || cues.isEmpty()) return null
    val duration = levels.size.toLong() * analysis.energyStepMs
    val offsets = listOf(0.2, 0.4, 0.6, 0.8).mapNotNull { fraction ->
        val center = (duration * fraction).toLong()
        val samples = storedEnergyWindow(levels, analysis.energyStepMs, center)
        if (samples.isEmpty()) return@mapNotNull null
        (SubtitleAutoSyncEngine.computeOptimalOffset(samples, cues, center) as? SubtitleAutoSyncResult.Success)?.offsetMs
    }.sorted()
    if (offsets.size < 2) return null
    val median = offsets[offsets.size / 2]
    val agreeing = offsets.count { abs(it - median) <= SYNC_AGREEMENT_MS }
    return median.takeIf { agreeing >= 2 }
}

@Composable
internal fun PlayerScreenRuntime.BindMediaAnalysisEffects() {
    val flags by MediaAnalysisSettings.flags.collectAsStateWithLifecycle()
    val loudnessOn = flags[MediaAnalysisSettings.Flag.Loudness] ?: MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.Loudness)
    val syncOn = flags[MediaAnalysisSettings.Flag.SubtitleSync] ?: MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.SubtitleSync)

    // Loudness: gain for the analysed file, 0 dB for anything else.
    LaunchedEffect(playerController, activeSourceUrl, loudnessOn) {
        val controller = playerController ?: return@LaunchedEffect
        val gain = if (loudnessOn) currentLocalAnalysis()?.loudnessGainDb() ?: 0.0 else 0.0
        controller.setLoudnessGainDb(gain)
    }

    // Subtitle sync: when an addon subtitle is picked on an analysed file and no delay is set yet.
    LaunchedEffect(selectedAddonSubtitleId, useCustomSubtitles, activeSourceUrl, syncOn) {
        if (!syncOn || !useCustomSubtitles) return@LaunchedEffect
        val subtitle = selectedAddonSubtitle ?: return@LaunchedEffect
        val analysis = currentLocalAnalysis()?.takeIf { !it.energy.isNullOrEmpty() } ?: return@LaunchedEffect
        delay(1_500)
        if (subtitleDelayMs != 0) return@LaunchedEffect
        val cues = runCatching {
            PlayerSubtitleCueParser.parse(
                httpGetTextWithHeaders(url = subtitle.url, headers = emptyMap()),
                subtitle.url,
            )
        }.getOrNull().orEmpty()
        if (cues.isEmpty()) return@LaunchedEffect
        val offset = storedEnergySubtitleOffset(analysis, cues) ?: run {
            InAppLogger.info("Analysis", "subtitle sync: no agreeing offset")
            return@LaunchedEffect
        }
        if (selectedAddonSubtitle?.url != subtitle.url || subtitleDelayMs != 0) return@LaunchedEffect
        if (abs(offset) < 100) return@LaunchedEffect
        setSubtitleDelay(offset)
        val seconds = (offset / 100.0).roundToInt() / 10.0
        val sign = if (offset > 0) "+" else ""
        NuvioToastController.show("Subtitles synced to audio ($sign$seconds s)")
        InAppLogger.info("Analysis", "subtitle sync applied offsetMs=$offset")
    }
}
