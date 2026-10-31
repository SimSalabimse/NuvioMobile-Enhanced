package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import com.nuvio.app.features.streams.StreamItem
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A download is visible as Preparing before its link exists, then Downloading or Failed.
 * Cancelling during Preparing removes it, and a later resolve cannot bring it back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadPreparingTest {
    private class FakeTransfers : DownloadTransfers {
        val running = linkedSetOf<String>()

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            val id = request.item.id
            running += id
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

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_downloads_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsSettingsStorage.initialize(context)
        DownloadsStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsSettingsRepository.clearLocalState()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.transfers = fake
        DownloadsRepository.setPlaybackDownloadCap(MAX_ACTIVE_DOWNLOADS)
    }

    @After
    fun clear() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.transfers = PlatformDownloadTransfers
    }

    @Test
    fun preparingBecomesDownloadingWhenTheLinkIsReady() {
        val id = DownloadsRepository.beginPreparation(movieTarget(), directStream())
        val preparing = DownloadsRepository.uiState.value.items.single()
        assertEquals(id, preparing.id)
        assertEquals(DownloadStatus.Preparing, preparing.status)
        assertEquals(PREPARING_SOURCE_URL, preparing.sourceUrl)
        assertTrue(id !in fake.running, "Preparing must not start a transfer")

        val result = DownloadsRepository.finishPreparation(id, directStream())
        val started = DownloadsRepository.uiState.value.items.single()
        assertEquals(DownloadEnqueueResult.Started, result)
        assertEquals(DownloadStatus.Downloading, started.status)
        assertEquals("https://cdn.example/movie.mkv", started.sourceUrl)
        assertEquals(id, started.id)
        assertTrue(id in fake.running)
    }

    @Test
    fun preparingBecomesFailedWithTheReason() {
        val id = DownloadsRepository.beginPreparation(movieTarget(), directStream())
        DownloadsRepository.failPreparation(id, "Not cached on TorBox")
        val failed = DownloadsRepository.uiState.value.items.single()
        assertEquals(id, failed.id)
        assertEquals(DownloadStatus.Failed, failed.status)
        assertEquals("Not cached on TorBox", failed.errorMessage)
        assertTrue(fake.running.isEmpty())
    }

    @Test
    fun cancelWhilePreparingRemovesTheRowAndALateResolveDoesNothing() {
        val id = DownloadsRepository.beginPreparation(movieTarget(), directStream())
        DownloadsRepository.cancelDownload(id)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())

        assertNull(DownloadsRepository.finishPreparation(id, directStream()))
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertTrue(fake.running.isEmpty())
    }

    @Test
    fun preparingLeftOnDiskFailsInsteadOfStayingPreparing() {
        val id = DownloadsRepository.beginPreparation(movieTarget(), directStream())
        assertEquals(DownloadStatus.Preparing, DownloadsRepository.uiState.value.items.single().status)

        DownloadsRepository.clearLocalState()
        DownloadsRepository.transfers = fake
        DownloadsRepository.ensureLoaded()

        val restored = DownloadsRepository.uiState.value.items.single()
        assertEquals(id, restored.id)
        assertEquals(DownloadStatus.Failed, restored.status)
        assertEquals(PREPARATION_INTERRUPTED_MESSAGE, restored.errorMessage)
        assertTrue(id !in fake.running)
    }

    private fun movieTarget() = DownloadTarget(
        contentType = "movie",
        videoId = "tt1",
        parentMetaId = "tt1",
        parentMetaType = "movie",
        title = "Example",
        logo = null,
        poster = null,
        background = null,
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        episodeThumbnail = null,
    )

    private fun directStream() = StreamItem(
        name = "Example 1080p",
        url = "https://cdn.example/movie.mkv",
        addonName = "AIOStreams",
        addonId = "aio",
    )
}
