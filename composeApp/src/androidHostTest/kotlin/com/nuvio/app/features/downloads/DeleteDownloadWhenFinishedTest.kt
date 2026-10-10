package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktHistory
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import java.io.File
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeleteDownloadWhenFinishedTest {
    private val videoId = "tt123"
    private lateinit var directory: File

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_downloads_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_watched", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_watch_progress", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsSettingsStorage.initialize(context)
        DownloadsStorage.initialize(context)
        WatchedStorage.initialize(context)
        WatchProgressStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsSettingsRepository.clearLocalState()
        DownloadsRepository.clearLocalState()
        WatchedRepository.clearLocalState()
        WatchProgressRepository.clearLocalState()
        FinishedDownloadDeletion.reset()
        directory = File(context.filesDir, "downloads").apply { mkdirs() }
    }

    @After
    fun clearState() {
        FinishedDownloadDeletion.reset()
        DownloadsRepository.clearLocalState()
        DownloadsSettingsRepository.clearLocalState()
        WatchedRepository.clearLocalState()
        WatchProgressRepository.clearLocalState()
    }

    @Test
    fun deleteWhenFinishedIsOffByDefaultAndRoundTrips() {
        DownloadsSettingsRepository.ensureLoaded()
        assertFalse(DownloadsSettingsRepository.deleteWhenFinished.value)

        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        DownloadsSettingsRepository.clearLocalState()
        DownloadsSettingsRepository.ensureLoaded()

        assertTrue(DownloadsSettingsRepository.deleteWhenFinished.value)
    }

    @Test
    fun completedPlaybackRemovesTheFileSubtitlesAndRowAfterThePlayerLeaves(): Unit = runBlocking {
        val stored = seedCompletedDownload()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.playbackOpened(videoId, stored.localFileUri!!)
        WatchingActions.onProgressEntryUpdated(completedEntry())

        assertTrue(videoFile().isFile)
        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)

        FinishedDownloadDeletion.playbackEnded(videoId)
        awaitRemoval(stored)

        assertFalse(videoFile().exists())
        assertFalse(partFile().exists())
        assertFalse(subtitleDirectory().exists())
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun scrubbingBackKeepsTheFileUntilPlaybackLeaves(): Unit = runBlocking {
        val stored = seedCompletedDownload()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.playbackOpened(videoId, stored.localFileUri!!)
        WatchingActions.onProgressEntryUpdated(completedEntry())
        WatchingActions.onProgressEntryUpdated(completedEntry().copy(isCompleted = false, lastPositionMs = 1_000L))

        assertTrue(videoFile().isFile)
        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)

        FinishedDownloadDeletion.playbackEnded(videoId)
        awaitRemoval(stored)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun movingToAnotherVideoRemovesTheFinishedDownload(): Unit = runBlocking {
        val stored = seedCompletedDownload()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.playbackOpened(videoId, stored.localFileUri!!)
        WatchingActions.onProgressEntryUpdated(completedEntry())

        FinishedDownloadDeletion.playbackOpened("tt123:1:2", "https://example.com/next.mkv")
        awaitRemoval(stored)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun anInProgressDownloadStaysWhenPlaybackCrossesTheThreshold(): Unit = runBlocking {
        val partial = loopbackPartialUrl(9, "early-download")
        val item = downloadItem(id = "early-download").copy(
            videoId = videoId,
            parentMetaId = videoId,
            status = DownloadStatus.Downloading,
            earlyPlayReady = true,
            fileName = "movie.mkv",
        )
        partFile().writeText("partial")
        DownloadsRepository.replaceItems(listOf(item))
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.playbackOpened(videoId, partial)
        WatchingActions.onProgressEntryUpdated(completedEntry())

        DownloadsRepository.replaceItems(listOf(item.copy(status = DownloadStatus.Completed, localFileUri = videoFile().apply { writeText("done") }.toURI().toString())))
        FinishedDownloadDeletion.playbackSourceChanged(videoId, DownloadsRepository.uiState.value.items.single().localFileUri!!)
        WatchingActions.onProgressEntryUpdated(completedEntry())
        FinishedDownloadDeletion.playbackEnded(videoId)

        assertEquals(DownloadStatus.Completed, DownloadsRepository.uiState.value.items.single().status)
        assertTrue(partFile().isFile)
        assertTrue(videoFile().isFile)
    }

    @Test
    fun completionAfterTheBytesFinishStillRemovesTheDownload(): Unit = runBlocking {
        val partial = loopbackPartialUrl(9, "early-download")
        val item = downloadItem(id = "early-download").copy(
            videoId = videoId,
            parentMetaId = videoId,
            status = DownloadStatus.Downloading,
            earlyPlayReady = true,
            fileName = "movie.mkv",
        )
        DownloadsRepository.replaceItems(listOf(item))
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.playbackOpened(videoId, partial)

        val finished = seedCompletedDownload(id = "early-download")
        FinishedDownloadDeletion.playbackSourceChanged(videoId, finished.localFileUri!!)
        WatchingActions.onProgressEntryUpdated(completedEntry())
        assertTrue(videoFile().isFile)

        FinishedDownloadDeletion.playbackEnded(videoId)
        awaitRemoval(finished)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun switchOffManualWatchedAndRemoteSyncLeaveTheFile(): Unit = runBlocking {
        val stored = seedCompletedDownload()
        val sourceUrl = checkNotNull(stored.localFileUri)
        DownloadsSettingsRepository.setDeleteWhenFinished(false)
        FinishedDownloadDeletion.playbackOpened(videoId, sourceUrl)
        WatchingActions.onProgressEntryUpdated(completedEntry())
        FinishedDownloadDeletion.playbackEnded(videoId)
        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)
        assertTrue(videoFile().isFile)

        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.playbackOpened(videoId, sourceUrl)
        WatchingActions.toggleEpisodeWatched(
            meta = MetaDetails(id = videoId, type = "movie", name = "Movie"),
            episode = MetaVideo(id = videoId, title = "Movie"),
            isCurrentlyWatched = false,
        )
        WatchedRepository.markWatched(
            WatchedItem(id = videoId, type = "movie", name = "Movie", markedAtEpochMs = 1L),
        )
        WatchingActions.onProgressEntryUpdated(
            completedEntry().copy(source = WatchProgressSourceTraktHistory),
        )
        FinishedDownloadDeletion.reset()
        WatchingActions.onProgressEntryUpdated(completedEntry())
        FinishedDownloadDeletion.playbackEnded(videoId)

        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)
        assertTrue(videoFile().isFile)
        assertTrue(subtitleDirectory().isDirectory)
    }

    @Test
    fun streamingPlaybackDoesNotRemoveAFinishedDownloadOfTheSameTitle(): Unit = runBlocking {
        val stored = seedCompletedDownload()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.playbackOpened(videoId, "https://example.com/stream.mkv")
        WatchingActions.onProgressEntryUpdated(completedEntry())
        FinishedDownloadDeletion.playbackEnded(videoId)

        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)
        assertTrue(videoFile().isFile)
    }

    @Test
    fun deletingAnItemThatIsAlreadyGoneIsANoOp() {
        val stored = seedCompletedDownload()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.playbackOpened(videoId, stored.localFileUri!!)
        WatchingActions.onProgressEntryUpdated(completedEntry())
        DownloadsRepository.cancelDownload(stored.id)
        assertNull(DownloadsRepository.uiState.value.items.firstOrNull { it.id == stored.id })

        FinishedDownloadDeletion.playbackEnded(videoId)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun turningTheSwitchOffBeforeLeavingKeepsTheQueuedFile() {
        val stored = seedCompletedDownload()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.playbackOpened(videoId, stored.localFileUri!!)
        WatchingActions.onProgressEntryUpdated(completedEntry())
        DownloadsSettingsRepository.setDeleteWhenFinished(false)
        FinishedDownloadDeletion.playbackEnded(videoId)

        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)
        assertTrue(videoFile().isFile)
    }

    private fun seedCompletedDownload(id: String = "finished-download"): DownloadItem {
        directory.mkdirs()
        videoFile().writeText("finished video")
        partFile().writeText("partial")
        DownloadSubtitleStorage(videoFile().toURI().toString()).write("english.srt", srt)
        val item = downloadItem(id = id).copy(
            videoId = videoId,
            parentMetaId = videoId,
            parentMetaType = "movie",
            contentType = "movie",
            status = DownloadStatus.Completed,
            localFileUri = videoFile().toURI().toString(),
            fileName = "movie.mkv",
            earlyPlayReady = false,
        )
        DownloadsRepository.replaceItems(listOf(item))
        return DownloadsRepository.uiState.value.items.single()
    }

    private fun completedEntry() = WatchProgressEntry(
        contentType = "movie",
        parentMetaId = videoId,
        parentMetaType = "movie",
        videoId = videoId,
        title = "Movie",
        lastPositionMs = 3_600_000L,
        durationMs = 3_600_000L,
        lastUpdatedEpochMs = 10L,
        isCompleted = true,
    )

    private suspend fun awaitRemoval(item: DownloadItem) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (removalPending(item).isEmpty()) return
            delay(20)
        }
        error(removalPending(item).joinToString())
    }

    private fun removalPending(item: DownloadItem): List<String> = buildList {
        val rows = DownloadsRepository.uiState.value.items.map { "${it.id}:${it.status}:${it.localFileUri}" }
        if (rows.any { it.startsWith("${item.id}:") }) add("row=$rows")
        if (videoFile().exists()) add("video=${videoFile().absolutePath}")
        if (partFile().exists()) add("part=${partFile().absolutePath}")
        if (subtitleDirectory().exists()) add("subs=${subtitleDirectory().absolutePath}")
    }

    private fun videoFile() = File(directory, "movie.mkv")
    private fun partFile() = File(directory, "movie.mkv.part")
    private fun subtitleDirectory() = File(videoFile().path + ".subtitles")
}

private const val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
