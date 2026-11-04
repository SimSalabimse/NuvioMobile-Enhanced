package com.nuvio.app.features.details

import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.canonicalFlagSegmentType
import com.nuvio.app.features.player.skip.flagSubmitContentKey

/** Segment types the submit bar can send for an episode. Credits are stored as "outro". */
internal val SEASON_SEGMENT_TYPES: List<String> = listOf("intro", "recap", "outro", "preview")

internal enum class SegmentMark {
    /** This account submitted this type to TheIntroDB. */
    Submitted,
    /** A public TheIntroDB time was fetched. Never [Submitted]. */
    OnTheIntroDb,
    Missing,
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
    val onTheIntroDb: Set<String> get() = marks.filterValues { it == SegmentMark.OnTheIntroDb }.keys
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
)

/** Actions removed to add the overview. */
internal val seasonOverviewRemovalList: List<String> = emptyList()

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
 * [ownTypes] are this account's TheIntroDB submissions.
 * [publicProviders] maps a segment kind to the provider a playback fetch recorded.
 * Only [TheIntroDb.PROVIDER] draws [SegmentMark.OnTheIntroDb]. Nothing in [publicProviders] is submitted.
 */
internal fun episodeSegmentOverview(
    episode: SeasonEpisodeRef,
    ownTypes: Set<String>,
    publicProviders: Map<String, String>,
): EpisodeSegmentOverview {
    val own = ownTypes.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) }
    val publicTheIntroDb = publicProviders.mapNotNull { (kind, provider) ->
        val type = canonicalFlagSegmentType(kind) ?: return@mapNotNull null
        type.takeIf { provider == TheIntroDb.PROVIDER && type !in own }
    }.toSet()
    val marks = SEASON_SEGMENT_TYPES.associateWith { type ->
        when {
            type in own -> SegmentMark.Submitted
            type in publicTheIntroDb -> SegmentMark.OnTheIntroDb
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
    ownByKey: Map<String, Set<String>>,
    publicByKey: Map<String, Map<String, String>>,
): List<EpisodeSegmentOverview> = episodes.map { episode ->
    episodeSegmentOverview(
        episode = episode,
        ownTypes = ownByKey[episode.contentKey].orEmpty(),
        publicProviders = publicByKey[episode.contentKey].orEmpty(),
    )
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
            row.onTheIntroDb.isEmpty()
    }
    if (allOwn) return SeasonOverviewUiState.AllSubmitted
    if (rows.all { it.submitted.isEmpty() }) return SeasonOverviewUiState.NoneSubmitted
    return SeasonOverviewUiState.Partial
}

internal fun seasonOverviewDownloadEnabled(state: SeasonOverviewUiState, offline: Boolean): Boolean =
    !offline && (state == SeasonOverviewUiState.AllSubmitted ||
        state == SeasonOverviewUiState.NoneSubmitted ||
        state == SeasonOverviewUiState.Partial)

internal fun seasonOverviewModel(
    loading: Boolean,
    offline: Boolean,
    episodesKnown: Boolean,
    rows: List<EpisodeSegmentOverview>,
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
        downloadEnabled = seasonOverviewDownloadEnabled(state, offline),
    )
}
