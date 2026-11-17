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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Synthetic fingerprints only. A recaptured print must keep a known intro, credits, and recap.
 * A cancelled recap search must not leave "none found".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Build210DetectionTest {
    private val json = Json { encodeDefaults = true }

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
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.RecapDetection, true)
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
    fun aCancelledRecapSearchDropsAStaleNone() = runTest {
        val key = MediaAnalysisStore.keyFor("show", 1, 5)
        MediaAnalysisStore.save(stored(key, recapOutcome = "rejected-no-match"))
        var captures = 0
        MediaAnalysisScheduler.analyzeFileForTest = {
            captures += 1
            check(captures == 1) { "recapture loop" }
            analysisJson(IntArray(500) { lane(it, 4) })
        }
        MediaAnalysisScheduler.recapSearchForTest = { throw CancellationException("stopped") }
        DownloadsRepository.replaceItems(listOf(episode(5)))
        runCatching { MediaAnalysisScheduler.runPending(fromBackgroundTask = false) }
        assertNull(MediaAnalysisStore.load(key)?.recapOutcome)
    }

    private fun stored(key: String, recapOutcome: String) = MediaAnalysisResult(
        key = key,
        analyzedAtMs = 1L,
        durationMs = 50_000L,
        fileName = "e5.mkv",
        fingerprintStepMs = 100,
        headFingerprint = encodeFingerprint(IntArray(8) { lane(it, 3) }),
        analysisAttempts = 1,
        derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION,
        recapOutcome = recapOutcome,
    )

    private fun analysisJson(head: IntArray): String = json.encodeToString(
        RawMediaAnalysis.serializer(),
        RawMediaAnalysis(
            durationMs = 50_000L,
            fingerprintStepMs = 100,
            head = encodeFingerprint(head),
        ),
    )

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

    private fun lane(index: Int, salt: Int): Int {
        var mixed = 0x12345678 xor (index * 0x45D9F3B) xor (salt * 0x6C8E9CF5.toInt())
        mixed = mixed xor (mixed ushr 16)
        mixed *= 0x7FEB352D
        mixed = mixed xor (mixed ushr 15)
        mixed *= 0x846CA68B.toInt()
        mixed = mixed xor (mixed ushr 16)
        return mixed or 1
    }
}
