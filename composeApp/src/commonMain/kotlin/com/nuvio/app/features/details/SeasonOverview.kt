package com.nuvio.app.features.details

import com.nuvio.app.core.uifixture.UiFixture
import com.nuvio.app.core.uifixture.currentUiFixture
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.player.skip.OwnAccountMarks
import com.nuvio.app.features.player.skip.OwnMarkStatus
import com.nuvio.app.features.player.skip.OwnTheIntroDbLedger
import com.nuvio.app.features.player.skip.canonicalFlagSegmentType
import com.nuvio.app.features.player.skip.currentTheIntroDbAccountScope
import com.nuvio.app.features.player.skip.flagSubmitContentKey

/** Segment types the submit bar can send for an episode. Credits are stored as "outro". */
internal val SEASON_SEGMENT_TYPES: List<String> = listOf("intro", "recap", "outro", "preview")

/** Visible state sentence for a local detection. Not a footnote. */
internal const val DETECTED_NOT_SUBMITTED_STATE = "Detected, not submitted yet"

internal enum class SegmentMark {
    /** This account's submission is confirmed. */
    Submitted,
    /** This account sent it. The review is not confirmed. */
    Pending,
    /** On-device detection only. Not a submission. */
    Detected,
    Missing,
}

/**
 * How a [SegmentMark] is drawn without relying on colour alone:
 * a filled chip with a check, an outline, the detection sentence, or dim type text.
 */
internal enum class SegmentChipVisual {
    FilledCheck,
    Outlined,
    DetectedText,
    Dim,
}

internal fun segmentChipVisual(mark: SegmentMark): SegmentChipVisual = when (mark) {
    SegmentMark.Submitted -> SegmentChipVisual.FilledCheck
    SegmentMark.Pending -> SegmentChipVisual.Outlined
    SegmentMark.Detected -> SegmentChipVisual.DetectedText
    SegmentMark.Missing -> SegmentChipVisual.Dim
}

internal data class SegmentChipModel(
    val visibleText: String,
    val visual: SegmentChipVisual,
)

/** The detection sentence is the chip text. There is no separate legend. */
internal fun segmentChipModel(typeLabel: String, mark: SegmentMark): SegmentChipModel = when (mark) {
    SegmentMark.Submitted -> SegmentChipModel(typeLabel, SegmentChipVisual.FilledCheck)
    SegmentMark.Pending -> SegmentChipModel("$typeLabel, pending", SegmentChipVisual.Outlined)
    SegmentMark.Detected -> SegmentChipModel(
        "$typeLabel. $DETECTED_NOT_SUBMITTED_STATE",
        SegmentChipVisual.DetectedText,
    )
    SegmentMark.Missing -> SegmentChipModel(typeLabel, SegmentChipVisual.Dim)
}

internal data class SeasonEpisodeRef(
    val season: Int,
    val episode: Int,
    val title: String,
    val watched: Boolean,
    val contentKey: String,
)

internal data class EpisodeSegmentOverview(
    val season: Int,
    val episode: Int,
    val title: String,
    val watched: Boolean,
    val contentKey: String,
    val marks: Map<String, SegmentMark>,
) {
    val submitted: Set<String> get() = marks.filterValues { it == SegmentMark.Submitted }.keys
    val pending: Set<String> get() = marks.filterValues { it == SegmentMark.Pending }.keys
    val detected: Set<String> get() = marks.filterValues { it == SegmentMark.Detected }.keys
    val missing: Set<String> get() = marks.filterValues { it == SegmentMark.Missing }.keys
}

internal enum class SeasonOverviewUiState {
    Loading,
    EmptySeason,
    AllSubmitted,
    NoneSubmitted,
    Partial,
    Offline,
}

internal data class SeasonOverviewModel(
    val state: SeasonOverviewUiState,
    val rows: List<EpisodeSegmentOverview>,
    val downloadEnabled: Boolean,
)

/** Call sites of the season overview entry. Nothing else opens it. */
internal val seasonOverviewCallers: List<String> = listOf(
    "MetaDetailsScreen.SeasonWatchedActionSheet",
    "DownloadsScreen.ShowSeason",
)

/** Actions removed to add the overview. */
internal val seasonOverviewRemovalList: List<String> = emptyList()

/** The sheet leaves, then the episode opens. Returning can open it again. */
internal enum class OverviewOpenStep {
    DismissOverlay,
    OpenEpisode,
}

internal fun overviewEpisodeOpenSteps(): List<OverviewOpenStep> = listOf(
    OverviewOpenStep.DismissOverlay,
    OverviewOpenStep.OpenEpisode,
)

internal data class OverviewSession(val overlayVisible: Boolean)

internal fun overviewSessionAfterEpisodeOpen(): OverviewSession = OverviewSession(overlayVisible = false)

internal fun overviewCanOpenAgain(session: OverviewSession): Boolean = !session.overlayVisible

internal fun seasonEpisodeContentKey(
    metaId: String,
    imdbId: String?,
    videoId: String,
    videoImdbId: String?,
    season: Int,
    episode: Int,
): String {
    val imdb = videoImdbId?.takeIf { it.startsWith("tt", ignoreCase = true) }
        ?: videoId.split(":").firstOrNull()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        ?: imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) }
        ?: metaId.takeIf { it.startsWith("tt", ignoreCase = true) }
        ?: ""
    return flagSubmitContentKey(imdb, season, episode, isMovie = false)
}

