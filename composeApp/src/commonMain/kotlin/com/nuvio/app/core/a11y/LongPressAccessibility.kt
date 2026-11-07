package com.nuvio.app.core.a11y

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/**
 * A user-facing long-press target. [longPressAccessibilityActions] lists the menu entries
 * VoiceOver and TalkBack should announce. Activating any entry runs the same callback the
 * long-press already runs, so the menu (or the single Downloads action) stays reachable.
 */
enum class LongPressSite {
    DownloadsActiveRow,
    DownloadsCompletedItem,
    DownloadsCompletedShow,
    OfflineHomeResume,
    OfflineHomeTitle,
    PosterShelf,
    ContinueWatching,
    Episode,
    Season,
    StreamCard,
    StreamSourceHeader,
    ProviderFilterChip,
    DetailPlay,
    DetailSave,
}

/**
 * Facts that choose which menu rows exist. Defaults hide optional rows so a caller
 * cannot announce an action the menu does not show.
 */
internal data class LongPressMenuFacts(
    val watched: Boolean = false,
    val savedInLibrary: Boolean = false,
    val cancelSeason: Boolean = false,
    val includeDetails: Boolean = false,
    val includeLibraryToggle: Boolean = false,
    val includeWatchedToggle: Boolean = false,
    val includeRemove: Boolean = false,
    val includePlayManually: Boolean = false,
    val includeMarkUpToHere: Boolean = false,
    val includePreviousEpisodes: Boolean = false,
    val previousEpisodesWatched: Boolean = false,
    val includeSeasonToggle: Boolean = false,
    val seasonWatched: Boolean = false,
    val markSeasonWatched: String = "",
    val markSeasonUnwatched: String = "",
    val includeRate: Boolean = false,
    val rateLabel: String = "",
    val includeDownload: Boolean = false,
    val downloadLabel: String = "",
    val includePreviousSeasons: Boolean = false,
    val includeSeasonOverview: Boolean = false,
    val includeSubmitSegment: Boolean = false,
    val includeWatchPrivately: Boolean = false,
    val canPinSource: Boolean = false,
    val sourcePinned: Boolean = false,
    val externalPlayerEnabled: Boolean = false,
)

internal data class LongPressMenuLabels(
    val cancelSeason: String,
    val reanalyze: String,
    val goToDetails: String,
    val addToLibrary: String,
    val removeFromLibrary: String,
    val markWatched: String,
    val markUnwatched: String,
    val markPreviousWatched: String,
    val markPreviousUnwatched: String,
    val markUpToHere: String,
    val playManually: String,
    val remove: String,
    val pinSource: String,
    val unpinSource: String,
    val copyLink: String,
    val openInternalPlayer: String,
    val openExternalPlayer: String,
    val downloadFile: String,
    val markPreviousSeasons: String,
    val seasonOverview: String,
    val libraryLists: String,
    val submitSegment: String,
    val watchPrivately: String,
)

data class LongPressAccessibilityAction(
    val id: String,
    val label: String,
)

/**
 * Composables that install [longPressAccessibilityActions]. The reachability test
 * requires every [LongPressSite] to appear here.
 */
internal val longPressAccessibilityCallers: List<Pair<LongPressSite, String>> = listOf(
    LongPressSite.DownloadsActiveRow to "DownloadsScreen.DownloadRow",
    LongPressSite.DownloadsCompletedItem to "DownloadsScreen.DownloadRow",
    LongPressSite.DownloadsCompletedShow to "DownloadsScreen.downloadsRootContent",
    LongPressSite.OfflineHomeResume to "OfflineDownloadsHome.ResumeCard",
    LongPressSite.OfflineHomeTitle to "OfflineDownloadsHome.PosterTile",
    LongPressSite.PosterShelf to "HomePosterCard",
    LongPressSite.PosterShelf to "PosterGrid.PosterGridTile",
    LongPressSite.PosterShelf to "CatalogScreen.CatalogPosterTile",
    LongPressSite.ContinueWatching to "HomeContinueWatchingSection.ContinueWatchingCard",
    LongPressSite.ContinueWatching to "HomeContinueWatchingSection.ContinueWatchingWideCard",
    LongPressSite.ContinueWatching to "HomeContinueWatchingSection.ContinueWatchingPosterCard",
    LongPressSite.ContinueWatching to "HomeFinishSeriesSection.FinishSeriesCard",
    LongPressSite.Episode to "DetailSeriesContent.EpisodeHorizontalCard",
    LongPressSite.Episode to "DetailSeriesContent.EpisodeListCard",
    LongPressSite.Episode to "DetailEpisodeRatingMap.RatingMapCellView",
    LongPressSite.Season to "DetailSeriesContent.seasonChip",
    LongPressSite.Season to "DetailSeriesContent.SeasonPosterButton",
    LongPressSite.StreamCard to "StreamCard",
    LongPressSite.StreamSourceHeader to "StreamsScreen.StreamSectionHeader",
    LongPressSite.StreamSourceHeader to "StreamsScreen.StreamSourceHeader",
    LongPressSite.ProviderFilterChip to "ProviderFilterRow.FilterChip",
    LongPressSite.DetailPlay to "DetailActionButtons.PlayButton",
    LongPressSite.DetailSave to "DetailActionButtons.DetailIconAction",
)

