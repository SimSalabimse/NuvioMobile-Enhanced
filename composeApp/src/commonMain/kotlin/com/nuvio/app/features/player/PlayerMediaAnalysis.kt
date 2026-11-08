package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.isIos
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
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

/** Skip times read from chapter markers in the file. Below user marks and external databases. */
internal const val CHAPTER_SKIP_PROVIDER = "chapters"

/**
 * Intro, recap and credits found on this phone (when the fallback is on). Intro and credits need
 * intro detection, the recap needs recap detection.
 */
internal fun PlayerScreenRuntime.onDeviceSkipIntervals(): List<SkipInterval> {
    if (!MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.OnDeviceSkipFallback)) return emptyList()
    val analysis = episodeSkipAnalysis() ?: return emptyList()
    return onDeviceSkipIntervalsFrom(
        analysis,
        recapDetection = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.RecapDetection),
        introDetection = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection),
    )
}

/** Stored intro, recap, credits and preview. Chapter-backed kinds use the chapters provider. */
internal fun onDeviceSkipIntervalsFrom(
    analysis: MediaAnalysisResult,
    recapDetection: Boolean,
    introDetection: Boolean,
): List<SkipInterval> {
    val out = mutableListOf<SkipInterval>()
    if (recapDetection) {
        val recapStart = analysis.recapStartMs
        val recapEnd = analysis.recapEndMs
        if (recapStart != null && recapEnd != null && recapEnd > recapStart) {
            out += SkipInterval(recapStart / 1000.0, recapEnd / 1000.0, "recap", analysis.skipProviderFor("recap"))
        }
    }
    if (introDetection) {
        val introStart = analysis.introStartMs
        val introEnd = analysis.introEndMs
        if (introStart != null && introEnd != null && introEnd > introStart) {
            out += SkipInterval(introStart / 1000.0, introEnd / 1000.0, "intro", analysis.skipProviderFor("intro"))
        }
        val creditsStart = analysis.creditsStartMs
        val creditsEnd = analysis.creditsEndMs
        if (creditsStart != null && creditsEnd != null && creditsEnd > creditsStart) {
            out += SkipInterval(creditsStart / 1000.0, creditsEnd / 1000.0, "outro", analysis.skipProviderFor("outro"))
        }
        val previewStart = analysis.previewStartMs
        val previewEnd = analysis.previewEndMs
        if (previewStart != null && previewEnd != null && previewEnd > previewStart) {
            out += SkipInterval(previewStart / 1000.0, previewEnd / 1000.0, "preview", analysis.skipProviderFor("preview"))
        }
    }
    return out
}

internal fun MediaAnalysisResult.skipProviderFor(kind: String): String =
    if (kind in chapterTypes) CHAPTER_SKIP_PROVIDER else ON_DEVICE_SKIP_PROVIDER

/**
 * Segment ids whose times come from this app's own analysis: intro, recap, credits, or preview
 * (including a time learned from another episode). A type the person marked is left out,
 * because that mark is theirs to submit. Empty when there is no stored analysis.
 */
internal fun segmentTypesSyncedByAnalysis(result: MediaAnalysisResult?): Set<String> {
    if (result == null) return emptySet()
    val marked = result.userSegments.keys
    return buildSet {
        if ("intro" !in marked && result.hasAnalysedRange(result.introStartMs, result.introEndMs)) add("intro")
        if ("recap" !in marked && result.hasAnalysedRange(result.recapStartMs, result.recapEndMs)) add("recap")
        if ("outro" !in marked && result.hasAnalysedRange(result.creditsStartMs, result.creditsEndMs)) add("outro")
        if ("preview" !in marked && result.hasAnalysedRange(result.previewStartMs, result.previewEndMs)) add("preview")
    }
}

private fun MediaAnalysisResult.hasAnalysedRange(startMs: Long?, endMs: Long?): Boolean =
    startMs != null && endMs != null && endMs > startMs

/**
 * Types the submit bar may mark Auto synced. Android only: iOS keeps the bar as it is.
 */
