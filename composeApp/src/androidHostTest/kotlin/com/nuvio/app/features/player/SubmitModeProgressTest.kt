package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import androidx.compose.ui.Modifier
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.downloads.FinishedDownloadDeletion
import com.nuvio.app.features.downloads.SmartDownloadSettings
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.stats.WatchStats
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesStorage
import com.nuvio.app.features.watchprogress.ProgressWriteTrace
import com.nuvio.app.features.watchprogress.WatchProgressCodec
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import com.nuvio.app.features.watchprogress.allowsProgressWrite
import com.nuvio.app.navigation.EpisodeSubmitRequest
import com.nuvio.app.navigation.installEpisodeSubmitLauncher
import com.nuvio.app.navigation.openEpisodeInSubmitMode
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A submit-mode session must leave Continue Watching and the watched set byte-identical.
 * The same session with the flag off still writes progress and marks the episode watched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubmitModeProgressTest {
    private val showId = "tt999"
    private val durationMs = 2_400_000L
    private val episodeFourPositionMs = 500_000L

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        listOf(
            "nuvio_downloads_settings",
            "nuvio_downloads",
            "nuvio_watched",
            "nuvio_watch_progress",
            "nuvio_continue_watching_preferences",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        DownloadsSettingsStorage.initialize(context)
        DownloadsStorage.initialize(context)
        WatchedStorage.initialize(context)
        WatchProgressStorage.initialize(context)
        ContinueWatchingPreferencesStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsSettingsRepository.clearLocalState()
        DownloadsRepository.clearLocalState()
        WatchedRepository.clearLocalState()
        WatchProgressRepository.clearLocalState()
        ContinueWatchingPreferencesRepository.clearLocalState()
        FinishedDownloadDeletion.reset()
        FinishedDownloadDeletion.closeGraceMs = 0L
        SmartDownloadSettings.setEnabled(false)
        ProgressWriteTrace.clear()
        installEpisodeSubmitLauncher(null)
        File(context.filesDir, "downloads").mkdirs()
    }

    @After
    fun clearState() {
        installEpisodeSubmitLauncher(null)
        FinishedDownloadDeletion.reset()
        DownloadsRepository.clearLocalState()
        DownloadsSettingsRepository.clearLocalState()
        SmartDownloadSettings.setEnabled(false)
        WatchedRepository.clearLocalState()
        WatchProgressRepository.clearLocalState()
        ContinueWatchingPreferencesRepository.clearLocalState()
        ProgressWriteTrace.clear()
    }

    @Test
    fun submitModeLeavesContinueWatchingAndWatchedUntouched() {
        seedEpisodeFour()
        ProgressWriteTrace.clear()
        val before = snapshotProgress()

        val driven = driveSession(submitMode = true)

        val after = snapshotProgress()
        assertEquals(emptyList(), ProgressWriteTrace.snapshot())
        assertEquals(before, driven.afterPause)
        assertEquals(before, after)
        assertEquals(episodeFourPositionMs, episodeFourPosition())
        assertTrue(driven.panelOpen)
        assertEquals(0L, PlayerLaunchStore.get(driven.launchId)?.initialPositionMs)
        assertTrue(before.continueWatching.contains("tt999:1:4"))
        assertFalse(after.watched.contains("series:$showId:1:2"))
    }

    @Test
    fun ordinaryPlaybackStillWritesProgressAndMarksWatched() {
        seedEpisodeFour()
        ProgressWriteTrace.clear()
        val before = snapshotProgress()

        val driven = driveSession(submitMode = false)

        val after = snapshotProgress()
        assertTrue(ProgressWriteTrace.snapshot().contains("WatchProgressRepository.upsert"))
        assertTrue(driven.afterPause.entries != before.entries || driven.afterPause.continueWatching != before.continueWatching)
        assertTrue(after.watched.contains("series:$showId:1:2"))
        assertEquals(episodeFourPositionMs, episodeFourPosition())
        assertFalse(driven.panelOpen)
        assertTrue((PlayerLaunchStore.get(driven.launchId)?.initialPositionMs ?: 0L) > 0L)
    }

    @Test
    fun submitModeFlagZeroesTheLaunchPositionAndBlocksWrites() {
        val resumed = PlayerLaunch(
            profileId = 1,
            title = "Show",
            sourceUrl = "https://example.com/ep.mp4",
            streamTitle = "Source",
            providerName = "Provider",
            parentMetaId = showId,
            parentMetaType = "series",
            initialPositionMs = 400_000L,
            initialProgressFraction = 0.2f,
        )
        assertTrue(resumed.persistsProgress)
        assertEquals(resumed, resumed.applyingSubmitMode(false))
        val submitting = resumed.applyingSubmitMode(true)
        assertTrue(submitting.submitMode)
        assertFalse(submitting.persistsProgress)
        assertEquals(0L, submitting.initialPositionMs)
        assertNull(submitting.initialProgressFraction)
        assertFalse(allowsProgressWrite(submitting.persistsProgress))
        assertTrue(allowsProgressWrite(resumed.persistsProgress))
    }

    @Test
    fun openEpisodeInSubmitModeReachesTheInstalledLauncher() {
        var seen: EpisodeSubmitRequest? = null
        installEpisodeSubmitLauncher { seen = it }
        val request = EpisodeSubmitRequest(
            type = "series",
            videoId = "tt999:1:2",
            parentMetaId = showId,
            parentMetaType = "series",
            title = "Show",
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Two",
        )
        openEpisodeInSubmitMode(request)
        assertEquals(request, seen)
        installEpisodeSubmitLauncher(null)
        openEpisodeInSubmitMode(request)
        assertEquals(request, seen)
    }

    private fun seedEpisodeFour() {
        WatchProgressRepository.upsertPlaybackProgress(
            session = WatchProgressPlaybackSession(
                profileId = ProfileRepository.activeProfileId,
                contentType = "series",
                parentMetaId = showId,
                parentMetaType = "series",
                videoId = "tt999:1:4",
                title = "Show",
                seasonNumber = 1,
                episodeNumber = 4,
                episodeTitle = "Four",
                persistsProgress = true,
            ),
            snapshot = PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = false,
                positionMs = episodeFourPositionMs,
                durationMs = durationMs,
            ),
            syncRemote = false,
        )
    }

    private fun driveSession(submitMode: Boolean): Driven = runBlocking {
        val launch = PlayerLaunch(
            profileId = ProfileRepository.activeProfileId,
            title = "Show",
            sourceUrl = "https://example.com/ep2.mp4",
            streamTitle = "Source",
            providerName = "Provider",
            contentType = "series",
            videoId = "tt999:1:2",
            parentMetaId = showId,
            parentMetaType = "series",
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Two",
            initialPositionMs = 0L,
            submitMode = submitMode,
        )
        val launchId = PlayerLaunchStore.put(launch)
        val runtime = PlayerScreenRuntime(
            episodeArgs(submitMode = submitMode, launchId = launchId),
        )
        val job = SupervisorJob()
        runtime.scope = CoroutineScope(
            job + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> },
        )
        try {
            runtime.resetIdentityStateIfNeeded()
            val videoId = runtime.playbackSession.videoId
            val source = runtime.activeSourceUrl
            FinishedDownloadDeletion.playbackOpened(videoId, source, runtime.persistsProgress)
            fun sample(positionMs: Long, playing: Boolean, ended: Boolean) {
                runtime.updatePlaybackSnapshot(
                    PlayerPlaybackSnapshot(
                        isLoading = false,
                        isPlaying = playing,
                        isEnded = ended,
                        positionMs = positionMs,
                        durationMs = durationMs,
                    ),
                )
                runtime.onPlaybackProgressSample()
            }
            sample(30_000L, playing = true, ended = false)
            sample(120_000L, playing = true, ended = false)
            runtime.scheduleProgressSyncAfterSeek()
            delay(PlayerSeekProgressSyncDebounceMs + 250L)
            sample(120_000L, playing = false, ended = false)
            val afterPause = snapshotProgress()
            sample(120_000L, playing = true, ended = false)
            sample(2_300_000L, playing = false, ended = true)
            runtime.onPlaybackSourceDisposed(videoId, source, runtime.activeSourceAudioUrl)
            runtime.releaseServerPlayback(source)
            FinishedDownloadDeletion.playbackPlaying(videoId, runtime.persistsProgress)
            FinishedDownloadDeletion.linkDownload(videoId, "download-1", runtime.persistsProgress)
            WatchStats.recordSkip(5_000L, runtime.persistsProgress)
            recordExternalPlaybackProgress(
                result = ExternalPlaybackResult(
                    positionMs = 80_000L,
                    durationMs = durationMs,
                    endedByUser = false,
                    playbackSession = runtime.playbackSession,
                ),
                fallbackSession = null,
            )
            runtime.onPlayerRouteDisposed()
            Driven(
                afterPause = afterPause,
                launchId = launchId,
                panelOpen = runtime.showSubmitIntroModal,
            )
        } finally {
            job.cancel()
        }
    }

    private fun episodeArgs(submitMode: Boolean, launchId: Long) = PlayerScreenArgs(
        profileId = ProfileRepository.activeProfileId,
        title = "Show",
        sourceUrl = "https://example.com/ep2.mp4",
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
        seasonNumber = 1,
        episodeNumber = 2,
        episodeTitle = "Two",
        episodeThumbnail = null,
        contentType = "series",
        videoId = "tt999:1:2",
        parentMetaId = showId,
        parentMetaType = "series",
        providerAddonId = null,
        torrentInfoHash = null,
        torrentFileIdx = null,
        torrentFilename = null,
        torrentTrackers = emptyList(),
        initialPositionMs = 0L,
        initialProgressFraction = null,
        launchId = launchId,
        submitMode = submitMode,
    )

    private fun episodeFourPosition(): Long? = WatchProgressRepository.progressForVideo(
        videoId = "tt999:1:4",
        parentMetaId = showId,
        seasonNumber = 1,
        episodeNumber = 4,
    )?.lastPositionMs

    private fun snapshotProgress(): ProgressBytes = ProgressBytes(
        continueWatching = WatchProgressCodec.encodeEntries(WatchProgressRepository.continueWatching()),
        entries = WatchProgressCodec.encodeEntries(WatchProgressRepository.uiState.value.entries),
        watched = WatchedRepository.uiState.value.watchedKeys.sorted().joinToString("\n"),
        dismissedNextUp = ContinueWatchingPreferencesRepository.uiState.value.dismissedNextUpKeys
            .sorted()
            .joinToString("\n"),
    )

    private data class ProgressBytes(
        val continueWatching: String,
        val entries: String,
        val watched: String,
        val dismissedNextUp: String,
    )

    private data class Driven(
        val afterPause: ProgressBytes,
        val launchId: Long,
        val panelOpen: Boolean,
    )
}
