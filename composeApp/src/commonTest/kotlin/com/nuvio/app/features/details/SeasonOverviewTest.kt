package com.nuvio.app.features.details

import com.nuvio.app.core.a11y.LongPressMenuLabels
import com.nuvio.app.core.a11y.LongPressSite
import com.nuvio.app.core.a11y.longPressAccessibilityActions
import com.nuvio.app.core.a11y.seasonLongPressFacts
import com.nuvio.app.features.player.skip.IntroSubmitService
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.applyOwnTheIntroDbListing
import com.nuvio.app.features.player.skip.shouldRecordOwnTheIntroDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeasonOverviewTest {
    @Test
    fun seasonWithOwnPublicBothAndNothingMarksOnlyOwnAsSubmitted() {
        val rows = seasonSegmentOverview(
            episodes = listOf(
                episode(1, watched = true),
                episode(2, watched = false),
                episode(3, watched = true),
                episode(4, watched = false),
            ),
            ownByKey = mapOf(
                key(1) to setOf("intro"),
                key(3) to setOf("intro", "recap"),
            ),
            publicByKey = mapOf(
                key(2) to mapOf("intro" to TheIntroDb.PROVIDER),
                key(3) to mapOf(
                    "intro" to TheIntroDb.PROVIDER,
                    "recap" to TheIntroDb.PROVIDER,
                    "outro" to "introdb",
                ),
            ),
        )
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.episode })
        assertTrue(rows.all { it.season == 1 })
        assertTrue(rows[0].watched)
        assertTrue(rows[2].watched)

        val ownOnly = rows[0]
        assertEquals(setOf("intro"), ownOnly.submitted)
        assertEquals(emptySet(), ownOnly.onTheIntroDb)
        assertEquals(setOf("recap", "outro", "preview"), ownOnly.missing)

        val publicOnly = rows[1]
        assertEquals(emptySet(), publicOnly.submitted)
        assertEquals(setOf("intro"), publicOnly.onTheIntroDb)
        assertFalse("intro" in publicOnly.submitted)
        assertEquals(setOf("recap", "outro", "preview"), publicOnly.missing)

        val both = rows[2]
        assertEquals(setOf("intro", "recap"), both.submitted)
        assertEquals(emptySet(), both.onTheIntroDb)
        assertFalse("intro" in both.onTheIntroDb)
        assertEquals(setOf("outro", "preview"), both.missing)
        assertFalse("outro" in both.submitted)

        val nothing = rows[3]
        assertEquals(emptySet(), nothing.submitted)
        assertEquals(emptySet(), nothing.onTheIntroDb)
        assertEquals(setOf("intro", "recap", "outro", "preview"), nothing.missing)

        assertEquals(3, submittedCount(rows))
        assertEquals(12, missingCount(rows))
    }

    @Test
    fun publicFetchedTypeIsNeverSubmittedEvenWhenTheNameMatches() {
        val row = episodeSegmentOverview(
            episode = episode(8),
            ownTypes = emptySet(),
            publicProviders = mapOf("opening" to TheIntroDb.PROVIDER, "credits" to "introdb"),
        )
        assertEquals(emptySet(), row.submitted)
        assertEquals(setOf("intro"), row.onTheIntroDb)
        assertTrue("outro" in row.missing)
        assertFalse("credits" in row.submitted)
    }

    @Test
    fun introDbSuccessIsNotAnOwnTheIntroDbSubmission() {
        assertFalse(shouldRecordOwnTheIntroDb(IntroSubmitService.INTRODB, succeeded = true))
        assertFalse(shouldRecordOwnTheIntroDb(IntroSubmitService.THE_INTRODB, succeeded = false))
        assertTrue(shouldRecordOwnTheIntroDb(IntroSubmitService.THE_INTRODB, succeeded = true))
    }

    @Test
    fun listingKeepsALocalTheIntroDbSuccessAndDropsARejectedOne() {
        val listed = applyOwnTheIntroDbListing(
            entries = mapOf(key(1) to setOf("intro", "outro")),
            contentKey = key(1),
            accepted = setOf("recap"),
            rejected = setOf("outro"),
        )
        assertEquals(setOf("intro", "recap"), listed[key(1)])
    }

    @Test
    fun overviewReportsLoadingEmptyAllSubmittedNonePartialAndOffline() {
        val one = episodeSegmentOverview(episode(1), ownTypes = setOf("intro"), publicProviders = emptyMap())
        val all = episodeSegmentOverview(
            episode(1),
            ownTypes = setOf("intro", "recap", "outro", "preview"),
            publicProviders = mapOf("intro" to TheIntroDb.PROVIDER),
        )
        val none = episodeSegmentOverview(
            episode(1),
            ownTypes = emptySet(),
            publicProviders = mapOf("intro" to TheIntroDb.PROVIDER),
        )
        assertEquals(SeasonOverviewUiState.Loading, seasonOverviewUiState(true, offline = true, episodesKnown = false, rows = emptyList()))
        assertEquals(SeasonOverviewUiState.EmptySeason, seasonOverviewUiState(false, offline = false, episodesKnown = true, rows = emptyList()))
        assertEquals(SeasonOverviewUiState.AllSubmitted, seasonOverviewUiState(false, offline = false, episodesKnown = true, rows = listOf(all)))
        assertEquals(SeasonOverviewUiState.NoneSubmitted, seasonOverviewUiState(false, offline = false, episodesKnown = true, rows = listOf(none)))
        assertEquals(SeasonOverviewUiState.Partial, seasonOverviewUiState(false, offline = false, episodesKnown = true, rows = listOf(one)))
        assertEquals(SeasonOverviewUiState.Offline, seasonOverviewUiState(false, offline = true, episodesKnown = false, rows = emptyList()))
        val cachedOffline = seasonOverviewModel(loading = false, offline = true, episodesKnown = true, rows = listOf(one))
        assertEquals(SeasonOverviewUiState.Partial, cachedOffline.state)
        assertFalse(cachedOffline.downloadEnabled)
        assertEquals(listOf(one), cachedOffline.rows)
        assertTrue(seasonOverviewDownloadEnabled(SeasonOverviewUiState.Partial, offline = false))
        assertFalse(seasonOverviewDownloadEnabled(SeasonOverviewUiState.EmptySeason, offline = false))
        assertEquals(setOf("intro", "recap", "outro", "preview"), all.submitted)
        assertEquals(emptySet(), all.onTheIntroDb)
    }

    @Test
    fun seasonMenuKeepsPriorRowsAndAddsOverview() {
        val facts = seasonLongPressFacts(
            seasonWatched = false,
            markSeasonWatched = "Mark season watched",
            markSeasonUnwatched = "Mark season unwatched",
            canRate = true,
            rateLabel = "Rate season",
            canMarkPreviousSeasons = true,
        )
        val ids = longPressAccessibilityActions(
            LongPressSite.Season,
            facts,
            LongPressMenuLabels(
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
            ),
        ).map { it.id }
        assertEquals(listOf("season.watched", "season.rate", "season.previous", "season.overview"), ids)
        assertEquals(emptyList(), seasonOverviewRemovalList)
        assertEquals(listOf("MetaDetailsScreen.SeasonWatchedActionSheet"), seasonOverviewCallers)
    }

    private fun episode(number: Int, watched: Boolean = false) = SeasonEpisodeRef(
        season = 1,
        episode = number,
        title = "Episode $number",
        watched = watched,
        contentKey = key(number),
    )

    private fun key(episode: Int) = "tt0000101:1:$episode"
}
