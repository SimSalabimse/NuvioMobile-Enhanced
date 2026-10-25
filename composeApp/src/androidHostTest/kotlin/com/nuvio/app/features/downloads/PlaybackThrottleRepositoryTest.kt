package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import com.nuvio.app.features.player.PlayerDownloadIndicator
import com.nuvio.app.features.player.playerDownloadIndicator
import com.nuvio.app.features.streams.StreamItem
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real repository and PlaybackPriority through a whole playback session, with a fake platform
 * layer that (like iOS) sends no callback when a transfer is stopped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaybackThrottleRepositoryTest {
    private class FakeTransfers : DownloadTransfers {
        val running = linkedSetOf<String>()
        val starts = mutableMapOf<String, Int>()

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            val id = request.item.id
            running += id
            starts[id] = (starts[id] ?: 0) + 1
            return object : DownloadsTaskHandle {
                override fun cancel() {
                    running -= id
                }
            }
        }

        override fun pauseRunningTransfer(item: DownloadItem) {
            running -= item.id
        }
    }

    private val fake = FakeTransfers()
    private var clock = 1_000_000L

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_downloads_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsSettingsStorage.initialize(context)
        DownloadsStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsSettingsRepository.clearLocalState()
        SmartDownloadSettings.setPauseDuringPlayback(false)
        PlaybackPriority.sessionEnded()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.transfers = fake
        DownloadsRepository.setPlaybackDownloadCap(MAX_ACTIVE_DOWNLOADS)
    }

    @After
    fun clear() {
        SmartDownloadSettings.setPauseDuringPlayback(false)
        PlaybackPriority.sessionEnded()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.transfers = PlatformDownloadTransfers
    }

    @Test
    fun smoothPlaybackKeepsOneDownloadMovingAndCloseResumesBoth() {
        startTwoDownloads()

        PlaybackPriority.sessionStarted(isLocalFile = false)
        snapshots(buffering = true, forMs = 2_000L)
        snapshots(buffering = false, forMs = 10 * 60_000L)

        assertEquals(PlaybackPriority.Level.Playing, PlaybackPriority.level)
        assertEquals(1, fake.running.size, "one download keeps going while playback is smooth")
        assertEveryDownloadingRowTransfers()

        PlaybackPriority.sessionEnded()
        assertEquals(setOf("e1", "e2"), fake.running)
        assertEveryDownloadingRowTransfers()
    }

    @Test
    fun downloadQueuedDuringPlaybackStartsAfterClose() {
        startTwoDownloads()
        PlaybackPriority.sessionStarted(isLocalFile = false)
        snapshots(buffering = false, forMs = 60_000L)

        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "show:1:3", parentMetaId = "show", parentMetaType = "series",
            title = "Show", logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 3,
            episodeTitle = null, episodeThumbnail = null,
            stream = StreamItem(name = "S1E3", url = "https://example.com/e3.mkv", addonName = "Test", addonId = "test"),
        )
        assertEquals(1, fake.running.size)
        assertEveryDownloadingRowTransfers()

        PlaybackPriority.sessionEnded()
        assertEquals(2, fake.running.size)
        assertEveryDownloadingRowTransfers()
        DownloadsRepository.uiState.value.items.filter { it.id in setOf("e1", "e2") }.forEach { row ->
            if (row.id !in fake.running) assertEquals(DownloadStatus.Queued, row.status)
        }
    }

    @Test
    fun downloadThePlayerReadsFromKeepsGoingWhenTheCapDrops() {
        startTwoDownloads()
        PartialPlaybackLease.acquire("e2")
        try {
            PlaybackPriority.sessionStarted(isLocalFile = true)
            snapshots(buffering = false, forMs = 60_000L)
            assertEquals(setOf("e2"), fake.running, "the file being played is the one that keeps going")

            snapshots(buffering = true, forMs = 2_000L)
            assertEquals(PlaybackPriority.Level.Strained, PlaybackPriority.level)
            assertEquals(setOf("e2"), fake.running, "pausing the played file would leave the player at its end")
            assertEveryDownloadingRowTransfers()

            PlaybackPriority.sessionEnded()
            assertEquals(setOf("e1", "e2"), fake.running)
        } finally {
            PartialPlaybackLease.release("e2")
        }
    }

    @Test
    fun pauseWhileWatchingStopsEveryDownloadAndCloseResumesThem() {
        SmartDownloadSettings.setPauseDuringPlayback(true)
        startTwoDownloads()

        PlaybackPriority.sessionStarted(isLocalFile = false)
        snapshots(buffering = false, forMs = 10 * 60_000L)
        assertTrue(fake.running.isEmpty(), "pause downloads while watching is on")
        DownloadsRepository.uiState.value.items.forEach { row ->
            assertEquals(DownloadStatus.Queued, row.status)
            assertEquals(PlayerDownloadIndicator.Waiting, playerDownloadIndicator(row))
        }

        PlaybackPriority.sessionEnded()
        assertEquals(setOf("e1", "e2"), fake.running)
        assertEveryDownloadingRowTransfers()
    }

    @Test
    fun episodeWaitingForTheCapShowsAStillIndicator() {
        startTwoDownloads()
        PlaybackPriority.sessionStarted(isLocalFile = false)
        snapshots(buffering = false, forMs = 10 * 60_000L)

        val waiting = DownloadsRepository.uiState.value.items.single { it.id !in fake.running }
        assertEquals(PlayerDownloadIndicator.Waiting, playerDownloadIndicator(waiting))
        val moving = DownloadsRepository.uiState.value.items.single { it.id in fake.running }
        assertEquals(DownloadStatus.Downloading, moving.status)
    }

    private fun startTwoDownloads() {
        DownloadsRepository.replaceItems(listOf(episode("e1", 1), episode("e2", 2)))
        DownloadsRepository.resumeDownloads(listOf("e1", "e2"))
        assertEquals(setOf("e1", "e2"), fake.running)
    }

    private fun snapshots(buffering: Boolean, forMs: Long) {
        val end = clock + forMs
        while (clock < end) {
            clock += 250L
            PlaybackPriority.noteSnapshot(wantsToPlay = true, buffering = buffering, nowMs = clock)
        }
    }

    private fun assertEveryDownloadingRowTransfers() {
        DownloadsRepository.uiState.value.items.filter { it.status == DownloadStatus.Downloading }.forEach { row ->
            assertTrue(row.id in fake.running, "${row.id} shows Downloading but nothing transfers")
        }
    }

    private fun episode(id: String, number: Int) = downloadItem(url = "https://example.com/$id.mkv", id = id).copy(
        contentType = "series",
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = "show:1:$number",
        seasonNumber = 1,
        episodeNumber = number,
        fileName = "$id.mkv",
        status = DownloadStatus.Paused,
        createdAtEpochMs = number.toLong(),
    )
}
