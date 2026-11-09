package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import com.nuvio.app.core.serialization.LenientJson

/** Which step of one file's analysis is running. */
enum class AnalysisStage { Audio, Matching, Recap, Previews }

/** What the analysis job is doing right now. */
sealed interface AnalysisJobState {
    data object Idle : AnalysisJobState
    data object Starting : AnalysisJobState
    data object WaitingForCharger : AnalysisJobState
    data object WaitingForPlayback : AnalysisJobState
    data class Running(
        val itemId: String,
        val title: String,
        val stage: AnalysisStage,
        /** 0..1 within the stage; negative when unknown. */
        val stageProgress: Double,
        val inBackground: Boolean,
        /** Stable step name: audio, match, clip, teach, backfill, recap, preview, previews. */
        val activity: String = "",
        /** Wall clock when [activity] started. The row shows elapsed time from this. The percent does not move from it. */
        val activitySinceEpochMs: Long = 0L,
    ) : AnalysisJobState {
        /**
         * Overall share of the file. Matching stays at 0.72 for the whole stage.
         * The row shows the step and the elapsed time; the percent is not animated.
         */
        val overall: Double
            get() {
                val p = stageProgress.coerceIn(0.0, 1.0)
                return when (stage) {
                    AnalysisStage.Audio -> 0.70 * p
                    AnalysisStage.Matching -> 0.72
                    AnalysisStage.Recap -> 0.74
                    AnalysisStage.Previews -> 0.75 + 0.25 * p
                }
            }
    }
}

@Serializable
data class AnalysisFailure(val fileName: String? = null, val reason: String, val atMs: Long = 0L)

@Serializable
data class AnalysisBackgroundInfo(
    val scheduledAtMs: Long? = null,
    val scheduleError: String? = null,
    val lastRunAtMs: Long? = null,
    val lastRunFinished: Boolean? = null,
)

/** Readiness of one analysis output for a file. */
enum class AnalysisPart { Ready, NotFound, NeedsMoreEpisodes, Off, Pending, NotChecked }

/** One chip on a downloaded item. Intro is episodes only; movies show Credits. */
enum class AnalysisChipKind { Intro, Credits, Recap, SubtitleSync, Loudness, Previews }

data class AnalysisParts(
    val intro: AnalysisPart,
    val credits: AnalysisPart,
    val recap: AnalysisPart,
    val subtitleSync: AnalysisPart,
    val loudness: AnalysisPart,
    val previews: AnalysisPart,
)

/** Chips in row order, with [AnalysisPart.Off] left out. */
internal fun analysisChipKinds(item: DownloadItem, parts: AnalysisParts): List<AnalysisChipKind> {
    val episode = item.seasonNumber != null && item.episodeNumber != null
    return buildList {
        if (episode) add(AnalysisChipKind.Intro to parts.intro)
        add(AnalysisChipKind.Credits to parts.credits)
        if (episode) add(AnalysisChipKind.Recap to parts.recap)
        add(AnalysisChipKind.SubtitleSync to parts.subtitleSync)
        add(AnalysisChipKind.Loudness to parts.loudness)
        add(AnalysisChipKind.Previews to parts.previews)
    }.filter { it.second != AnalysisPart.Off }.map { it.first }
}

sealed interface ItemAnalysisStatus {
    data object Unsupported : ItemAnalysisStatus
    data object Queued : ItemAnalysisStatus
    data class Running(
        val stage: AnalysisStage,
        val overall: Double,
        val activity: String = "",
        val activitySinceEpochMs: Long = 0L,
    ) : ItemAnalysisStatus
    data class Done(val parts: AnalysisParts, val complete: Boolean) : ItemAnalysisStatus
    data class Failed(val reason: String, val parts: AnalysisParts?) : ItemAnalysisStatus
}

data class AnalysisSummary(
    val total: Int,
    val analysed: Int,
    val queued: List<DownloadItem>,
    val failed: Int,
)

/** Live state of the on-device analysis, for the Downloads badges and the settings summary. */
object MediaAnalysisStatus {
    private const val FAILURES_KEY = "analysis_failures_v1"
    private const val BACKGROUND_KEY = "analysis_background_v1"
    private val json = LenientJson