internal fun PlayerScreenRuntime.autoSyncedSubmitTypes(): Set<String> =
    if (isIos) emptySet() else segmentTypesSyncedByAnalysis(episodeSkipAnalysis())

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
 * online sources did not cover. A detected range for the open segment fills Submit Timestamps.
 */
internal fun PlayerScreenRuntime.withOnDeviceSkips(online: List<SkipInterval>): List<SkipInterval> =
    mergeOnDeviceSkips(online).also { used ->
        // Downloads cards show where each segment's times came from.
        com.nuvio.app.features.analysis.SkipSourceLedger.record(parentMetaId, activeSeasonNumber, activeEpisodeNumber, used)
        refreshSegmentClocks(used)
    }

private fun PlayerScreenRuntime.mergeOnDeviceSkips(online: List<SkipInterval>): List<SkipInterval> {
    // The user's own marks for this episode replace every other source of that kind.
    val mine = userMarkedSkipIntervals()
    val mineCovered = mine.map { skipCategory(it.type) }.toSet()
    val allowed = (online.filter { skipCategory(it.type) !in mineCovered } + mine).sortedBy { it.startTime }
    val covered = allowed.map { skipCategory(it.type) }.toSet()
    val extra = onDeviceSkipIntervals().filter { skipCategory(it.type) !in covered }
    if (extra.isEmpty()) return allowed
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

/** Stored speech energy around [centerMs], in the shape the live capture produces. */
private fun storedEnergyWindow(levels: DoubleArray, stepMs: Int, centerMs: Long): List<AudioEnergySample> {
    val step = stepMs.coerceAtLeast(1)
    val from = ((centerMs - SYNC_WINDOW_BEFORE_MS).coerceAtLeast(0L) / step).toInt()
    val to = ((centerMs + SYNC_WINDOW_AFTER_MS) / step).toInt().coerceAtMost(levels.size)
    if (to - from < 100) return emptyList()
    return (from until to).map { AudioEnergySample(timestampMs = it.toLong() * step, energy = levels[it]) }
}

/**
 * Matches from the stored speech energy at several points of the episode.
 * Each point uses [SubtitleAutoSyncEngine]. The caller fits those matches. It does not invent a frame rate.
 */
internal fun energySubtitleObservations(
    analysis: MediaAnalysisResult,
    cues: List<SubtitleSyncCue>,
): List<SubtitleMatchObservation> {
    val levels = decodeEnergy(analysis.energy)
    if (levels.isEmpty() || cues.isEmpty()) return emptyList()
    val duration = levels.size.toLong() * analysis.energyStepMs
    return listOf(0.2, 0.4, 0.6, 0.8).mapNotNull { fraction ->
        val center = (duration * fraction).toLong()
        val samples = storedEnergyWindow(levels, analysis.energyStepMs, center)
        if (samples.isEmpty()) return@mapNotNull null
        matchObservation(center, samples, cues)
    }
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
        val ticket = subtitleDrift.begin()
        val cues = runCatching {
            PlayerSubtitleCueParser.parse(
                httpGetTextWithHeaders(url = subtitle.url, headers = emptyMap()),
                subtitle.url,
            )
        }.getOrNull().orEmpty()
        if (cues.isEmpty() || !isActive || !subtitleDrift.isCurrent(ticket)) return@LaunchedEffect
        val stillRunning = isActive
        val outcome = subtitleDrift.correct(
            source = SubtitleObservationSource { energySubtitleObservations(analysis, cues) },
            ticket = ticket,
            provider = subtitle.addonName,
            release = subtitle.label,
            cancelled = { !stillRunning || !subtitleDrift.isCurrent(ticket) },
        )
        if (outcome != SubtitleDriftOutcome.Applied || selectedAddonSubtitle?.url != subtitle.url) return@LaunchedEffect
        if (subtitleDelayMs != 0) return@LaunchedEffect
        NuvioToastController.show("Subtitles synced to audio")
        InAppLogger.info("Analysis", "subtitle sync applied")
    }
}