/**
 * Screens that pass a long-press into a composable listed in [longPressAccessibilityCallers].
 * They do not install semantics themselves.
 */
internal val longPressAccessibilityDelegates: List<Pair<LongPressSite, String>> = listOf(
    LongPressSite.PosterShelf to "HomeCatalogSection",
    LongPressSite.PosterShelf to "LibraryScreen",
    LongPressSite.PosterShelf to "DetailPosterRailSection",
    LongPressSite.OfflineHomeResume to "OfflineDownloadsHome.ResumeCard dimmed clearAndSetSemantics",
)

internal fun longPressAccessibilityActions(
    site: LongPressSite,
    facts: LongPressMenuFacts = LongPressMenuFacts(),
    labels: LongPressMenuLabels,
): List<LongPressAccessibilityAction> {
    fun action(id: String, label: String) = LongPressAccessibilityAction(id, label)
    val watchedLabel = if (facts.watched) labels.markUnwatched else labels.markWatched
    val libraryLabel = if (facts.savedInLibrary) labels.removeFromLibrary else labels.addToLibrary
    val seasonLabel = if (facts.seasonWatched) facts.markSeasonUnwatched else facts.markSeasonWatched
    val previousLabel = if (facts.previousEpisodesWatched) {
        labels.markPreviousUnwatched
    } else {
        labels.markPreviousWatched
    }
    val pinLabel = if (facts.sourcePinned) labels.unpinSource else labels.pinSource
    val openPlayerLabel = if (facts.externalPlayerEnabled) {
        labels.openInternalPlayer
    } else {
        labels.openExternalPlayer
    }
    return when (site) {
        LongPressSite.DownloadsActiveRow ->
            listOfNotNull(action("downloads.cancel_season", labels.cancelSeason).takeIf { facts.cancelSeason })
        LongPressSite.DownloadsCompletedItem,
        LongPressSite.DownloadsCompletedShow,
        -> listOf(action("downloads.reanalyze", labels.reanalyze))
        LongPressSite.OfflineHomeTitle,
        LongPressSite.PosterShelf,
        -> buildList {
            if (facts.includeDetails) add(action("poster.details", labels.goToDetails))
            if (facts.includeLibraryToggle) add(action("poster.library", libraryLabel))
            if (facts.includeWatchedToggle) add(action("poster.watched", watchedLabel))
        }
        LongPressSite.OfflineHomeResume,
        LongPressSite.ContinueWatching,
        -> buildList {
            if (facts.includeDetails) add(action("cw.details", labels.goToDetails))
            if (facts.includeWatchedToggle) add(action("cw.watched", watchedLabel))
            if (facts.includeRemove) add(action("cw.remove", labels.remove))
        }
        LongPressSite.Episode -> buildList {
            if (facts.includeWatchedToggle) add(action("episode.watched", watchedLabel))
            if (facts.includeMarkUpToHere) add(action("episode.up_to_here", labels.markUpToHere))
            if (facts.includePreviousEpisodes) add(action("episode.previous", previousLabel))
            if (facts.includeSeasonToggle && seasonLabel.isNotBlank()) add(action("episode.season", seasonLabel))
            if (facts.includeRate && facts.rateLabel.isNotBlank()) add(action("episode.rate", facts.rateLabel))
            if (facts.includeDownload && facts.downloadLabel.isNotBlank()) add(action("episode.download", facts.downloadLabel))
            if (facts.includePlayManually) add(action("episode.play_manually", labels.playManually))
            if (facts.includeSubmitSegment) add(action("episode.submit_segment", labels.submitSegment))
            if (facts.includeWatchPrivately) add(action("episode.watch_privately", labels.watchPrivately))
        }
        LongPressSite.Season -> buildList {
            if (facts.includeSeasonToggle && seasonLabel.isNotBlank()) add(action("season.watched", seasonLabel))
            if (facts.includeRate && facts.rateLabel.isNotBlank()) add(action("season.rate", facts.rateLabel))
            if (facts.includePreviousSeasons) add(action("season.previous", labels.markPreviousSeasons))
            if (facts.includeSeasonOverview) add(action("season.overview", labels.seasonOverview))
        }
        LongPressSite.StreamCard -> buildList {
            if (facts.canPinSource) add(action("stream.pin", pinLabel))
            add(action("stream.copy", labels.copyLink))
            add(action("stream.open", openPlayerLabel))
            add(action("stream.download", labels.downloadFile))
        }
        LongPressSite.StreamSourceHeader,
        LongPressSite.ProviderFilterChip,
        -> listOfNotNull(action("source.pin", pinLabel).takeIf { facts.canPinSource })
        LongPressSite.DetailPlay ->
            listOfNotNull(action("detail.play_manually", labels.playManually).takeIf { facts.includePlayManually })
        LongPressSite.DetailSave ->
            listOf(action("detail.library_lists", labels.libraryLists))
    }.filter { it.label.isNotBlank() }
}

