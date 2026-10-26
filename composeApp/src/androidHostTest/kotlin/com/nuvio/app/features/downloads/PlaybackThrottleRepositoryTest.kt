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
    private class Callbacks(
        val onProgress: (Long, Long?) -> Unit,
        val onSuccess: (String, Long?) -> Unit,
    )

    /**
     * [asyncOpens] is iOS: a start first looks up the session's tasks and only then begins the
     * transfer. A stop of a transfer that is still opening finds nothing running to stop.
     */
    private class FakeTransfers : DownloadTransfers {
        val running = linkedSetOf<String>()
        val opening = linkedSetOf<String>()
        val starts = mutableMapOf<String, Int>()
        val callbacks = mutableMapOf<String, Callbacks>()
        var asyncOpens = false

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            val id = request.item.id
            if (asyncOpens) opening += id else running += id
            starts[id] = (starts[id] ?: 0) + 1
            callbacks[id] = Callbacks(onProgress, onSuccess)
            return object : DownloadsTaskHandle {
                override fun cancel() {
                    running -= id
                    opening -= id
                }
            }
        }

        override fun pauseRunningTransfer(item: DownloadItem) {
            running -= item.id
        }

        /** The opens finish; each transfer reports its first bytes. */
        fun finishOpens() {
            val opened = opening.toList()
            opening.clear()
            opened.forEach { id ->
                running += id
                callbacks.getValue(id).onProgress(1_000_000L, 100_000_000L)
            }
        }

        /** iOS delivers a finished task on the session's queue, also one stopped as it finished. */
        fun finish(id: String) {
            running -= id
            callbacks.getValue(id).onSuccess("file:///downloads/$id.mkv", 100_000_000L)
        }
    }

    private val fake = FakeTransfers()
    private var clock = 1_000_000L
    private var position = 0L

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

    @Test
    fun shortLoadingBlipsDuringSmoothPlaybackNeverLeavePlaying() {
        startTwoDownloads()
        PlaybackPriority.sessionStarted(isLocalFile = false)
        snapshots(buffering = true, forMs = 3_000L)

        repeat(120) {
            snapshots(buffering = false, forMs = 4_500L)
            snapshots(buffering = true, forMs = 500L)
            assertEquals(PlaybackPriority.Level.Playing, PlaybackPriority.level)
            assertEquals(1, fake.running.size)
        }
        assertEveryDownloadingRowTransfers()
    }

    @Test
    fun realStallStrainsAndPlayingComesBackThirtySecondsAfterTheLastOne() {
        startTwoDownloads()
        PlaybackPriority.sessionStarted(isLocalFile = false)
        snapshots(buffering = false, forMs = 60_000L)
        val kept = fake.running.single()

        snapshots(buffering = true, forMs = 2_000L)
        assertEquals(PlaybackPriority.Level.Strained, PlaybackPriority.level)
        assertTrue(fake.running.isEmpty())

        snapshots(buffering = false, forMs = 20_000L)
        snapshots(buffering = true, forMs = 2_000L)
        repeat(5) {
            snapshots(buffering = false, forMs = 5_000L)
            snapshots(buffering = true, forMs = 500L)
        }
        snapshots(buffering = false, forMs = 2_000L)
        assertEquals(PlaybackPriority.Level.Strained, PlaybackPriority.level, "29.5 s after the last real stall")

        snapshots(buffering = false, forMs = 500L)
        assertEquals(PlaybackPriority.Level.Playing, PlaybackPriority.level)
        assertEquals(setOf(kept), fake.running, "the kept download is started again")
        assertEquals(2, fake.starts[kept])
        assertEveryDownloadingRowTransfers()
    }

    @Test
    fun initialLoadSeeksAndReloadsDoNotStrain() {
        startTwoDownloads()
        PlaybackPriority.sessionStarted(isLocalFile = false)
        snapshots(buffering = true, forMs = 6_000L)
        assertEquals(PlaybackPriority.Level.Playing, PlaybackPriority.level, "first load before a frame")

        snapshots(buffering = false, forMs = 60_000L)
        position += 10 * 60_000L
        snapshots(buffering = true, forMs = 5_000L)
        assertEquals(PlaybackPriority.Level.Playing, PlaybackPriority.level, "seek forward")

        snapshots(buffering = false, forMs = 10_000L)
        position -= 5 * 60_000L
        snapshots(buffering = true, forMs = 5_000L)
        assertEquals(PlaybackPriority.Level.Playing, PlaybackPriority.level, "seek back")

        snapshots(buffering = false, forMs = 10_000L)
        val resumeAt = position
        position = 0L
        snapshots(buffering = true, forMs = 3_000L)
        position = resumeAt
        snapshots(buffering = true, forMs = 3_000L)
        assertEquals(PlaybackPriority.Level.Playing, PlaybackPriority.level, "reload for a quality or audio switch")
        assertEquals(1, fake.running.size)

        snapshots(buffering = false, forMs = 10_000L)
        snapshots(buffering = true, forMs = 2_000L)
        assertEquals(PlaybackPriority.Level.Strained, PlaybackPriority.level, "a real stall still strains")
    }

    @Test
    fun playedPartialThatWaitsInLineDownloadsWhileItPlays() {
        startThreeDownloads()
        PartialPlaybackLease.acquire("e3")
        try {
            PlaybackPriority.sessionStarted(isLocalFile = false)
            snapshots(buffering = true, forMs = 2_000L)
            snapshots(buffering = false, forMs = 60_000L)
            assertEquals(setOf("e3"), fake.running, "the file being played takes the one slot")
            assertSlotsMatchStatuses()

            snapshots(buffering = true, forMs = 2_000L)
            assertEquals(PlaybackPriority.Level.Strained, PlaybackPriority.level)
            assertEquals(setOf("e3"), fake.running, "the played file keeps going while strained")
            assertSlotsMatchStatuses()
        } finally {
            PartialPlaybackLease.release("e3")
        }
    }

    @Test
    fun switchingToAWaitingPartialMidSessionStartsIt() {
        startThreeDownloads()
        PlaybackPriority.sessionStarted(isLocalFile = false)
        snapshots(buffering = false, forMs = 60_000L)
        assertEquals(setOf("e1"), fake.running)

        PartialPlaybackLease.acquire("e3")
        try {
            assertEquals(setOf("e3"), fake.running, "switching to e3's file starts its download")
            assertSlotsMatchStatuses()
            snapshots(buffering = false, forMs = 60_000L)
            assertEquals(setOf("e3"), fake.running)
        } finally {
            PartialPlaybackLease.release("e3")
        }
        assertSlotsMatchStatuses()
    }

    @Test
    fun nextEpisodeQueuedDuringStreamedPlaybackStartsAtOnce() {
        PlaybackPriority.sessionStarted(isLocalFile = false)
        snapshots(buffering = true, forMs = 2_000L)
        snapshots(buffering = false, forMs = 60_000L)

        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "show:1:2", parentMetaId = "show", parentMetaType = "series",
            title = "Show", logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 2,
            episodeTitle = null, episodeThumbnail = null,
            stream = StreamItem(name = "S1E2", url = "https://example.com/e2.mkv", addonName = "Test", addonId = "test"),
        )
        assertEquals(1, fake.running.size, "the next episode starts while the player is open")
        assertSlotsMatchStatuses()
    }

    @Test
    fun transferStillOpeningWhenTheCapStopsItDoesNotRunAsWaiting() {
        fake.asyncOpens = true
        DownloadsRepository.replaceItems(listOf(episode("e1", 1), episode("e2", 2)))
        DownloadsRepository.resumeDownloads(listOf("e1", "e2"))
        assertEquals(setOf("e1", "e2"), fake.opening)

        PlaybackPriority.sessionStarted(isLocalFile = false)
        fake.finishOpens()

        assertSlotsMatchStatuses()
        assertEquals(setOf("e1"), fake.running)
    }

    @Test
    fun fileThatFinishesAsTheCapStopsItShowsCompletedAtOnce() {
        startTwoDownloads()
        PlaybackPriority.sessionStarted(isLocalFile = false)
        val demoted = DownloadsRepository.uiState.value.items.single { it.status == DownloadStatus.Queued }.id

        fake.finish(demoted)

        val row = DownloadsRepository.uiState.value.items.single { it.id == demoted }
        assertEquals(DownloadStatus.Completed, row.status, "a finished file is not left Waiting")
        assertSlotsMatchStatuses()
    }

    private fun startThreeDownloads() {
        DownloadsRepository.replaceItems(listOf(episode("e1", 1), episode("e2", 2), episode("e3", 3)))
        DownloadsRepository.resumeDownloads(listOf("e1", "e2", "e3"))
        assertEquals(setOf("e1", "e2"), fake.running)
    }

    /** Downloading means a transfer runs; Waiting means none does, with nothing left to refresh it. */
    private fun assertSlotsMatchStatuses() {
        DownloadsRepository.uiState.value.items.forEach { row ->
            val transferring = row.id in fake.running || row.id in fake.opening
            when (row.status) {
                DownloadStatus.Downloading -> assertTrue(transferring, "${row.id} shows Downloading but nothing transfers")
                DownloadStatus.Queued -> assertTrue(!transferring, "${row.id} shows Waiting but holds a running transfer")
                else -> assertTrue(!transferring, "${row.id} is ${row.status} but still transfers")
            }
        }
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
            if (!buffering) position += 250L
            PlaybackPriority.noteSnapshot(wantsToPlay = true, buffering = buffering, positionMs = position, nowMs = clock)
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
