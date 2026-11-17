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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnalysisLifecycleTest {
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
        MediaAnalysisFiles.retainForTests = true
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisStore.clearCacheForTests()
        MediaAnalysisScheduler.resetForTest()
        MediaAnalysisScheduler.supportedOverride = true
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, false)
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.RecapDetection, false)
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.ScrubPreviews, false)
    }

    @After
    fun clearState() {
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, true)
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.RecapDetection, true)
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.ScrubPreviews, true)
        MediaAnalysisScheduler.resetForTest()
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisFiles.retainForTests = false
        DownloadsRepository.clearLocalState()
        DownloadsSettingsRepository.clearLocalState()
        Dispatchers.resetMain()
    }

    @Test
    fun aFinishedEpisodeIsFollowedByTheNextOne() = runTest {
        val seen = mutableListOf<String>()
        MediaAnalysisScheduler.analyzeFileForTest = { uri ->
            seen += uri
            """{"durationMs":1000}"""
        }
        DownloadsRepository.replaceItems(listOf(episode(5), episode(6)))

        val finished = MediaAnalysisScheduler.runPending(fromBackgroundTask = false)

        assertTrue(finished)
        assertEquals(2, seen.size)
        assertTrue(seen[0].contains("e5.mkv"))
        assertTrue(seen[1].contains("e6.mkv"))
    }

    @Test
    fun anExceptionOnOneEpisodeStillStartsTheNext() = runTest {
        val seen = mutableListOf<String>()
        MediaAnalysisScheduler.analyzeFileForTest = { uri ->
            seen += uri
            if (uri.contains("e5.mkv")) error("stopped")
            """{"durationMs":1000}"""
        }
        DownloadsRepository.replaceItems(listOf(episode(6), episode(5)))

        MediaAnalysisScheduler.runPending(fromBackgroundTask = false)

        assertEquals(listOf(true, true), seen.map { it.contains("e5.mkv") || it.contains("e6.mkv") })
        assertTrue(seen[0].contains("e5.mkv"))
        assertTrue(seen[1].contains("e6.mkv"))
    }

    @Test
    fun cancellationStopsThePassBeforeTheNextEpisode() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        MediaAnalysisScheduler.analyzeFileForTest = {
            calls += 1
            if (calls == 1) {
                started.complete(Unit)
                gate.await()
            }
            null
        }
        DownloadsRepository.replaceItems(listOf(episode(5), episode(6)))
        val running = async { MediaAnalysisScheduler.runPending(fromBackgroundTask = false) }
        try {
            started.await()
            running.cancel()
        } finally {
            if (!gate.isCompleted) gate.cancel()
        }
        running.join()
        assertEquals(1, calls)
    }

    private fun episode(number: Int) = downloadItem(id = "e$number").copy(
        contentType = "series",
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = "show:1:$number",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = number,
        status = DownloadStatus.Completed,
        localFileUri = videoFile("e$number.mkv"),
        fileName = "e$number.mkv",
    )

    private fun videoFile(name: String): String {
        val directory = File(RuntimeEnvironment.getApplication().filesDir, "downloads").apply { mkdirs() }
        return File(directory, name).apply { writeText("episode") }.toURI().toString()
    }
}
