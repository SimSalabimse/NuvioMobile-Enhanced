package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import com.nuvio.app.features.analysis.ANALYSIS_MAX_ATTEMPTS
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.MediaAnalysisSettings
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HoldDownloadForIntroAnalysisTest {
    private lateinit var directory: File
    private var nowMs = 1_000_000L

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
        FinishedDownloadDeletion.reset()
        FinishedDownloadDeletion.closeGraceMs = 0L
        FinishedDownloadDeletion.deletionNowMs = { nowMs }
        directory = File(context.filesDir, "downloads").apply { mkdirs() }
    }

    @After
    fun clearState() {
        FinishedDownloadDeletion.reset()
        DownloadsRepository.clearLocalState()
        DownloadsSettingsRepository.clearLocalState()
        SmartDownloadSettings.setEnabled(false)
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, true)
    }

    @Test
    fun androidLeavesTheEpisodeToDeleteImmediately() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        finishPlayback(stored)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertFalse(videoFile().exists())
        assertFalse(smartDownloadsDefersToPendingDeletion())
    }

    @Test
    fun missingFingerprintHoldsAnAutomaticDelete() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        finishPlayback(stored)
        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)
        assertTrue(videoFile().isFile)
        assertTrue(FinishedDownloadDeletion.hasPendingDeletion())
        assertTrue(smartDownloadsDefersToPendingDeletion())
    }

    @Test
    fun smartDownloadsAloneNeitherDeleteNorHold() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(false)
        SmartDownloadSettings.setEnabled(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        finishPlayback(stored)
        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)
        assertTrue(videoFile().isFile)
        assertFalse(FinishedDownloadDeletion.hasPendingDeletion())
        assertFalse(smartDownloadsDefersToPendingDeletion())
    }

    @Test
    fun savedFingerprintDeletes() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        FinishedDownloadDeletion.deletionStoredAnalysis = { fingerprint() }
        finishPlayback(stored)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertFalse(videoFile().exists())
    }

    @Test
    fun introDetectionOffDeletes() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, false)
        finishPlayback(stored)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun aMovieDeletesEvenWhenTheAnalyserIsSupported() {
        val stored = seedEpisode().copy(
            contentType = "movie",
            parentMetaType = "movie",
            seasonNumber = null,
            episodeNumber = null,
        )
        DownloadsRepository.replaceItems(listOf(stored))
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        finishPlayback(DownloadsRepository.uiState.value.items.single())
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun manualDeleteStaysImmediateWhileAHoldWouldApply() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        finishPlayback(stored)
        assertTrue(FinishedDownloadDeletion.hasPendingDeletion())
        DownloadsRepository.cancelDownload(stored.id)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertFalse(videoFile().exists())
        assertFalse(FinishedDownloadDeletion.hasPendingDeletion())
        assertFalse(smartDownloadsDefersToPendingDeletion())
        FinishedDownloadDeletion.analysisSettled(stored.id)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun analysisSettledRunsTheHeldDeletionOnce() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        finishPlayback(stored)
        FinishedDownloadDeletion.analysisSettled(stored.id)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertFalse(videoFile().exists())
        assertFalse(smartDownloadsDefersToPendingDeletion())
        FinishedDownloadDeletion.analysisSettled(stored.id)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun twentyNineMinutesUnpluggedStaysHeld() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        FinishedDownloadDeletion.deletionOnlyWhileCharging = { true }
        FinishedDownloadDeletion.deletionDeviceCharging = { false }
        finishPlayback(stored)
        nowMs += 29L * 60L * 1000L
        FinishedDownloadDeletion.reevaluateHeldDeletion()
        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)
        assertTrue(FinishedDownloadDeletion.hasPendingDeletion())
    }

    @Test
    fun chargingLetsAnalysisFinishThenDeletes() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        FinishedDownloadDeletion.deletionOnlyWhileCharging = { true }
        FinishedDownloadDeletion.deletionDeviceCharging = { false }
        finishPlayback(stored)
        assertTrue(FinishedDownloadDeletion.hasPendingDeletion())

        FinishedDownloadDeletion.deletionDeviceCharging = { true }
        FinishedDownloadDeletion.reevaluateHeldDeletion()
        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)

        FinishedDownloadDeletion.deletionStoredAnalysis = { fingerprint() }
        FinishedDownloadDeletion.analysisSettled(stored.id)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertFalse(videoFile().exists())
    }

    @Test
    fun thirtyMinutesOfEligibleWaitDeletes() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        FinishedDownloadDeletion.deletionOnlyWhileCharging = { true }
        FinishedDownloadDeletion.deletionDeviceCharging = { true }
        finishPlayback(stored)
        nowMs += ANALYSIS_HOLD_CAP_MS
        FinishedDownloadDeletion.reevaluateHeldDeletion()
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertFalse(videoFile().exists())
        assertFalse(FinishedDownloadDeletion.hasPendingDeletion())
    }

    @Test
    fun exhaustedAttemptsReleaseTheHold() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        FinishedDownloadDeletion.deletionStoredAnalysis = {
            fingerprint().copy(headFingerprint = null, tailFingerprint = null, analysisAttempts = ANALYSIS_MAX_ATTEMPTS)
        }
        finishPlayback(stored)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertFalse(FinishedDownloadDeletion.hasPendingDeletion())
    }

    @Test
    fun capReleasesTheHoldSoSmartDownloadsIsNotStuck() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        finishPlayback(stored)
        assertTrue(smartDownloadsDefersToPendingDeletion())
        nowMs += ANALYSIS_HOLD_CAP_MS
        FinishedDownloadDeletion.reevaluateHeldDeletion()
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertFalse(videoFile().exists())
        assertFalse(FinishedDownloadDeletion.hasPendingDeletion())
        assertFalse(smartDownloadsDefersToPendingDeletion())
    }

    @Test
    fun restartKeepsTheHoldThenDeletesOnceTheFingerprintExists() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        finishPlayback(stored)
        FinishedDownloadDeletion.discardMemoryForRestart()
        assertFalse(FinishedDownloadDeletion.hasPendingDeletion())
        FinishedDownloadDeletion.resumeAfterLaunch()
        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)
        assertTrue(FinishedDownloadDeletion.hasPendingDeletion())
        FinishedDownloadDeletion.resumeAfterLaunch()
        assertEquals(stored.id, DownloadsRepository.uiState.value.items.single().id)

        FinishedDownloadDeletion.deletionStoredAnalysis = { fingerprint() }
        FinishedDownloadDeletion.discardMemoryForRestart()
        FinishedDownloadDeletion.resumeAfterLaunch()
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertFalse(videoFile().exists())
        FinishedDownloadDeletion.resumeAfterLaunch()
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun restartDeletesWhenTheCapAlreadyPassed() {
        val stored = seedEpisode()
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        FinishedDownloadDeletion.deletionAnalyserSupported = true
        finishPlayback(stored)
        nowMs += ANALYSIS_HOLD_CAP_MS
        FinishedDownloadDeletion.discardMemoryForRestart()
        FinishedDownloadDeletion.resumeAfterLaunch()
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertFalse(smartDownloadsDefersToPendingDeletion())
    }

    private fun finishPlayback(item: DownloadItem) {
        FinishedDownloadDeletion.playbackOpened(item.videoId, item.localFileUri!!)
        val entry = WatchProgressEntry(
            contentType = item.contentType,
            parentMetaId = item.parentMetaId,
            parentMetaType = item.parentMetaType,
            videoId = item.videoId,
            title = item.title,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
            lastPositionMs = 3_600_000L,
            durationMs = 3_600_000L,
            lastUpdatedEpochMs = 10L,
        )
        FinishedDownloadDeletion.onLocalProgress(entry, positionMs = 3_600_000L, durationMs = 3_600_000L)
        FinishedDownloadDeletion.playbackEnded(item.videoId)
    }

    private fun seedEpisode(): DownloadItem {
        directory.mkdirs()
        videoFile().writeText("episode")
        val item = downloadItem(id = "episode-1").copy(
            contentType = "series",
            parentMetaId = "show",
            parentMetaType = "series",
            videoId = "show:1:1",
            title = "Show",
            seasonNumber = 1,
            episodeNumber = 1,
            status = DownloadStatus.Completed,
            localFileUri = videoFile().toURI().toString(),
            fileName = "episode.mkv",
        )
        DownloadsRepository.replaceItems(listOf(item))
        return DownloadsRepository.uiState.value.items.single()
    }

    private fun fingerprint() = MediaAnalysisResult(
        key = "show__s1e1",
        analyzedAtMs = 1L,
        durationMs = 1L,
        headFingerprint = "abc",
    )

    private fun videoFile() = File(directory, "episode.mkv")
}
