package com.nuvio.app.core.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.ratings.episodeUserRatingTarget
import com.nuvio.app.features.ratings.rememberCanRate
import com.nuvio.app.features.ratings.seasonUserRatingTarget
import com.nuvio.app.features.streams.StreamAutoPlayPolicy
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.previousReleasedEpisodesBefore
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watched.releasedPlayableEpisodes
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.a11y_library_lists
import nuvio.composeapp.generated.resources.a11y_mark_watched_up_to_here
import nuvio.composeapp.generated.resources.a11y_reanalyze
import nuvio.composeapp.generated.resources.cw_action_go_to_details
import nuvio.composeapp.generated.resources.cw_action_remove
import nuvio.composeapp.generated.resources.downloads_season_cancel
import nuvio.composeapp.generated.resources.episode_mark_previous_seasons_watched
import nuvio.composeapp.generated.resources.episode_submit_segment_only
import nuvio.composeapp.generated.resources.episode_mark_previous_unwatched
import nuvio.composeapp.generated.resources.episode_mark_previous_watched
import nuvio.composeapp.generated.resources.episode_mark_season_unwatched
import nuvio.composeapp.generated.resources.episode_mark_season_watched
import nuvio.composeapp.generated.resources.episode_mark_unwatched
import nuvio.composeapp.generated.resources.episode_mark_watched
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.episodes_specials
import nuvio.composeapp.generated.resources.hero_add_to_library
import nuvio.composeapp.generated.resources.hero_mark_unwatched
import nuvio.composeapp.generated.resources.hero_mark_watched
import nuvio.composeapp.generated.resources.hero_remove_from_library
import nuvio.composeapp.generated.resources.play_manually
import nuvio.composeapp.generated.resources.streams_copy_link
import nuvio.composeapp.generated.resources.streams_download_file
import nuvio.composeapp.generated.resources.streams_open_external_player
import nuvio.composeapp.generated.resources.streams_open_internal_player
import nuvio.composeapp.generated.resources.streams_pin_source
import nuvio.composeapp.generated.resources.streams_unpin_source
import nuvio.composeapp.generated.resources.user_rating_rate_episode
import nuvio.composeapp.generated.resources.user_rating_rate_season
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun longPressMenuLabels(): LongPressMenuLabels = LongPressMenuLabels(
    cancelSeason = stringResource(Res.string.downloads_season_cancel),
    reanalyze = stringResource(Res.string.a11y_reanalyze),
    goToDetails = stringResource(Res.string.cw_action_go_to_details),
    addToLibrary = stringResource(Res.string.hero_add_to_library),
    removeFromLibrary = stringResource(Res.string.hero_remove_from_library),
    markWatched = stringResource(Res.string.hero_mark_watched),
    markUnwatched = stringResource(Res.string.hero_mark_unwatched),
    markPreviousWatched = stringResource(Res.string.episode_mark_previous_watched),
    markPreviousUnwatched = stringResource(Res.string.episode_mark_previous_unwatched),
    markUpToHere = stringResource(Res.string.a11y_mark_watched_up_to_here),
    playManually = stringResource(Res.string.play_manually),
    remove = stringResource(Res.string.cw_action_remove),
    pinSource = stringResource(Res.string.streams_pin_source),
    unpinSource = stringResource(Res.string.streams_unpin_source),
    copyLink = stringResource(Res.string.streams_copy_link),
    openInternalPlayer = stringResource(Res.string.streams_open_internal_player),
    openExternalPlayer = stringResource(Res.string.streams_open_external_player),
    downloadFile = stringResource(Res.string.streams_download_file),
    markPreviousSeasons = stringResource(Res.string.episode_mark_previous_seasons_watched),
    libraryLists = stringResource(Res.string.a11y_library_lists),
    submitSegment = stringResource(Res.string.episode_submit_segment_only),
)

@Composable
internal fun posterShelfLongPressActions(
    id: String,
    type: String,
    watched: Boolean,
    enabled: Boolean,
): List<LongPressAccessibilityAction> {
    if (!enabled) return emptyList()
    return longPressAccessibilityActions(
        site = LongPressSite.PosterShelf,
        facts = posterLongPressFacts(
            watched = watched,
            savedInLibrary = LibraryRepository.isSaved(id, type),
        ),
        labels = longPressMenuLabels(),
    )
}

@Composable
internal fun offlineTitleLongPressActions(
    id: String,
    type: String,
    watched: Boolean,
    enabled: Boolean,
): List<LongPressAccessibilityAction> {
    if (!enabled) return emptyList()
    return longPressAccessibilityActions(
        site = LongPressSite.OfflineHomeTitle,
        facts = posterLongPressFacts(
            watched = watched,
            savedInLibrary = LibraryRepository.isSaved(id, type),
        ),
        labels = longPressMenuLabels(),
    )
}

@Composable
internal fun continueWatchingLongPressActions(
    item: ContinueWatchingItem,
    enabled: Boolean,
    site: LongPressSite = LongPressSite.ContinueWatching,
): List<LongPressAccessibilityAction> {
    if (!enabled) return emptyList()
    val watched = WatchedRepository.isWatched(
        id = item.parentMetaId,
        type = item.parentMetaType,
        season = item.seasonNumber,
        episode = item.episodeNumber,
    )
    return longPressAccessibilityActions(
        site = site,
        facts = continueWatchingLongPressFacts(
            watched = watched,
            includeDetails = !item.parentMetaType.equals(CloudLibraryContentType, ignoreCase = true),
        ),
        labels = longPressMenuLabels(),
    )
}

