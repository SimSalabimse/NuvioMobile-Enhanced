package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Offline advance from one downloaded episode to the next. The played URL is the local file,
 * and the player surface key from episode 1 is kept so mpv is not destroyed at end of file.
 */
class PlayerNextDownloadedEpisodeTest {

    @Test
    fun nextEpisodePlaysTheDownloadedFileAndKeepsThePlayer() {
        val dir = File(System.getProperty("java.io.tmpdir"), "nuvio-next-ep-${System.nanoTime()}").apply { mkdirs() }
        try {
            val episode1 = File(dir, "episode-1.mp4").apply { writeBytes(byteArrayOf(0, 0, 0, 1)) }
            val episode2 = File(dir, "episode-2.mp4").apply { writeBytes(byteArrayOf(0, 0, 0, 2)) }
            val episode1Uri = episode1.toURI().toString()
            val episode2Uri = episode2.toURI().toString()
            DownloadsRepository.replaceItems(
                listOf(
                    download("dl-1", "tt1:1:1", 1, episode1.name, episode1Uri),
                    download("dl-2", "tt1:1:2", 2, episode2.name, episode2Uri),
                ),
            )
            val runtime = playingEpisode1(episode1Uri)
            runtime.downloadSwap = PlayerDownloadSwap(
                videoId = "tt1:1:1",
                downloadId = "dl-1",
                streamUrl = "https://example.test/episode-1.mkv",
                streamHeaders = emptyMap(),
                streamResponseHeaders = emptyMap(),
                streamType = null,
                localUrl = episode1Uri,
                partial = false,
                phase = DownloadSwapPhase.Playing,
                startMs = null,
                startedAtMs = 0L,
                restore = SwapTrackRestore(1f, null, null, subtitlesOff = true, addonSubtitleUrl = null),
            )
            val surfaceBefore = runtime.activePlaybackKey
            runtime.playbackSnapshot = PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = true,
                durationMs = 1_200_000L,
                positionMs = 1_200_000L,
            )
            runtime.shouldPlay = false

            runtime.playNextEpisode()

            assertTrue(
                runtime.activeSourceUrl.contains("episode-2.mp4"),
                "played ${runtime.activeSourceUrl}",
            )
            assertFalse(runtime.activeSourceUrl.startsWith("http"))
            assertEquals(runtime.activeSourceUrl, runtime.activePlaybackSourceUrl)
            assertEquals("tt1:1:2", runtime.activeVideoId)
            assertEquals(1, runtime.activeSeasonNumber)
            assertEquals(2, runtime.activeEpisodeNumber)
            assertTrue(runtime.shouldPlay)
            assertFalse(runtime.playbackSnapshot.isEnded)
            assertTrue(runtime.localEpisodeHandoff)
            assertEquals(surfaceBefore, runtime.retainedLocalSurfaceKey)
            assertNull(runtime.downloadSwap)
            assertNull(runtime.nextEpisodeAutoPlayJob)
        } finally {
            DownloadsRepository.clearLocalState()
            dir.deleteRecursively()
        }
    }

    @Test
    fun nextEpisodePlaysTheLocalFileWhenTheCatalogIdDoesNotMatch() {
        val dir = File(System.getProperty("java.io.tmpdir"), "nuvio-next-ep-id-${System.nanoTime()}").apply { mkdirs() }
        try {
            val episode1 = File(dir, "episode-1.mp4").apply { writeBytes(byteArrayOf(0)) }
            val episode2 = File(dir, "episode-2.mp4").apply { writeBytes(byteArrayOf(1)) }
            DownloadsRepository.replaceItems(
                listOf(
                    download("dl-1", "stored-ep-1", 1, episode1.name, episode1.toURI().toString()),
                    download("dl-2", "stored-ep-2", 2, episode2.name, episode2.toURI().toString()),
                ),
            )
            val runtime = playingEpisode1(episode1.toURI().toString())
            runtime.playerMetaVideos = emptyList()

            runtime.playNextEpisode()

            assertTrue(runtime.activeSourceUrl.contains("episode-2.mp4"), "played ${runtime.activeSourceUrl}")
            assertEquals(2, runtime.activeEpisodeNumber)
            assertTrue(runtime.shouldPlay)
            assertNull(runtime.nextEpisodeAutoPlayJob)
        } finally {
            DownloadsRepository.clearLocalState()
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingNextEpisodeDoesNotThrow() {
        val runtime = playingEpisode1("file:///episode-1.mp4")
        runtime.nextEpisodeInfo = null
        runtime.playerMetaVideos = emptyList()
        runtime.playNextEpisode()
        assertTrue(runtime.activeSourceUrl.contains("episode-1.mp4"))
        assertNull(PlayerNextEpisodeRules.resolveNextEpisodeVideo(null, emptyList()))
    }

    private fun playingEpisode1(episode1Uri: String): PlayerScreenRuntime {
        val runtime = PlayerScreenRuntime(
            PlayerScreenArgs(
                profileId = 1,
                title = "Show",
                sourceUrl = episode1Uri,
                sourceAudioUrl = null,
                sourceHeaders = emptyMap(),
                sourceResponseHeaders = emptyMap(),
                streamType = null,
                providerName = "Downloads",
                streamTitle = "Episode 1",
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
                episodeNumber = 1,
                episodeTitle = "Episode 1",
                episodeThumbnail = null,
                contentType = "series",
                videoId = "tt1:1:1",
                parentMetaId = "tt1",
                parentMetaType = "series",
                providerAddonId = null,
                torrentInfoHash = null,
                torrentFileIdx = null,
                torrentFilename = null,
                torrentTrackers = emptyList(),
                initialPositionMs = 0L,
                initialProgressFraction = null,
            ),
        )
        runtime.scope = CoroutineScope(SupervisorJob())
        runtime.playerMetaVideos = listOf(
            MetaVideo(id = "tt1:1:1", title = "Episode 1", season = 1, episode = 1),
            MetaVideo(id = "tt1:1:2", title = "Episode 2", season = 1, episode = 2),
        )
        runtime.nextEpisodeInfo = NextEpisodeInfo(
            videoId = "tt1:1:2",
            season = 1,
            episode = 2,
            title = "Episode 2",
            thumbnail = null,
            overview = null,
            released = null,
            hasAired = true,
            isWatched = false,
            unairedMessage = null,
        )
        return runtime
    }

    private fun download(
        id: String,
        videoId: String,
        episode: Int,
        fileName: String,
        localFileUri: String,
    ) = DownloadItem(
        id = id,
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        videoId = videoId,
        title = "Show",
        seasonNumber = 1,
        episodeNumber = episode,
        episodeTitle = "Episode $episode",
        streamTitle = "Episode $episode",
        providerName = "Downloads",
        sourceUrl = "https://example.test/$fileName",
        localFileUri = localFileUri,
        fileName = fileName,
        status = DownloadStatus.Completed,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
