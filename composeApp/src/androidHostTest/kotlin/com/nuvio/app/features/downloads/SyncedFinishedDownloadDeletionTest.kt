package com.nuvio.app.features.downloads

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decision only. No player, no download store, no device.
 */
class SyncedFinishedDownloadDeletionTest {
    @Test
    fun completedNewerSyncedCompletionDeletes() {
        assertTrue(decide())
    }

    @Test
    fun switchOffKeepsTheFile() {
        assertFalse(decide(enabled = false))
    }

    @Test
    fun runningDownloadKeepsTheFile() {
        assertFalse(decide(status = DownloadStatus.Downloading))
        assertFalse(decide(status = DownloadStatus.Queued))
    }

    @Test
    fun localInProgressNewerThanTheSyncKeepsTheFile() {
        assertFalse(
            decide(
                local = LocalWatchSnapshot(
                    lastPositionMs = 60_000L,
                    isCompleted = false,
                    lastUpdatedEpochMs = 3_000L,
                ),
            ),
        )
    }

    @Test
    fun completionOlderThanTheDownloadKeepsTheFile() {
        assertFalse(decide(finishedAt = 5_000L, completedAt = 2_000L))
    }

    @Test
    fun missingTimestampOrSourceKeepsTheFile() {
        assertFalse(decide(completedAt = null))
        assertFalse(decide(source = null))
        assertFalse(decide(source = ""))
        assertFalse(decide(source = "local"))
        assertFalse(decide(origin = SyncedWatchOrigin.Unclear))
    }

    @Test
    fun videoOnScreenKeepsTheFile() {
        assertFalse(decide(onScreen = true))
        assertFalse(decide(inPlayerSession = true))
        assertFalse(decide(playedWithinGrace = true))
    }

    @Test
    fun submitModeAutomationHoldAndPartialProgressKeepTheFile() {
        assertFalse(decide(persistsProgress = false))
        assertFalse(decide(automationTestMode = true))
        assertFalse(decide(analysisTemp = true))
        assertFalse(decide(introAnalysisHoldActive = true))
        assertFalse(decide(fullyCompleted = false))
        assertFalse(
            decide(
                fullyCompleted = true,
                requiresPlayheadAtEnd = true,
                positionMs = 90L,
                durationMs = 100L,
            ),
        )
    }

    @Test
    fun olderLocalProgressDoesNotBlockANewerSyncedCompletion() {
        assertTrue(
            decide(
                local = LocalWatchSnapshot(
                    lastPositionMs = 60_000L,
                    isCompleted = false,
                    lastUpdatedEpochMs = 500L,
                ),
            ),
        )
    }

    @Test
    fun episodeMatchesOnShowSeasonAndEpisodeWhenVideoIdsDiffer() {
        assertTrue(decide(videoId = "other-device-id"))
        assertFalse(decide(videoId = "other-device-id", seasonNumber = 2))
    }

    @Test
    fun historyImportDeletesOnlyWhenTheTimestampIsNewerThanTheDownload() {
        assertTrue(decide(source = "trakt_history", origin = SyncedWatchOrigin.HistoryImport))
        assertFalse(
            decide(
                source = "mdblist_history",
                origin = SyncedWatchOrigin.HistoryImport,
                finishedAt = 5_000L,
                completedAt = 2_000L,
            ),
        )
    }

    private fun decide(
        enabled: Boolean = true,
        persistsProgress: Boolean = true,
        automationTestMode: Boolean = false,
        status: DownloadStatus = DownloadStatus.Completed,
        finishedAt: Long? = 1_000L,
        analysisTemp: Boolean = false,
        introAnalysisHoldActive: Boolean = false,
        completedAt: Long? = 2_000L,
        source: String? = SyncedWatchSourceAccount,
        origin: SyncedWatchOrigin = SyncedWatchOrigin.AccountSyncPull,
        fullyCompleted: Boolean = true,
        onScreen: Boolean = false,
        inPlayerSession: Boolean = false,
        playedWithinGrace: Boolean = false,
        local: LocalWatchSnapshot? = null,
        videoId: String = "video-1",
        parentMetaId: String = "show-1",
        seasonNumber: Int? = 1,
        episodeNumber: Int? = 4,
        requiresPlayheadAtEnd: Boolean = false,
        positionMs: Long? = null,
        durationMs: Long? = null,
    ): Boolean = shouldDeleteFinishedDownloadForSyncedCompletion(
        download = FinishedDownloadDeletionCandidate(
            status = status,
            videoId = "video-1",
            parentMetaId = "show-1",
            seasonNumber = 1,
            episodeNumber = 4,
            finishedAtEpochMs = finishedAt,
            analysisTemp = analysisTemp,
            introAnalysisHoldActive = introAnalysisHoldActive,
        ),
        completion = SyncedWatchCompletion(
            fullyCompleted = fullyCompleted,
            videoId = videoId,
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            completedAtEpochMs = completedAt,
            source = source,
            origin = origin,
            requiresPlayheadAtEnd = requiresPlayheadAtEnd,
            positionMs = positionMs,
            durationMs = durationMs,
        ),
        context = SyncedDeletionContext(
            deleteWhenFinishedEnabled = enabled,
            persistsProgress = persistsProgress,
            automationTestMode = automationTestMode,
            videoOnScreen = onScreen,
            inPlayerSession = inPlayerSession,
            playedWithinGrace = playedWithinGrace,
            localWatch = local,
        ),
    )
}
