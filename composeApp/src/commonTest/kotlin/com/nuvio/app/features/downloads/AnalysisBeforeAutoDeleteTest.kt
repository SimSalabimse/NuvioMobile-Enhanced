package com.nuvio.app.features.downloads

import com.nuvio.app.features.analysis.MediaAnalysisResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisBeforeAutoDeleteTest {
    @Test
    fun noFingerprintHoldsACompletedEpisode() {
        assertTrue(needsAnalysisBeforeAutoDelete(episode(), stored = null, intro = true, supported = true))
        assertTrue(needsAnalysisBeforeAutoDelete(episode(), stored = analysis(head = "", tail = null), intro = true, supported = true))
        assertTrue(
            shouldHoldAutomaticDeletion(
                item = episode(),
                storedAnalysis = null,
                introDetectionEnabled = true,
                analyserSupported = true,
                automatic = true,
                heldForMs = 0L,
            ),
        )
    }

    @Test
    fun savedFingerprintDeletes() {
        assertFalse(needsAnalysisBeforeAutoDelete(episode(), analysis(head = "abc"), intro = true, supported = true))
        assertFalse(needsAnalysisBeforeAutoDelete(episode(), analysis(head = null, tail = "xyz"), intro = true, supported = true))
    }

    @Test
    fun introDetectionOffDeletes() {
        assertFalse(needsAnalysisBeforeAutoDelete(episode(), stored = null, intro = false, supported = true))
    }

    @Test
    fun unsupportedAnalyserDeletes() {
        assertFalse(needsAnalysisBeforeAutoDelete(episode(), stored = null, intro = true, supported = false))
    }

    @Test
    fun manualDeleteIsImmediate() {
        assertFalse(
            shouldHoldAutomaticDeletion(
                item = episode(),
                storedAnalysis = null,
                introDetectionEnabled = true,
                analyserSupported = true,
                automatic = false,
                heldForMs = 0L,
            ),
        )
    }

    @Test
    fun capExceededDeletes() {
        assertFalse(
            shouldHoldAutomaticDeletion(
                item = episode(),
                storedAnalysis = null,
                introDetectionEnabled = true,
                analyserSupported = true,
                automatic = true,
                heldForMs = ANALYSIS_HOLD_CAP_MS,
            ),
        )
        assertTrue(
            shouldHoldAutomaticDeletion(
                item = episode(),
                storedAnalysis = null,
                introDetectionEnabled = true,
                analyserSupported = true,
                automatic = true,
                heldForMs = ANALYSIS_HOLD_CAP_MS - 1L,
            ),
        )
    }

    @Test
    fun finalAnalysisFailureDeletes() {
        assertFalse(
            shouldHoldAutomaticDeletion(
                item = episode(),
                storedAnalysis = null,
                introDetectionEnabled = true,
                analyserSupported = true,
                automatic = true,
                heldForMs = 0L,
                attemptsExhausted = true,
            ),
        )
    }

    @Test
    fun moviesAndUnfinishedDownloadsDelete() {
        assertFalse(needsAnalysisBeforeAutoDelete(episode().copy(seasonNumber = null, episodeNumber = null), null, true, true))
        assertFalse(needsAnalysisBeforeAutoDelete(episode().copy(status = DownloadStatus.Downloading), null, true, true))
    }

    private fun needsAnalysisBeforeAutoDelete(
        item: DownloadItem,
        stored: MediaAnalysisResult?,
        intro: Boolean,
        supported: Boolean,
    ) = com.nuvio.app.features.downloads.needsAnalysisBeforeAutoDelete(item, stored, intro, supported)

    private fun episode() = DownloadItem(
        id = "ep",
        contentType = "series",
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = "show:1:1",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = 1,
        streamTitle = "",
        providerName = "",
        sourceUrl = "https://example.invalid/ep.mkv",
        fileName = "ep.mkv",
        status = DownloadStatus.Completed,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    private fun analysis(head: String? = null, tail: String? = null) = MediaAnalysisResult(
        key = "show__s1e1",
        analyzedAtMs = 1L,
        durationMs = 1L,
        headFingerprint = head,
        tailFingerprint = tail,
    )
}
