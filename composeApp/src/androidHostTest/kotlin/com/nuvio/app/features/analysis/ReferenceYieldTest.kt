package com.nuvio.app.features.analysis

import android.app.Application
import android.content.Context
import com.nuvio.app.core.uifixture.UiFixtureSession
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.downloads.downloadItem
import java.io.File
import kotlinx.serialization.json.Json
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
        SiblingClipReference.clearCacheForTests()
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
        SiblingClipReference.clearCacheForTests()
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

    @Test
    fun aMatchedOpeningIsKeptWhenTheCreditsWindowDoesNotMatch() = runTest {
        UiFixtureSession.testFixture = null
        val frames = IntArray(50) { openingLane(it, 14) }
        for (index in 10 until 40) frames[index] = openingLane(index - 10, 6)
        MediaAnalysisScheduler.fingerprintClipForTest = { _, start, end ->
            val count = ((end - start) / 1_000L).toInt().coerceAtLeast(1)
            val values = if (start >= 1_000_000L) {
                IntArray(count) { openingLane(it, 3) }
            } else {
                IntArray(count) { index ->
                    val second = (start / 1_000L).toInt() + index
                    if (second in 10 until 40) openingLane(second - 10, 6) else openingLane(index, 2)
                }
            }
            clipText(start, end, values)
        }
        storeTarget(encodeFingerprint(frames), encodeFingerprint(IntArray(40) { openingLane(it, 9) }))
        storeMarkedOpeningAndCredits()
        DownloadsRepository.replaceItems(listOf(episode(5), episode(6)))
        assertEquals(true, MediaAnalysisScheduler.runPending(fromBackgroundTask = false))
        val stored = MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show", 1, 5))
        assertEquals(10_000L, stored?.introStartMs)
        assertEquals(40_000L, stored?.introEndMs)
        assertEquals(null, stored?.creditsStartMs)
    }

    @Test
    fun twoDisagreeingSlicesDoNotWriteTheLongerRun() = runTest {
        UiFixtureSession.testFixture = null
        val frames = IntArray(120) { openingLane(it, 14) }
        for (index in 20 until 60) frames[index] = openingLane(index - 20, 6)
        for (index in 80 until 100) frames[index] = openingLane(index - 80, 7)
        MediaAnalysisScheduler.fingerprintClipForTest = { _, start, end ->
            val count = ((end - start) / 1_000L).toInt().coerceAtLeast(1)
            val values = IntArray(count) { index ->
                val second = (start / 1_000L).toInt() + index
                when {
                    second in 20 until 60 -> openingLane(second - 20, 6)
                    second in 80 until 100 -> openingLane(second - 80, 7)
                    else -> openingLane(index, 2)
                }
            }
            clipText(start, end, values)
        }
        storeTarget(encodeFingerprint(frames), null)
        storeMarkedLongIntro()
        DownloadsRepository.replaceItems(listOf(episode(5), episode(6)))
        MediaAnalysisScheduler.runPending(fromBackgroundTask = false)
        val stored = MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show", 1, 5))
        assertNull(stored?.introStartMs)
        assertNull(stored?.introEndMs)
    }

    @Test
    fun anIncompleteReferenceWindowDoesNotWriteAnIntro() = runTest {
        UiFixtureSession.testFixture = null
        MediaAnalysisScheduler.fingerprintClipForTest = { _, start, end ->
            clipText(start, end, IntArray(10) { openingLane(it, 6) }, stop = "truncated", complete = false)
        }
        storeTarget(encodeFingerprint(IntArray(50) { openingLane(it, 6) }), null)
        storeMarkedOpeningAndCredits()
        DownloadsRepository.replaceItems(listOf(episode(5), episode(6)))
        MediaAnalysisScheduler.runPending(fromBackgroundTask = false)
        val stored = MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show", 1, 5))
        assertEquals(null, stored?.introEndMs)
    }

    private val fingerprintJson = Json { encodeDefaults = true }

    private fun clipText(
        start: Long,
        end: Long,
        values: IntArray,
        stop: String = "window-end",
        complete: Boolean = true,
    ): String = fingerprintJson.encodeToString(
        RawClipFingerprint.serializer(),
        RawClipFingerprint(
            startMs = start,
            stepMs = 1_000,
            decodedFrames = values.size.coerceAtLeast(1),
            decodedSamples = values.size.coerceAtLeast(1),
            fingerprint = encodeFingerprint(values),
            windowComplete = complete,
            stopReason = stop,
        ),
    )

    private fun storeTarget(head: String, tail: String?) {
        MediaAnalysisStore.save(
            MediaAnalysisResult(
                key = MediaAnalysisStore.keyFor("show", 1, 5),
                analyzedAtMs = 1L,
                durationMs = 2_400_000L,
                fileName = "e5.mkv",
                fingerprintStepMs = 1_000,
                headFingerprint = head,
                tailFingerprint = tail,
                tailStartMs = 1_980_000L,
                analysisAttempts = ANALYSIS_MAX_ATTEMPTS,
                derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION,
            ),
        )
    }

    private fun storeMarkedLongIntro() {
        MediaAnalysisStore.save(
            MediaAnalysisResult(
                key = MediaAnalysisStore.keyFor("show", 1, 6),
                analyzedAtMs = 1L,
                durationMs = 2_400_000L,
                fileName = "e6.mkv",
                analysisAttempts = ANALYSIS_MAX_ATTEMPTS,
                derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION,
                userSegments = mapOf(
                    "intro" to UserSegment(20_000L, 100_000L, markedAtMs = 1L, durationMs = 2_400_000L),
                ),
            ),
        )
    }

    private fun storeMarkedOpeningAndCredits() {
        MediaAnalysisStore.save(
            MediaAnalysisResult(
                key = MediaAnalysisStore.keyFor("show", 1, 6),
                analyzedAtMs = 1L,
                durationMs = 2_400_000L,
                fileName = "e6.mkv",
                analysisAttempts = ANALYSIS_MAX_ATTEMPTS,
                derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION,
                userSegments = mapOf(
                    "intro" to UserSegment(10_000L, 40_000L, markedAtMs = 1L, durationMs = 2_400_000L),
                    "outro" to UserSegment(2_000_000L, 2_020_000L, markedAtMs = 1L, durationMs = 2_400_000L),
                ),
            ),
        )
    }

    /** Frames far enough apart that a 6-bit match cannot slide onto a neighbour. */
    private fun openingLane(index: Int, salt: Int): Int {
        var mixed = 0x12345678 xor (index * 0x45D9F3B) xor (salt * 0x6C8E9CF5.toInt())
        mixed = mixed xor (mixed ushr 16)
        mixed *= 0x7FEB352D
        mixed = mixed xor (mixed ushr 15)
        mixed *= 0x846CA68B.toInt()
        mixed = mixed xor (mixed ushr 16)
        return mixed or 1
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