internal fun episodeLongPressFacts(
    watched: Boolean,
    sheetMenu: Boolean,
    hasPreviousEpisodes: Boolean,
    previousEpisodesWatched: Boolean,
    markSeasonWatched: String,
    markSeasonUnwatched: String,
    seasonWatched: Boolean,
    canRate: Boolean,
    rateLabel: String,
    downloadEnabled: Boolean,
    downloadLabel: String,
    playManually: Boolean,
): LongPressMenuFacts = LongPressMenuFacts(
    watched = watched,
    includeWatchedToggle = true,
    includeMarkUpToHere = sheetMenu && hasPreviousEpisodes && !(previousEpisodesWatched && watched),
    includePreviousEpisodes = hasPreviousEpisodes,
    previousEpisodesWatched = previousEpisodesWatched,
    includeSeasonToggle = true,
    seasonWatched = seasonWatched,
    markSeasonWatched = markSeasonWatched,
    markSeasonUnwatched = markSeasonUnwatched,
    includeRate = canRate,
    rateLabel = rateLabel,
    includeDownload = sheetMenu && downloadEnabled,
    downloadLabel = downloadLabel,
    includePlayManually = playManually,
    includeSubmitSegment = true,
    includeWatchPrivately = true,
)

internal fun seasonLongPressFacts(
    seasonWatched: Boolean,
    markSeasonWatched: String,
    markSeasonUnwatched: String,
    canRate: Boolean,
    rateLabel: String,
    canMarkPreviousSeasons: Boolean,
): LongPressMenuFacts = LongPressMenuFacts(
    includeSeasonToggle = true,
    seasonWatched = seasonWatched,
    markSeasonWatched = markSeasonWatched,
    markSeasonUnwatched = markSeasonUnwatched,
    includeRate = canRate,
    rateLabel = rateLabel,
    includePreviousSeasons = canMarkPreviousSeasons,
    includeSeasonOverview = true,
)

internal fun posterLongPressFacts(watched: Boolean, savedInLibrary: Boolean): LongPressMenuFacts =
    LongPressMenuFacts(
        watched = watched,
        savedInLibrary = savedInLibrary,
        includeDetails = true,
        includeLibraryToggle = true,
        includeWatchedToggle = true,
    )

internal fun continueWatchingLongPressFacts(
    watched: Boolean,
    includeDetails: Boolean,
): LongPressMenuFacts = LongPressMenuFacts(
    watched = watched,
    includeDetails = includeDetails,
    includeWatchedToggle = true,
    includeRemove = true,
)

internal fun streamCardLongPressFacts(
    canPin: Boolean,
    pinned: Boolean,
    externalPlayerEnabled: Boolean,
): LongPressMenuFacts = LongPressMenuFacts(
    canPinSource = canPin,
    sourcePinned = pinned,
    externalPlayerEnabled = externalPlayerEnabled,
)

internal fun Modifier.installLongPressAccessibility(
    actions: List<LongPressAccessibilityAction>,
    onLongPress: () -> Unit,
): Modifier {
    if (actions.isEmpty()) return this
    return semantics {
        customActions = actions.map { action ->
            CustomAccessibilityAction(action.label) {
                onLongPress()
                true
            }
        }
    }
}