    private val _job = MutableStateFlow<AnalysisJobState>(AnalysisJobState.Idle)
    val job: StateFlow<AnalysisJobState> = _job.asStateFlow()

    private val _failures = MutableStateFlow(loadFailures())
    val failures: StateFlow<Map<String, AnalysisFailure>> = _failures.asStateFlow()

    private val _background = MutableStateFlow(loadBackground())
    val background: StateFlow<AnalysisBackgroundInfo> = _background.asStateFlow()

    /** Bumped whenever stored results change, so the UI re-reads them. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * Host tests set this to read chip status where the analyzer binary is absent.
     * Null follows [MediaAnalyzerPlatform.supported].
     */
    internal var supportedForTests: Boolean? = null

    val supported: Boolean get() = supportedForTests ?: MediaAnalyzerPlatform.supported

    internal fun setJob(state: AnalysisJobState) {
        _job.value = state
    }

    internal fun updateProgress(progress: Double) {
        val current = _job.value as? AnalysisJobState.Running ?: return
        if (progress >= 0 && progress != current.stageProgress) _job.value = current.copy(stageProgress = progress)
    }

    internal fun resultsChanged() {
        _revision.value = _revision.value + 1
    }

    internal fun recordFailure(item: DownloadItem, reason: String) {
        _failures.value = _failures.value + (item.id to AnalysisFailure(item.fileName, reason, com.nuvio.app.features.streams.epochMs()))
        saveFailures()
        resultsChanged()
    }

    internal fun clearFailure(itemId: String) {
        if (!_failures.value.containsKey(itemId)) return
        _failures.value = _failures.value - itemId
        saveFailures()
    }

    internal fun clearAllFailures() {
        _failures.value = emptyMap()
        saveFailures()
    }

    internal fun backgroundScheduled(error: String?) {
        _background.value = _background.value.copy(
            scheduledAtMs = if (error == null) com.nuvio.app.features.streams.epochMs() else _background.value.scheduledAtMs,
            scheduleError = error,
        )
        saveBackground()
    }

    internal fun backgroundRan(finished: Boolean) {
        _background.value = _background.value.copy(lastRunAtMs = com.nuvio.app.features.streams.epochMs(), lastRunFinished = finished)
        saveBackground()
    }

