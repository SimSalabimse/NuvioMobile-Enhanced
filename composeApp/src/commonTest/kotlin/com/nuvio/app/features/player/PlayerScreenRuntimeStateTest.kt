package com.nuvio.app.features.player

import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerScreenRuntimeStateTest {

    @Test
    fun controlsStartHidden() {
        assertFalse(PlayerScreenRuntime(testPlayerScreenArgs()).controlsVisible)
    }

    @Test
    fun endedSnapshotRetainsDurationOnlyForTheSamePlayback() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(durationMs = 30_000L))
        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isLoading = false, isEnded = true))
        assertEquals(30_000L, runtime.playbackSnapshot.durationMs)
        assertFalse(runtime.isAtNextEpisodeThreshold())

        runtime.activeSourceUrl = "https://example.com/another.mp4"
        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot())
        assertEquals(0L, runtime.playbackSnapshot.durationMs)

        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(durationMs = 121_000L))
        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isLoading = false, isEnded = true))
        assertTrue(runtime.isAtNextEpisodeThreshold())
    }

    @Test
    fun shortErrorClipsDoNotStartOrCompleteScrobbling() = runTest {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs()).apply { scope = backgroundScope }
        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(
            isLoading = false, isPlaying = true, positionMs = 29_000L, durationMs = 30_000L,
        ))
        runtime.emitTrackingScrobbleStart()
        assertFalse(runtime.hasRequestedScrobbleStartForCurrentItem)
        runtime.emitStopScrobbleForCurrentProgress()
        assertFalse(runtime.hasSentCompletionScrobbleForCurrentItem)

        runtime.hasRequestedScrobbleStartForCurrentItem = true
        runtime.scrobbleStartRequestGeneration = 1L
        runtime.emitTrackingScrobblePause()
        runtime.emitTrackingScrobbleStop()
        assertEquals(1L, runtime.scrobbleStartRequestGeneration)
    }

    @Test
    fun bufferedScrubKeepsReleasedPositionUntilThePlayerAcknowledgesIt() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val buffering = PlayerPlaybackSnapshot(isLoading = true, positionMs = 30_000L, durationMs = 120_000L)
        runtime.playbackSnapshot = buffering
        runtime.isScrubbingTimeline = true
        runtime.scrubbingPositionMs = 80_000L

        runtime.finishTimelineScrub(80_000L)
        assertFalse(runtime.isScrubbingTimeline)
        assertEquals(80_000L, runtime.scrubbingPositionMs)
        runtime.updatePlaybackSnapshot(buffering)
        assertEquals(80_000L, runtime.scrubbingPositionMs)
        runtime.updatePlaybackSnapshot(buffering.copy(positionMs = 30_250L))
        assertEquals(80_000L, runtime.scrubbingPositionMs)

        runtime.updatePlaybackSnapshot(buffering.copy(positionMs = 80_000L))
        assertNull(runtime.scrubbingPositionMs)
        assertEquals(80_000L, runtime.playbackClock.positionMs)
        assertEquals(0L, runtime.playbackSnapshot.positionMs)
    }

    @Test
    fun backwardScrubAlsoKeepsTheTargetDuringBuffering() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val buffering = PlayerPlaybackSnapshot(isLoading = true, positionMs = 90_000L, durationMs = 120_000L)
        runtime.playbackSnapshot = buffering

        runtime.finishTimelineScrub(20_000L)
        runtime.updatePlaybackSnapshot(buffering)
        assertEquals(20_000L, runtime.scrubbingPositionMs)

        runtime.updatePlaybackSnapshot(buffering.copy(positionMs = 20_100L))
        assertNull(runtime.scrubbingPositionMs)
    }

    @Test
    fun bufferingEndReleasesThePreviewEvenWhenThePlayerLandsOnAnotherKeyframe() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = true, positionMs = 30_000L)
        runtime.finishTimelineScrub(80_000L)

        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isLoading = false, positionMs = 78_000L))

        assertNull(runtime.scrubbingPositionMs)
        assertEquals(78_000L, runtime.playbackClock.positionMs)
        assertEquals(0L, runtime.playbackSnapshot.positionMs)
    }

    @Test
    fun activePlaybackKeepsItsExistingScrubReleaseBehavior() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 30_000L)
        runtime.playbackClock.positionMs = 30_000L
        runtime.isScrubbingTimeline = true
        runtime.scrubbingPositionMs = 80_000L

        runtime.finishTimelineScrub(80_000L)

        assertFalse(runtime.isScrubbingTimeline)
        assertNull(runtime.scrubbingPositionMs)
        assertEquals(30_000L to 80_000L, runtime.lastManualSkipSeekPositions)
    }

    @Test
    fun oldSeekUpdatesDoNotOverrideANewerScrub() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = true, positionMs = 30_000L)
        runtime.finishTimelineScrub(80_000L)
        runtime.isScrubbingTimeline = true
        runtime.scrubbingPositionMs = 100_000L

        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isLoading = false, positionMs = 80_000L))

        assertTrue(runtime.isScrubbingTimeline)
        assertEquals(100_000L, runtime.scrubbingPositionMs)
    }

    @Test
    fun tappingNextEpisodeDuringSearchKeepsTheCurrentJob() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val job = Job()
        runtime.nextEpisodeAutoPlayJob = job
        runtime.nextEpisodeAutoPlaySearching = true

        runtime.playNextEpisode()

        assertSame(job, runtime.nextEpisodeAutoPlayJob)
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun tappingNextEpisodeDuringCountdownKeepsTheCurrentJob() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val job = Job()
        runtime.nextEpisodeAutoPlayJob = job
        runtime.nextEpisodeAutoPlayCountdown = 2

        runtime.playNextEpisode()

        assertSame(job, runtime.nextEpisodeAutoPlayJob)
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun positionOnlyTicksKeepTheStatusSnapshot() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.updatePlaybackSnapshot(
            PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = true,
                positionMs = 1_000L,
                bufferedPositionMs = 5_000L,
                durationMs = 60_000L,
            ),
        )
        val status = runtime.playbackSnapshot
        assertEquals(0L, status.positionMs)
        assertEquals(0L, status.bufferedPositionMs)
        assertEquals(1_000L, runtime.playbackClock.positionMs)
        assertEquals(5_000L, runtime.playbackClock.bufferedPositionMs)

        assertTrue(
            runtime.updatePlaybackSnapshot(
                status.copy(positionMs = 1_250L, bufferedPositionMs = 6_000L),
            ),
        )
        assertSame(status, runtime.playbackSnapshot)
        assertEquals(1_250L, runtime.playbackClock.positionMs)
        assertEquals(6_000L, runtime.playbackClock.bufferedPositionMs)
    }

    @Test
    fun parentalGuideDoesNotRevealPlaybackControls() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.parentalWarnings = listOf(ParentalWarning(label = "Violence", severity = "Mild"))

        runtime.tryShowParentalGuide()

        assertTrue(runtime.showParentalGuide)
        assertFalse(runtime.controlsVisible)
    }

    @Test
    fun sourceFilterUpdatesInvalidateUiWithoutPlaybackUpdates() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val selectedFilter = derivedStateOf { runtime.sourceStreamsState.selectedFilter }

        assertNull(selectedFilter.value)

        runtime.sourceStreamsState = StreamsUiState(selectedFilter = "addon-id")

        assertEquals("addon-id", selectedFilter.value)
    }

    @Test
    fun episodeFilterUpdatesInvalidateUiWithoutPlaybackUpdates() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val selectedFilter = derivedStateOf { runtime.episodeStreamsRepoState.selectedFilter }

        assertNull(selectedFilter.value)

        runtime.episodeStreamsRepoState = StreamsUiState(selectedFilter = "addon-id")

        assertEquals("addon-id", selectedFilter.value)
    }

    @Test
    fun restoredLaunchResumesFromTheCurrentPosition() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs().copy(initialPositionMs = 351_000L))

        assertEquals(351_000L, runtime.currentLaunch(testPlayerLaunch()).initialPositionMs)

        runtime.initialSeekApplied = true
        runtime.updatePlaybackSnapshot(
            PlayerPlaybackSnapshot(isPlaying = true, positionMs = 442_000L, durationMs = 1_200_000L),
        )

        assertEquals(442_000L, runtime.currentLaunch(testPlayerLaunch()).initialPositionMs)
    }

    @Test
    fun restoredLaunchFollowsTheActiveEpisode() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.updatePlaybackSnapshot(
            PlayerPlaybackSnapshot(isPlaying = true, positionMs = 442_000L, durationMs = 1_200_000L),
        )
        runtime.activeSourceUrl = "https://example.com/episode-2.mp4"
        runtime.activeVideoId = "tt1234567:1:2"
        runtime.activeSeasonNumber = 1
        runtime.activeEpisodeNumber = 2
        runtime.activeInitialPositionMs = 60_000L

        val launch = runtime.currentLaunch(testPlayerLaunch())

        assertEquals("https://example.com/episode-2.mp4", launch.sourceUrl)
        assertEquals("tt1234567:1:2", launch.videoId)
        assertEquals(1, launch.seasonNumber)
        assertEquals(2, launch.episodeNumber)
        assertEquals(60_000L, launch.initialPositionMs)
    }

    @Test
    fun seekScrobbleUpdate_requiresActiveIncompletePlayback() {
        assertTrue(
            shouldUpdateTrackingScrobbleAfterSeek(
                hasActiveScrobble = true,
                progressPercent = 50f,
            ),
        )
        assertFalse(
            shouldUpdateTrackingScrobbleAfterSeek(
                hasActiveScrobble = false,
                progressPercent = 50f,
            ),
        )
        assertFalse(
            shouldUpdateTrackingScrobbleAfterSeek(
                hasActiveScrobble = true,
                progressPercent = 80f,
            ),
        )
    }

    @Test
    fun stopScrobble_closesActiveSessionBelowOnePercent() {
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = true,
                progressPercent = 0f,
            ),
        )
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = true,
                progressPercent = 0.5f,
            ),
        )
    }

    @Test
    fun stopScrobble_skipsEarlyProgressWithoutActiveSession() {
        assertFalse(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                progressPercent = 0.5f,
            ),
        )
        assertFalse(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                progressPercent = 79.99f,
            ),
        )
    }

    @Test
    fun openingOnePlayerMenuClosesTheOthers() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.playerSettingsUiState = PlayerSettingsUiState(useExperimentalPlayerOverlay = true)
        PlayerMenu.entries.forEach { menu ->
            runtime.openEveryPlayerMenuFlag()
            assertTrue(runtime.beginPlayerMenu(menu))
            runtime.assertOnlyPlayerMenu(menu)
        }
    }

    @Test
    fun errorAndP2pCardsReplaceEveryPlayerMenu() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.openEveryPlayerMenuFlag()
        runtime.errorMessage = "Playback failed"
        assertTrue(runtime.blockingPlayerCardOpen())
        assertFalse(runtime.beginPlayerMenu(PlayerMenu.Audio))
        runtime.assertOnlyPlayerMenu(null)

        runtime.errorMessage = null
        runtime.openEveryPlayerMenuFlag()
        runtime.pendingP2pSwitch = PendingPlayerP2pSwitch(
            stream = StreamItem(addonName = "Addon", addonId = "addon"),
            episode = null,
            isAutoPlay = false,
        )
        assertTrue(runtime.blockingPlayerCardOpen())
        assertFalse(runtime.beginPlayerMenu(PlayerMenu.Quality))
        runtime.assertOnlyPlayerMenu(null)
        assertFalse(runtime.episodeStreamsPanelState.showStreams)
    }

    @Test
    fun stopScrobble_allowsCompletionWithoutActiveSession() {
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                progressPercent = 80f,
            ),
        )
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                progressPercent = 100f,
            ),
        )
    }

    private fun PlayerScreenRuntime.openEveryPlayerMenuFlag() {
        showSubtitleModal = true
        showAudioModal = true
        showStreamInfoModal = true
        showLiveChannelsPanel = true
        showSubmitIntroModal = true
        showUserRatingSheet = true
        showSourcesPanel = true
        showEpisodesPanel = true
        showQualityPanel = true
        showVideoSettingsModal = true
        showSubtitleSyncByEar = true
    }

    private fun PlayerScreenRuntime.assertOnlyPlayerMenu(menu: PlayerMenu?) {
        assertEquals(menu == PlayerMenu.Subtitles, showSubtitleModal)
        assertEquals(menu == PlayerMenu.Audio, showAudioModal)
        assertEquals(menu == PlayerMenu.PlaybackInfo, showStreamInfoModal)
        assertEquals(menu == PlayerMenu.LiveChannels, showLiveChannelsPanel)
        assertEquals(menu == PlayerMenu.SubmitIntro, showSubmitIntroModal)
        assertEquals(menu == PlayerMenu.Rate, showUserRatingSheet)
        assertEquals(menu == PlayerMenu.Sources, showSourcesPanel)
        assertEquals(menu == PlayerMenu.Episodes, showEpisodesPanel)
        assertEquals(menu == PlayerMenu.Quality, showQualityPanel)
        assertEquals(menu == PlayerMenu.VideoSettings, showVideoSettingsModal)
        assertFalse(showSubtitleSyncByEar)
    }

    private fun testPlayerScreenArgs() = PlayerScreenArgs(
        profileId = 1,
        title = "Title",
        sourceUrl = "https://example.com/video.mp4",
        sourceAudioUrl = null,
        sourceHeaders = emptyMap(),
        sourceResponseHeaders = emptyMap(),
        streamType = null,
        providerName = "Provider",
        streamTitle = "Source",
        streamSubtitle = null,
        initialBingeGroup = null,
        pauseDescription = null,
        onBack = {},
        onOpenInExternalPlayer = null,
        onOpenExternalUrl = null,
        modifier = Modifier,
        logo = null,
        poster = null,
        background = null,
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        episodeThumbnail = null,
        contentType = "movie",
        videoId = "tt1234567",
        parentMetaId = "tt1234567",
        parentMetaType = "movie",
        providerAddonId = null,
        torrentInfoHash = null,
        torrentFileIdx = null,
        torrentFilename = null,
        torrentTrackers = emptyList(),
        initialPositionMs = 0L,
        initialProgressFraction = null,
    )

    private fun testPlayerLaunch() = PlayerLaunch(
        profileId = 1,
        title = "Title",
        sourceUrl = "https://example.com/video.mp4",
        streamTitle = "Source",
        providerName = "Provider",
        contentType = "movie",
        videoId = "tt1234567",
        parentMetaId = "tt1234567",
        parentMetaType = "movie",
    )
}
