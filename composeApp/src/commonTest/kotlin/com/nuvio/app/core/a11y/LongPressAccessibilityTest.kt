package com.nuvio.app.core.a11y

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongPressAccessibilityTest {
    @Test
    fun everyLongPressSiteStaysReachable() {
        val installed = longPressAccessibilityCallers.map { it.first }.toSet()
        assertEquals(LongPressSite.entries.toSet(), installed)
        (longPressAccessibilityCallers + longPressAccessibilityDelegates).forEach { (site, caller) ->
            assertTrue(caller.isNotBlank(), site.name)
        }
        val delegateSites = longPressAccessibilityDelegates.map { it.first }.toSet()
        assertTrue(delegateSites.all { it in installed })
    }

    @Test
    fun eachSiteAnnouncesANonEmptyLabel() {
        val labels = sampleLabels()
        representativeMenus().forEach { (site, facts, expectedIds) ->
            val actions = longPressAccessibilityActions(site, facts, labels)
            assertEquals(expectedIds, actions.map { it.id }, site.name)
            actions.forEach { action ->
                assertTrue(action.label.isNotBlank(), "${site.name} ${action.id}")
            }
        }
    }

    @Test
    fun labelsFollowTheSameMenuState() {
        val labels = sampleLabels()
        val savedWatched = longPressAccessibilityActions(
            LongPressSite.PosterShelf,
            posterLongPressFacts(watched = true, savedInLibrary = true),
            labels,
        )
        assertEquals(listOf("Details", "Remove from library", "Mark unwatched"), savedWatched.map { it.label })

        val pinnedExternal = longPressAccessibilityActions(
            LongPressSite.StreamCard,
            streamCardLongPressFacts(canPin = true, pinned = true, externalPlayerEnabled = true),
            labels,
        )
        assertEquals(
            listOf("Unpin source", "Copy link", "Open in internal player", "Download file"),
            pinnedExternal.map { it.label },
        )

        val cloud = longPressAccessibilityActions(
            LongPressSite.ContinueWatching,
            continueWatchingLongPressFacts(watched = false, includeDetails = false),
            labels,
        )
        assertEquals(listOf("cw.watched", "cw.remove"), cloud.map { it.id })

        val zoomMenu = episodeLongPressFacts(
            watched = false,
            sheetMenu = false,
            hasPreviousEpisodes = true,
            previousEpisodesWatched = false,
            markSeasonWatched = "Mark season watched",
            markSeasonUnwatched = "Mark season unwatched",
            seasonWatched = false,
            canRate = true,
            rateLabel = "Rate episode",
            downloadEnabled = true,
            downloadLabel = "Download",
            playManually = true,
        )
        val zoomIds = longPressAccessibilityActions(LongPressSite.Episode, zoomMenu, labels).map { it.id }
        assertEquals(
            listOf(
                "episode.watched",
                "episode.previous",
                "episode.season",
                "episode.rate",
                "episode.play_manually",
                "episode.submit_segment",
            ),
            zoomIds,
        )

        val alreadyWatchedThroughHere = episodeLongPressFacts(
            watched = true,
            sheetMenu = true,
            hasPreviousEpisodes = true,
            previousEpisodesWatched = true,
            markSeasonWatched = "Mark season watched",
            markSeasonUnwatched = "Mark season unwatched",
            seasonWatched = true,
            canRate = false,
            rateLabel = "",
            downloadEnabled = false,
            downloadLabel = "",
            playManually = false,
        )
        val watchedIds = longPressAccessibilityActions(
            LongPressSite.Episode,
            alreadyWatchedThroughHere,
            labels,
        ).map { it.id }
        assertEquals(
            listOf("episode.watched", "episode.previous", "episode.season", "episode.submit_segment"),
            watchedIds,
        )
    }

    @Test
    fun blankLabelsAreNotAnnounced() {
        val actions = longPressAccessibilityActions(
            LongPressSite.DetailSave,
            LongPressMenuFacts(),
            sampleLabels().copy(libraryLists = " "),
        )
        assertEquals(emptyList(), actions)
    }

    private fun representativeMenus(): List<Triple<LongPressSite, LongPressMenuFacts, List<String>>> {
        val episodeSheet = episodeLongPressFacts(
            watched = false,
            sheetMenu = true,
            hasPreviousEpisodes = true,
            previousEpisodesWatched = false,
            markSeasonWatched = "Mark season watched",
            markSeasonUnwatched = "Mark season unwatched",
            seasonWatched = false,
            canRate = true,
            rateLabel = "Rate episode",
            downloadEnabled = true,
            downloadLabel = "Download",
            playManually = true,
        )
        val season = seasonLongPressFacts(
            seasonWatched = false,
            markSeasonWatched = "Mark season watched",
            markSeasonUnwatched = "Mark season unwatched",
            canRate = true,
            rateLabel = "Rate season",
            canMarkPreviousSeasons = true,
        )
        return listOf(
            Triple(
                LongPressSite.DownloadsActiveRow,
                LongPressMenuFacts(cancelSeason = true),
                listOf("downloads.cancel_season"),
            ),
            Triple(
                LongPressSite.DownloadsCompletedItem,
                LongPressMenuFacts(),
                listOf("downloads.reanalyze"),
            ),
            Triple(
                LongPressSite.DownloadsCompletedShow,
                LongPressMenuFacts(),
                listOf("downloads.reanalyze"),
            ),
            Triple(
                LongPressSite.OfflineHomeTitle,
                posterLongPressFacts(watched = false, savedInLibrary = false),
                listOf("poster.details", "poster.library", "poster.watched"),
            ),
            Triple(
                LongPressSite.PosterShelf,
                posterLongPressFacts(watched = false, savedInLibrary = false),
                listOf("poster.details", "poster.library", "poster.watched"),
            ),
            Triple(
                LongPressSite.OfflineHomeResume,
                continueWatchingLongPressFacts(watched = false, includeDetails = true),
                listOf("cw.details", "cw.watched", "cw.remove"),
            ),
            Triple(
                LongPressSite.ContinueWatching,
                continueWatchingLongPressFacts(watched = false, includeDetails = true),
                listOf("cw.details", "cw.watched", "cw.remove"),
            ),
            Triple(
                LongPressSite.Episode,
                episodeSheet,
                listOf(
                    "episode.watched",
                    "episode.up_to_here",
                    "episode.previous",
                    "episode.season",
                    "episode.rate",
                    "episode.download",
                    "episode.play_manually",
                    "episode.submit_segment",
                ),
            ),
            Triple(
                LongPressSite.Season,
                season,
                listOf("season.watched", "season.rate", "season.previous", "season.overview"),
            ),
            Triple(
                LongPressSite.StreamCard,
                streamCardLongPressFacts(canPin = true, pinned = false, externalPlayerEnabled = false),
                listOf("stream.pin", "stream.copy", "stream.open", "stream.download"),
            ),
            Triple(
                LongPressSite.StreamSourceHeader,
                LongPressMenuFacts(canPinSource = true, sourcePinned = false),
                listOf("source.pin"),
            ),
            Triple(
                LongPressSite.ProviderFilterChip,
                LongPressMenuFacts(canPinSource = true, sourcePinned = false),
                listOf("source.pin"),
            ),
            Triple(
                LongPressSite.DetailPlay,
                LongPressMenuFacts(includePlayManually = true),
                listOf("detail.play_manually"),
            ),
            Triple(
                LongPressSite.DetailSave,
                LongPressMenuFacts(),
                listOf("detail.library_lists"),
            ),
        )
    }

    private fun sampleLabels() = LongPressMenuLabels(
        cancelSeason = "Cancel season",
        reanalyze = "Re-analyze",
        goToDetails = "Details",
        addToLibrary = "Add to library",
        removeFromLibrary = "Remove from library",
        markWatched = "Mark watched",
        markUnwatched = "Mark unwatched",
        markPreviousWatched = "Mark previous watched",
        markPreviousUnwatched = "Mark previous unwatched",
        markUpToHere = "Mark watched up to here",
        playManually = "Play manually",
        remove = "Remove",
        pinSource = "Pin source",
        unpinSource = "Unpin source",
        copyLink = "Copy link",
        openInternalPlayer = "Open in internal player",
        openExternalPlayer = "Open in external player",
        downloadFile = "Download file",
        markPreviousSeasons = "Mark previous seasons watched",
        seasonOverview = "Season overview",
        libraryLists = "Library lists",
        submitSegment = "Submit segment only",
    )
}
