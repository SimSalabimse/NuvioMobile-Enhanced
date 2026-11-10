package com.nuvio.app.features.details

import com.nuvio.app.core.a11y.LongPressMenuLabels
import com.nuvio.app.core.a11y.LongPressSite
import com.nuvio.app.core.a11y.longPressAccessibilityActions
import com.nuvio.app.core.a11y.seasonLongPressFacts
import com.nuvio.app.core.ui.FitSheetAction
import com.nuvio.app.core.ui.fitSheetDetentPoints
import com.nuvio.app.core.ui.fitSheetMeasurement
import com.nuvio.app.core.uifixture.UiFixtureCatalog
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.UserSegment
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.applyingSubmitMode
import com.nuvio.app.features.player.skip.FlagSubmissionRecord
import com.nuvio.app.features.player.skip.IntroSubmitService
import com.nuvio.app.features.player.skip.ListStatusBucket
import com.nuvio.app.features.player.skip.OwnAccountMarks
import com.nuvio.app.features.player.skip.applyAccountListing
import com.nuvio.app.features.player.skip.listStatusBucket
import com.nuvio.app.features.player.skip.ownListBucketsForTitle
import com.nuvio.app.features.player.skip.shouldRecordOwnTheIntroDb
import com.nuvio.app.features.watchprogress.allowsProgressWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeasonOverviewTest {
    @Test
    fun seasonMarksConfirmedPendingDetectedAndMissingWithoutPublicConsensus() {
        val rows = seasonSegmentOverview(
            episodes = listOf(
                episode(1, watched = true),
                episode(2, watched = false),
                episode(3, watched = true),
                episode(4, watched = false),
            ),
            own = OwnAccountMarks(
                confirmed = mapOf(
                    key(1) to setOf("intro"),
                    key(3) to setOf("intro", "recap"),
                ),
                pending = mapOf(key(2) to setOf("outro")),
            ),
            detectedByKey = mapOf(
                key(2) to setOf("intro", "outro"),
                key(3) to setOf("intro", "outro"),
                key(4) to setOf("preview"),
            ),
        )
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.episode })
        assertTrue(rows[0].watched)
        assertTrue(rows[2].watched)

        val confirmedOnly = rows[0]
        assertEquals(setOf("intro"), confirmedOnly.submitted)
        assertEquals(emptySet(), confirmedOnly.pending)
        assertEquals(emptySet(), confirmedOnly.detected)
        assertEquals(setOf("recap", "outro", "preview"), confirmedOnly.missing)

        val pendingBeatsDetection = rows[1]
        assertEquals(emptySet(), pendingBeatsDetection.submitted)
        assertEquals(setOf("outro"), pendingBeatsDetection.pending)
        assertEquals(setOf("intro"), pendingBeatsDetection.detected)
        assertFalse("intro" in pendingBeatsDetection.submitted)
        assertEquals(setOf("recap", "preview"), pendingBeatsDetection.missing)

        val confirmedBeatsDetection = rows[2]
        assertEquals(setOf("intro", "recap"), confirmedBeatsDetection.submitted)
        assertEquals(setOf("outro"), confirmedBeatsDetection.detected)
        assertFalse("intro" in confirmedBeatsDetection.detected)

        val detectedOnly = rows[3]
        assertEquals(emptySet(), detectedOnly.submitted)
        assertEquals(setOf("preview"), detectedOnly.detected)
        val previewChip = segmentChipModel("Preview", detectedOnly.marks.getValue("preview"))
        assertEquals("Preview · detected", previewChip.visibleText)
        assertEquals(
            "Preview. $DETECTED_NOT_SUBMITTED_STATE",
            previewChip.spokenText,
        )
        assertEquals(listOf("preview"), visibleSegmentMarks(detectedOnly.marks).map { it.first })

        assertEquals(3, submittedCount(rows))
        assertEquals(9, missingCount(rows))
    }

    @Test
    fun publicFetchIsNotAMark() {
        val row = episodeSegmentOverview(
            episode = episode(8),
            own = OwnAccountMarks(),
            detectedTypes = emptySet(),
        )
        assertEquals(emptySet(), row.submitted)
        assertEquals(emptySet(), row.pending)
        assertEquals(emptySet(), row.detected)
        assertEquals(setOf("intro", "recap", "outro", "preview"), row.missing)
    }

    @Test
    fun introDbSuccessIsNotAnOwnTheIntroDbSubmission() {
        assertFalse(shouldRecordOwnTheIntroDb(IntroSubmitService.INTRODB, succeeded = true))
        assertFalse(shouldRecordOwnTheIntroDb(IntroSubmitService.THE_INTRODB, succeeded = false))
        assertTrue(shouldRecordOwnTheIntroDb(IntroSubmitService.THE_INTRODB, succeeded = true))
    }

    @Test
    fun listingKeepsCachedConfirmedDropsRejectedAndDoesNotPromotePending() {
        val listed = applyAccountListing(
            marks = OwnAccountMarks(
                confirmed = mapOf(key(1) to setOf("intro", "outro")),
                pending = mapOf(key(1) to setOf("preview")),
            ),
            contentKey = key(1),
            confirmed = setOf("recap"),
            pending = setOf("intro"),
            rejected = setOf("outro"),
        )
        assertEquals(setOf("recap"), listed.confirmed[key(1)])
        assertEquals(setOf("intro", "preview"), listed.pending[key(1)])
        assertFalse("outro" in listed.confirmed[key(1)].orEmpty())
        assertFalse("intro" in listed.confirmed[key(1)].orEmpty())
    }

    @Test
    fun overviewReportsLoadingEmptyAllSubmittedNonePartialAndOffline() {
        val one = episodeSegmentOverview(
            episode(1),
            own = OwnAccountMarks(confirmed = mapOf(key(1) to setOf("intro"))),
            detectedTypes = emptySet(),
        )
        val all = episodeSegmentOverview(
            episode(1),
            own = OwnAccountMarks(confirmed = mapOf(key(1) to setOf("intro", "recap", "outro", "preview"))),
            detectedTypes = setOf("intro"),
        )
        val none = episodeSegmentOverview(
            episode(1),
            own = OwnAccountMarks(pending = mapOf(key(1) to setOf("intro"))),
            detectedTypes = setOf("recap"),
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
        assertEquals(emptySet(), all.detected)
        assertEquals(setOf("intro"), none.pending)
        assertEquals(setOf("recap"), none.detected)
        assertTrue(none.submitted.isEmpty())
    }

    @Test
    fun chipVisualDiffersWithoutALegend() {
        assertEquals(SegmentChipVisual.FilledCheck, segmentChipVisual(SegmentMark.Submitted))
        assertEquals(SegmentChipVisual.Outlined, segmentChipVisual(SegmentMark.Pending))
        assertEquals(SegmentChipVisual.DetectedText, segmentChipVisual(SegmentMark.Detected))
        assertEquals(SegmentChipVisual.Dim, segmentChipVisual(SegmentMark.Missing))
        assertEquals(DETECTED_NOT_SUBMITTED_STATE, "Detected, not submitted yet")
        val detected = segmentChipModel("Intro", SegmentMark.Detected)
        assertEquals("Intro · detected", detected.visibleText)
        assertTrue(detected.spokenText.contains(DETECTED_NOT_SUBMITTED_STATE))
        assertFalse(detected.visibleText.contains("legend", ignoreCase = true))
        val row = episodeSegmentOverview(
            episode = episode(1),
            own = OwnAccountMarks(
                confirmed = mapOf(key(1) to setOf("recap")),
                pending = mapOf(key(1) to setOf("intro")),
            ),
            detectedTypes = setOf("outro"),
        )
        assertEquals(SegmentChipVisual.FilledCheck, segmentChipVisual(row.marks.getValue("recap")))
        assertEquals(SegmentChipVisual.Outlined, segmentChipVisual(row.marks.getValue("intro")))
        assertEquals(SegmentChipVisual.DetectedText, segmentChipVisual(row.marks.getValue("outro")))
        assertEquals(SegmentChipVisual.Dim, segmentChipVisual(row.marks.getValue("preview")))
    }

    @Test
    fun twentyFiveEpisodeSeasonListsEveryEpisodeIncludingWatched() {
        val count = 25
        assertEquals(count, UiFixtureCatalog.SEASON_OVERVIEW_LONG_EPISODE_COUNT)
        val episodes = (1..count).map { number ->
            episode(number, watched = number == 1 || number == count)
        }
        val rows = seasonSegmentOverview(
            episodes = episodes,
            own = OwnAccountMarks(
                confirmed = mapOf(
                    key(1) to setOf("recap"),
                    key(2) to setOf("intro", "recap", "outro", "preview"),
                ),
            ),
            detectedByKey = mapOf(key(4) to setOf("intro")),
        )
        assertEquals(episodes.size, rows.size)
        assertEquals(count, rows.size)
        assertEquals(episodes.map { it.episode }, rows.map { it.episode })
        assertTrue(rows.first().watched)
        assertTrue(rows.last().watched)
        assertEquals(setOf("recap"), rows[0].submitted)
        assertEquals(emptySet(), rows[0].detected)
        assertEquals(setOf("intro", "recap", "outro", "preview"), rows[1].submitted)
        assertTrue(rows[2].submitted.isEmpty())
        assertTrue(rows[2].detected.isEmpty())
        assertEquals(setOf("intro"), rows[3].detected)
        assertEquals(setOf("intro", "recap", "outro", "preview"), rows[2].missing)
    }

    @Test
    fun episodeOpenDismissesTheOverlayBeforeNavigationAndCanOpenAgain() {
        assertEquals(
            listOf(OverviewOpenStep.DismissOverlay, OverviewOpenStep.OpenEpisode),
            overviewEpisodeOpenSteps(),
        )
        val after = overviewSessionAfterEpisodeOpen()
        assertFalse(after.overlayVisible)
        assertTrue(overviewCanOpenAgain(after))
        assertEquals(overviewEpisodeOpenSteps(), overviewEpisodeOpenSteps())
    }

    @Test
    fun downloadsEntryDoesNotDownloadAgainAndSubmitModeDoesNotPersistProgress() {
        assertFalse(
            seasonOverviewAnalysisDownloadAllowed(
                fromDownloads = true,
                offline = true,
                state = SeasonOverviewUiState.Partial,
            ),
        )
        assertFalse(
            seasonOverviewAnalysisDownloadAllowed(
                fromDownloads = false,
                offline = true,
                state = SeasonOverviewUiState.Partial,
            ),
        )
        assertTrue(
            seasonOverviewAnalysisDownloadAllowed(
                fromDownloads = false,
                offline = false,
                state = SeasonOverviewUiState.NoneSubmitted,
            ),
        )
        val launch = PlayerLaunch(
            profileId = 1,
            title = "Show",
            sourceUrl = "file:///episode.mp4",
            streamTitle = "Episode",
            providerName = "Local",
            parentMetaId = "tt1",
            parentMetaType = "series",
            initialPositionMs = 50_000L,
            initialProgressFraction = 0.4f,
        ).applyingSubmitMode(true)
        assertTrue(launch.submitMode)
        assertFalse(launch.persistsProgress)
        assertEquals(0L, launch.initialPositionMs)
        assertEquals(null, launch.initialProgressFraction)
        assertFalse(allowsProgressWrite(launch.persistsProgress))
    }

    @Test
    fun localDetectionIgnoresUserMarksAndDoesNotBecomeSubmitted() {
        val detected = localDetectionTypes(
            MediaAnalysisResult(
                key = "show__s1e1",
                analyzedAtMs = 1L,
                durationMs = 1_200_000L,
                introStartMs = 0L,
                introEndMs = 40_000L,
                creditsStartMs = 1_000_000L,
                creditsEndMs = 1_100_000L,
                userSegments = mapOf("intro" to UserSegment(startMs = 0L, endMs = 40_000L, imported = true)),
                creditsConfidence = com.nuvio.app.features.analysis.SegmentConfidence(theIntroDbAgrees = true),
            ),
        )
        assertEquals(setOf("outro"), detected)
        val row = episodeSegmentOverview(
            episode = episode(1),
            own = OwnAccountMarks(),
            detectedTypes = detected,
        )
        assertTrue(row.submitted.isEmpty())
        assertEquals(setOf("outro"), row.detected)
    }

    @Test
    fun listStatusSplitsPendingConfirmedAndDuplicate() {
        assertEquals(ListStatusBucket.Pending, listStatusBucket("pending"))
        assertEquals(ListStatusBucket.Pending, listStatusBucket("submitted"))
        assertEquals(ListStatusBucket.Pending, listStatusBucket(""))
        assertEquals(ListStatusBucket.Confirmed, listStatusBucket("approved"))
        assertEquals(ListStatusBucket.Confirmed, listStatusBucket("duplicate"))
        assertEquals(ListStatusBucket.Confirmed, listStatusBucket("published"))
        assertEquals(ListStatusBucket.Rejected, listStatusBucket("rejected"))
        assertEquals(ListStatusBucket.Rejected, listStatusBucket("declined"))
        val buckets = ownListBucketsForTitle(
            records = listOf(
                FlagSubmissionRecord(7, "tv", 1, 1, "intro", "pending"),
                FlagSubmissionRecord(7, "tv", 1, 1, "recap", "approved"),
                FlagSubmissionRecord(7, "tv", 1, 1, "outro", "duplicate"),
                FlagSubmissionRecord(7, "tv", 1, 1, "preview", "rejected"),
                FlagSubmissionRecord(8, "tv", 1, 1, "intro", "approved"),
            ),
            tmdbId = 7,
            isMovie = false,
            season = 1,
            episode = 1,
        )
        assertEquals(setOf("recap", "outro"), buckets.confirmed)
        assertEquals(setOf("intro"), buckets.pending)
        assertEquals(setOf("preview"), buckets.rejected)
        val receipt = ownListBucketsForTitle(
            records = listOf(FlagSubmissionRecord(7, "tv", 1, 1, "intro", "submitted")),
            tmdbId = 7,
            isMovie = false,
            season = 1,
            episode = 1,
        )
        assertEquals(setOf("intro"), receipt.pending)
        assertTrue(receipt.confirmed.isEmpty())
        val otherAccount = episodeSegmentOverview(
            episode = episode(1),
            own = OwnAccountMarks(confirmed = mapOf("other" to setOf("intro"))),
            detectedTypes = setOf("intro"),
        )
        assertTrue(otherAccount.submitted.isEmpty())
        assertEquals(setOf("intro"), otherAccount.detected)
    }

    @Test
    fun sheetStaysAtTheLargeDetentForALongListAndShrinksForAShortOne() {
        val maximum = 800.0
        assertEquals(maximum, fitSheetDetentPoints(Double.POSITIVE_INFINITY, maximum))
        assertEquals(360.0, fitSheetDetentPoints(360.0, maximum))
        val longList = fitSheetMeasurement(
            sheetPoints = maximum,
            contentPoints = maximum,
            lockedToContent = false,
        )
        assertEquals(FitSheetAction.KeepMaximum, longList.action)
        val shortList = fitSheetMeasurement(
            sheetPoints = maximum,
            contentPoints = 360.0,
            lockedToContent = false,
        )
        assertEquals(FitSheetAction.Shrink, shortList.action)
        assertEquals(360.0, shortList.points)
        val settled = fitSheetMeasurement(
            sheetPoints = 360.0,
            contentPoints = 360.0,
            lockedToContent = true,
        )
        assertEquals(FitSheetAction.Unchanged, settled.action)
        val doesNotGrowAgain = fitSheetMeasurement(
            sheetPoints = 360.0,
            contentPoints = 700.0,
            lockedToContent = true,
        )
        assertEquals(FitSheetAction.Unchanged, doesNotGrowAgain.action)
    }

    @Test
    fun largestContentSizeCategoryScalesPastThree() {
        assertEquals(1f, fontScaleForIosContentCategory("UICTContentSizeCategoryL"))
        assertEquals(1f, fontScaleForIosContentCategory("UIContentSizeCategoryLarge"))
        assertEquals(3.12f, fontScaleForIosContentCategory("UICTContentSizeCategoryAccessibilityXXXL"))
        assertEquals(
            3.12f,
            fontScaleForIosContentCategory("UIContentSizeCategoryAccessibilityExtraExtraExtraLarge"),
        )
        assertTrue(fontScaleForIosContentCategory("UICTContentSizeCategoryAccessibilityXXXL") >= 3f)
        val applied = changedSurfaceDensity(density = 3f, fontScale = 3.12f)
        assertEquals(3f, applied.density)
        assertEquals(3.12f, applied.fontScale)
    }

    @Test
    fun theOverviewIsOnScreenOrDismissedAfterThePlayerReturns() {
        val portraitWidth = 402
        val portraitHeight = 874
        val opened = overviewSheetFrameAfterReturn(
            proposedLeft = 0,
            proposedTop = 40,
            proposedWidth = portraitWidth,
            proposedHeight = 700,
            windowWidth = portraitWidth,
            windowHeight = portraitHeight,
            deviceIsLandscape = false,
        )
        assertEquals("0,40,$portraitWidth,700", opened)

        val landscapeWidth = portraitHeight
        val landscapeHeight = portraitWidth
        val duringPlay = overviewSheetFrameAfterReturn(
            proposedLeft = 0,
            proposedTop = 0,
            proposedWidth = landscapeWidth,
            proposedHeight = landscapeHeight,
            windowWidth = landscapeWidth,
            windowHeight = landscapeHeight,
            deviceIsLandscape = true,
        )
        assertEquals("0,0,$landscapeWidth,$landscapeHeight", duringPlay)

        // The window is still the player size. The phone is portrait, so the list window is swapped.
        val stillLandscape = overviewSheetFrameAfterReturn(
            proposedLeft = 0,
            proposedTop = 0,
            proposedWidth = landscapeWidth,
            proposedHeight = landscapeHeight,
            windowWidth = landscapeWidth,
            windowHeight = landscapeHeight,
            deviceIsLandscape = false,
        )
        assertEquals("", stillLandscape)
        val portraitSheetWhileWindowIsLandscape = overviewSheetFrameAfterReturn(
            proposedLeft = 0,
            proposedTop = 40,
            proposedWidth = portraitWidth,
            proposedHeight = 700,
            windowWidth = landscapeWidth,
            windowHeight = landscapeHeight,
            deviceIsLandscape = false,
        )
        assertEquals("0,40,$portraitWidth,700", portraitSheetWhileWindowIsLandscape)

        val returned = overviewSheetFrameAfterReturn(
            proposedLeft = 0,
            proposedTop = 0,
            proposedWidth = landscapeWidth,
            proposedHeight = landscapeHeight,
            windowWidth = portraitWidth,
            windowHeight = portraitHeight,
            deviceIsLandscape = false,
        )
        assertEquals("", returned)

        val trailing = overviewSheetFrameAfterReturn(
            proposedLeft = 472,
            proposedTop = 0,
            proposedWidth = portraitWidth,
            proposedHeight = 700,
            windowWidth = portraitWidth,
            windowHeight = portraitHeight,
            deviceIsLandscape = false,
        )
        assertEquals("", trailing)
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
                watchPrivately = "Watch privately",
            ),
        ).map { it.id }
        assertEquals(listOf("season.watched", "season.rate", "season.previous", "season.overview"), ids)
        assertEquals(emptyList(), seasonOverviewRemovalList)
        assertEquals(
            listOf(
                "MetaDetailsScreen.SeasonWatchedActionSheet",
                "DownloadsScreen.ShowSeason",
            ),
            seasonOverviewCallers,
        )
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