    /** Status of one download, from the stored result, the running job and recorded failures. */
    fun statusFor(item: DownloadItem): ItemAnalysisStatus {
        if (!supported) return ItemAnalysisStatus.Unsupported
        val running = _job.value as? AnalysisJobState.Running
        if (running != null && running.itemId == item.id) {
            return ItemAnalysisStatus.Running(
                running.stage,
                running.overall,
                running.activity,
                running.activitySinceEpochMs,
            )
        }
        val stored = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item))?.takeIf { it.fileName == item.fileName }
        val failure = _failures.value[item.id]?.takeIf { it.fileName == null || it.fileName == item.fileName }
        val parts = stored?.let { partsOf(item, it) }
        if (failure != null) return ItemAnalysisStatus.Failed(failure.reason, parts)
        if (parts == null) return ItemAnalysisStatus.Queued
        val complete = listOf(parts.intro, parts.credits, parts.recap, parts.subtitleSync, parts.loudness, parts.previews)
            .none { it == AnalysisPart.Pending }
        return if (complete) ItemAnalysisStatus.Done(parts, true) else ItemAnalysisStatus.Done(parts, false)
    }

    private fun partsOf(item: DownloadItem, r: MediaAnalysisResult): AnalysisParts {
        fun on(flag: MediaAnalysisSettings.Flag) = MediaAnalysisSettings.isEnabled(flag)
        val season = item.seasonNumber
        val isEpisode = season != null && item.episodeNumber != null
        val derivedCurrent = r.derivedVersion >= MEDIA_ANALYSIS_DERIVED_VERSION
        val introOn = on(MediaAnalysisSettings.Flag.IntroDetection)
        val siblingsEmpty = introOn &&
            season != null &&
            (r.introEndMs == null || r.creditsStartMs == null) &&
            MediaAnalysisStore.seasonSiblings(item.parentMetaId, season, r.key).isEmpty()
        // Episodes: each side is Ready only from its own timestamp. Movies have no intro chip.
        // Credits on a movie use the stored time only; season matching stays episode-only.
        val intro = when {
            !introOn -> AnalysisPart.Off
            !isEpisode -> AnalysisPart.Off
            r.introEndMs != null -> AnalysisPart.Ready
            siblingsEmpty -> AnalysisPart.NeedsMoreEpisodes
            r.introOutcome in INTRO_CREDITS_CHECKED_NONE -> AnalysisPart.NotFound
            else -> AnalysisPart.NotChecked
        }
        val credits = when {
            !introOn -> AnalysisPart.Off
            r.creditsStartMs != null -> AnalysisPart.Ready
            !isEpisode -> AnalysisPart.NotFound
            siblingsEmpty -> AnalysisPart.NeedsMoreEpisodes
            r.creditsOutcome in INTRO_CREDITS_CHECKED_NONE -> AnalysisPart.NotFound
            else -> AnalysisPart.NotChecked
        }
        val recap = recapAnalysisPart(
            recapEnabled = on(MediaAnalysisSettings.Flag.RecapDetection),
            isEpisode = isEpisode,
            derivedCurrent = derivedCurrent,
            recapEndMs = r.recapEndMs,
            userRecap = r.userSegments["recap"],
            durationMs = r.durationMs,
            siblingsEmpty = season != null &&
                MediaAnalysisStore.seasonSiblings(item.parentMetaId, season, r.key).isEmpty(),
            outcome = r.recapOutcome,
        )
        val subtitleSync = when {
            !on(MediaAnalysisSettings.Flag.SubtitleSync) -> AnalysisPart.Off
            !r.energy.isNullOrEmpty() -> AnalysisPart.Ready
            else -> AnalysisPart.NotFound
        }
        val loudness = when {
            !on(MediaAnalysisSettings.Flag.Loudness) -> AnalysisPart.Off
            r.integratedLufs != null -> AnalysisPart.Ready
            else -> AnalysisPart.NotFound
        }
        val previews = when {
            !on(MediaAnalysisSettings.Flag.ScrubPreviews) -> AnalysisPart.Off
            r.thumbnails != null -> AnalysisPart.Ready
            else -> AnalysisPart.Pending
        }
        return AnalysisParts(intro, credits, recap, subtitleSync, loudness, previews)
    }

    fun summarize(items: List<DownloadItem>): AnalysisSummary {
        val completed = items.filter { it.status == DownloadStatus.Completed }
        val statuses = completed.associateWith { statusFor(it) }
        return AnalysisSummary(
            total = completed.size,
            analysed = statuses.values.count { it is ItemAnalysisStatus.Done && it.complete },
            queued = statuses.filter { (_, s) ->
                s is ItemAnalysisStatus.Queued || (s is ItemAnalysisStatus.Done && !s.complete)
            }.keys.sortedWith(AnalysisOrder),
            failed = statuses.values.count { it is ItemAnalysisStatus.Failed },
        )
    }

    private fun loadFailures(): Map<String, AnalysisFailure> = runCatching {
        DownloadsSettingsStorage.loadString(FAILURES_KEY)?.let { json.decodeFromString<Map<String, AnalysisFailure>>(it) }
    }.getOrNull().orEmpty()

    private fun saveFailures() {
        runCatching { DownloadsSettingsStorage.saveString(FAILURES_KEY, json.encodeToString(_failures.value)) }
    }

    private fun loadBackground(): AnalysisBackgroundInfo = runCatching {
        DownloadsSettingsStorage.loadString(BACKGROUND_KEY)?.let { json.decodeFromString<AnalysisBackgroundInfo>(it) }
    }.getOrNull() ?: AnalysisBackgroundInfo()

    private fun saveBackground() {
        runCatching { DownloadsSettingsStorage.saveString(BACKGROUND_KEY, json.encodeToString(_background.value)) }
    }
}
