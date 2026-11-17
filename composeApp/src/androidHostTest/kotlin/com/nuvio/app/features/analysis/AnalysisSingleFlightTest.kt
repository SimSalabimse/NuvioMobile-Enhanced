package com.nuvio.app.features.analysis

import android.app.Application
import android.content.Context
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.downloads.downloadItem
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnalysisSingleFlightTest {
    @Before
    fun initialize() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_downloads_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsSettingsStorage.initialize(context)
        DownloadsStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsSettingsRepository.clearLocalState()
        DownloadsRepository.clearLocalState()
        MediaAnalysisScheduler.resetForTest()
    }

    @After
    fun clearState() {
        MediaAnalysisScheduler.resetForTest()
        DownloadsRepository.clearLocalState()
        DownloadsSettingsRepository.clearLocalState()
        Dispatchers.resetMain()
    }

    @Test
    fun secondRunPendingDoesNotAnalyseUntilTheFirstReturns() = runTest {
        val directory = File(RuntimeEnvironment.getApplication().filesDir, "downloads").apply { mkdirs() }
        val video = File(directory, "episode.mkv").apply { writeText("episode") }
        val item = downloadItem(id = "episode-1").copy(
            contentType = "series",
            parentMetaId = "show",
            parentMetaType = "series",
            videoId = "show:1:1",
            title = "Show",
            seasonNumber = 1,
            episodeNumber = 1,
            status = DownloadStatus.Completed,
            localFileUri = video.toURI().toString(),
            fileName = "episode.mkv",
        )
        DownloadsRepository.replaceItems(listOf(item))
        MediaAnalysisScheduler.supportedOverride = true
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        MediaAnalysisScheduler.analyzeFileForTest = {
            calls += calls.size + 1
            if (calls.size == 1) {
                inside.complete(Unit)
                release.await()
            }
            null
        }
        val first = async { MediaAnalysisScheduler.runPending(fromBackgroundTask = false) }
        try {
            inside.await()
            val second = async { MediaAnalysisScheduler.runPending(fromBackgroundTask = false) }
            second.await()
            assertEquals(listOf(1), calls)
        } finally {
            release.complete(Unit)
        }
        first.await()
    }
}