@Composable
internal fun downloadsLongPressActions(
    site: LongPressSite,
    enabled: Boolean,
): List<LongPressAccessibilityAction> {
    if (!enabled) return emptyList()
    return longPressAccessibilityActions(
        site = site,
        facts = LongPressMenuFacts(cancelSeason = site == LongPressSite.DownloadsActiveRow),
        labels = longPressMenuLabels(),
    )
}

@Composable
internal fun detailLongPressActions(
    site: LongPressSite,
    enabled: Boolean,
): List<LongPressAccessibilityAction> {
    if (!enabled) return emptyList()
    return longPressAccessibilityActions(
        site = site,
        facts = LongPressMenuFacts(includePlayManually = site == LongPressSite.DetailPlay),
        labels = longPressMenuLabels(),
    )
}

@Composable
internal fun sourcePinLongPressActions(
    site: LongPressSite,
    enabled: Boolean,
    pinned: Boolean,
): List<LongPressAccessibilityAction> {
    if (!enabled) return emptyList()
    return longPressAccessibilityActions(
        site = site,
        facts = LongPressMenuFacts(canPinSource = true, sourcePinned = pinned),
        labels = longPressMenuLabels(),
    )
}

@Composable
internal fun episodeLongPressActions(
    meta: MetaDetails,
    episode: MetaVideo,
    isWatched: Boolean,
    isEpisodeWatched: (MetaVideo) -> Boolean,
    sheetMenu: Boolean,
    downloadEnabled: Boolean,
    downloadLabel: String,
    manualPlayAvailable: Boolean,
    enabled: Boolean,
): List<LongPressAccessibilityAction> {
    if (!enabled) return emptyList()
    val today = CurrentDateProvider.todayIsoDate()
    val previous = meta.previousReleasedEpisodesBefore(episode, today)
    val seasonEpisodes = meta.releasedEpisodesForSeason(episode.season, today)
    val previousWatched = previous.isNotEmpty() && previous.all(isEpisodeWatched)
    val seasonWatched = seasonEpisodes.isNotEmpty() && seasonEpisodes.all(isEpisodeWatched)
    val seasonName = episode.season?.takeIf { it > 0 }?.let { season ->
        stringResource(Res.string.episodes_season, season)
    } ?: stringResource(Res.string.episodes_specials)
    val settings by androidx.compose.runtime.remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val canRate = rememberCanRate(meta.episodeUserRatingTarget(episode))
    return longPressAccessibilityActions(
        site = LongPressSite.Episode,
        facts = episodeLongPressFacts(
            watched = isWatched,
            sheetMenu = sheetMenu,
            hasPreviousEpisodes = previous.isNotEmpty(),
            previousEpisodesWatched = previousWatched,
            markSeasonWatched = stringResource(Res.string.episode_mark_season_watched, seasonName),
            markSeasonUnwatched = stringResource(Res.string.episode_mark_season_unwatched, seasonName),
            seasonWatched = seasonWatched,
            canRate = canRate,
            rateLabel = stringResource(Res.string.user_rating_rate_episode),
            downloadEnabled = downloadEnabled,
            downloadLabel = downloadLabel,
            playManually = manualPlayAvailable && StreamAutoPlayPolicy.isEffectivelyEnabled(settings),
        ),
        labels = longPressMenuLabels(),
    )
}

@Composable
internal fun seasonLongPressActions(
    meta: MetaDetails,
    season: Int,
    isEpisodeWatched: (MetaVideo) -> Boolean,
    enabled: Boolean,
): List<LongPressAccessibilityAction> {
    if (!enabled) return emptyList()
    val today = CurrentDateProvider.todayIsoDate()
    val seasonEpisodes = meta.releasedEpisodesForSeason(season, today)
    val seasonWatched = seasonEpisodes.isNotEmpty() && seasonEpisodes.all(isEpisodeWatched)
    val previousSeasonEpisodes = meta.releasedPlayableEpisodes(today).filter { episode ->
        val episodeSeason = episode.season?.coerceAtLeast(0) ?: 0
        episodeSeason > 0 && episodeSeason < season.coerceAtLeast(0)
    }
    val canMarkPrevious = previousSeasonEpisodes.any { episode -> !isEpisodeWatched(episode) }
    val seasonName = if (season <= 0) {
        stringResource(Res.string.episodes_specials)
    } else {
        stringResource(Res.string.episodes_season, season)
    }
    val canRate = rememberCanRate(meta.seasonUserRatingTarget(season))
    return longPressAccessibilityActions(
        site = LongPressSite.Season,
        facts = seasonLongPressFacts(
            seasonWatched = seasonWatched,
            markSeasonWatched = stringResource(Res.string.episode_mark_season_watched, seasonName),
            markSeasonUnwatched = stringResource(Res.string.episode_mark_season_unwatched, seasonName),
            canRate = canRate,
            rateLabel = stringResource(Res.string.user_rating_rate_season, seasonName),
            canMarkPreviousSeasons = canMarkPrevious,
        ),
        labels = longPressMenuLabels(),
    )
}
