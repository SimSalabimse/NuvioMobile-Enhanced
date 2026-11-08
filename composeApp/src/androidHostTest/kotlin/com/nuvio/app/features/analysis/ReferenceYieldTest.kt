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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReferenceYieldTest {
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
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, true)
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.RecapDetection, false)
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.ScrubPreviews, false)
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.OnlyWhileCharging, false)
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
    fun aBlockedReferenceLetsTheNextLocalFileStart() = runTest {
        val fingerprintStarted = CompletableDeferred<Unit>()
        val localStarted = CompletableDeferred<String>()
        var fingerprints = 0
        MediaAnalysisScheduler.fingerprintClipForTest = { _, _, _ ->
            fingerprints += 1
            if (fingerprints == 1) {
                fingerprintStarted.complete(Unit)
                while (!MediaAnalysisScheduler.referenceCancelRequested()) delay(10)
            }
            null
        }
        MediaAnalysisScheduler.analyzeFileForTest = { uri ->
            localStarted.complete(uri)
            """{"durationMs":1000,"head":"AAAA"}"""
        }
        storeMarkedSibling()
        storeEpisodeNeedingReference()
        DownloadsRepository.replaceItems(listOf(episode(5), episode(6)))
        val running = async { MediaAnalysisScheduler.runPending(fromBackgroundTask = false) }
        try {
            fingerprintStarted.await()
            DownloadsRepository.replaceItems(listOf(episode(5), episode(6), episode(7)))
            MediaAnalysisScheduler.kick(startDelayMs = 0L)
            val started = withTimeout(5_000) { localStarted.await() }
            assertTrue(started.contains("e7.mkv"))
            assertEquals(1, fingerprints)
        } finally {
            if (!running.isCompleted) running.cancel()
            running.join()
        }
        val stored = MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show", 1, 5))
        assertNull(stored?.introEndMs)
    }

    private fun storeEpisodeNeedingReference() {
        MediaAnalysisStore.save(
            MediaAnalysisResult(
                key = MediaAnalysisStore.keyFor("show", 1, 5),
                analyzedAtMs = 1L,
                durationMs = 2_400_000L,
                fileName = "e5.mkv",
                headFingerprint = "AQIDBA==",
                derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION,
            ),
        )
    }

    private fun storeMarkedSibling() {
        MediaAnalysisStore.save(
            MediaAnalysisResult(
                key = MediaAnalysisStore.keyFor("show", 1, 6),
                analyzedAtMs = 1L,
                durationMs = 2_400_000L,
                fileName = "e6.mkv",
                derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION,
                userSegments = mapOf(
                    "intro" to UserSegment(20_000L, 110_000L, markedAtMs = 1L, durationMs = 2_400_000L),
                ),
            ),
        )
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
        sourceUrl = "local-file",
    )

    private fun videoFile(name: String): String {
        val directory = File(RuntimeEnvironment.getApplication().filesDir, "downloads").apply { mkdirs() }
        return File(directory, name).apply { writeText("episode") }.toURI().toString()
    }
}