/**
 * [own] is this account only.
 * [detectedTypes] are on-device ranges that are not this account's submission.
 * A public TheIntroDB time is not an input and cannot become [SegmentMark.Submitted].
 */
internal fun episodeSegmentOverview(
    episode: SeasonEpisodeRef,
    own: OwnAccountMarks,
    detectedTypes: Set<String>,
): EpisodeSegmentOverview {
    val confirmed = own.confirmed[episode.contentKey].orEmpty().mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) }
    val pending = own.pending[episode.contentKey].orEmpty().mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) } - confirmed
    val detected = detectedTypes.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) } - confirmed - pending
    val marks = SEASON_SEGMENT_TYPES.associateWith { type ->
        when {
            type in confirmed -> SegmentMark.Submitted
            type in pending -> SegmentMark.Pending
            type in detected -> SegmentMark.Detected
            else -> SegmentMark.Missing
        }
    }
    return EpisodeSegmentOverview(
        season = episode.season,
        episode = episode.episode,
        title = episode.title,
        watched = episode.watched,
        contentKey = episode.contentKey,
        marks = marks,
    )
}

internal fun seasonSegmentOverview(
    episodes: List<SeasonEpisodeRef>,
    own: OwnAccountMarks,
    detectedByKey: Map<String, Set<String>>,
): List<EpisodeSegmentOverview> = episodes.map { episode ->
    episodeSegmentOverview(
        episode = episode,
        own = own,
        detectedTypes = detectedByKey[episode.contentKey].orEmpty(),
    )
}

/**
 * Analyzer ranges that the user did not mark and did not import.
 * Imported submissions and typed marks are not detections.
 */
internal fun localDetectionTypes(result: MediaAnalysisResult?): Set<String> {
    if (result == null) return emptySet()
    val user = result.userSegments.keys.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) }
    fun range(type: String, start: Long?, end: Long?) =
        type.takeIf { start != null && end != null && end > start && type !in user }
    return listOfNotNull(
        range("intro", result.introStartMs, result.introEndMs),
        range("recap", result.recapStartMs, result.recapEndMs),
        range("outro", result.creditsStartMs, result.creditsEndMs),
        range("preview", result.previewStartMs, result.previewEndMs),
    ).toSet()
}

internal fun overviewLedgerScope(fixture: UiFixture? = currentUiFixture()): String =
    if (fixture == UiFixture.SeasonOverview || fixture == UiFixture.SeasonOverviewLong) {
        OwnTheIntroDbLedger.FIXTURE_SCOPE
    } else {
        currentTheIntroDbAccountScope()
    }

internal fun submittedCount(rows: List<EpisodeSegmentOverview>): Int = rows.sumOf { it.submitted.size }

internal fun missingCount(rows: List<EpisodeSegmentOverview>): Int = rows.sumOf { it.missing.size }

internal fun seasonOverviewUiState(
    loading: Boolean,
    offline: Boolean,
    episodesKnown: Boolean,
    rows: List<EpisodeSegmentOverview>,
): SeasonOverviewUiState {
    if (loading) return SeasonOverviewUiState.Loading
    if (!episodesKnown) {
        return if (offline) SeasonOverviewUiState.Offline else SeasonOverviewUiState.Loading
    }
    if (rows.isEmpty()) return SeasonOverviewUiState.EmptySeason
    val allOwn = rows.all { row ->
        row.submitted.size == SEASON_SEGMENT_TYPES.size &&
            row.missing.isEmpty() &&
            row.pending.isEmpty() &&
            row.detected.isEmpty()
    }
    if (allOwn) return SeasonOverviewUiState.AllSubmitted
    if (rows.all { it.submitted.isEmpty() }) return SeasonOverviewUiState.NoneSubmitted
    return SeasonOverviewUiState.Partial
}

/** Files already on the device are not downloaded again. Offline never starts one. */
internal fun seasonOverviewAnalysisDownloadAllowed(
    fromDownloads: Boolean,
    offline: Boolean,
    state: SeasonOverviewUiState,
): Boolean = !fromDownloads && !offline && (
    state == SeasonOverviewUiState.AllSubmitted ||
        state == SeasonOverviewUiState.NoneSubmitted ||
        state == SeasonOverviewUiState.Partial
    )

internal fun seasonOverviewDownloadEnabled(state: SeasonOverviewUiState, offline: Boolean): Boolean =
    seasonOverviewAnalysisDownloadAllowed(fromDownloads = false, offline = offline, state = state)

internal fun seasonOverviewModel(
    loading: Boolean,
    offline: Boolean,
    episodesKnown: Boolean,
    rows: List<EpisodeSegmentOverview>,
    fromDownloads: Boolean = false,
): SeasonOverviewModel {
    val state = seasonOverviewUiState(loading, offline, episodesKnown, rows)
    val visible = if (state == SeasonOverviewUiState.Loading ||
        state == SeasonOverviewUiState.Offline ||
        state == SeasonOverviewUiState.EmptySeason
    ) {
        emptyList()
    } else {
        rows
    }
    return SeasonOverviewModel(
        state = state,
        rows = visible,
        downloadEnabled = seasonOverviewAnalysisDownloadAllowed(fromDownloads, offline, state),
    )
}
