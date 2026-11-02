package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonDownloadPolicyTest {
    @Test
    fun `finished running queued and paused episodes are left out of a season download`() {
        assertEquals(SeasonExistingDownload.Downloaded, seasonExistingDownload(DownloadStatus.Completed))
        assertEquals(SeasonExistingDownload.InProgress, seasonExistingDownload(DownloadStatus.Downloading))
        assertEquals(SeasonExistingDownload.InProgress, seasonExistingDownload(DownloadStatus.Queued))
        assertEquals(SeasonExistingDownload.InProgress, seasonExistingDownload(DownloadStatus.Paused))
        assertEquals(SeasonExistingDownload.InProgress, seasonExistingDownload(DownloadStatus.Preparing))
    }

    @Test
    fun `a failed or missing episode can be queued`() {
        assertNull(seasonExistingDownload(DownloadStatus.Failed))
        assertNull(seasonExistingDownload(null))
    }

    @Test
    fun `auto pick follows the stream screen timeout`() {
        assertTrue(seasonAutoPickMayDecide(timeoutSeconds = 0, timeoutElapsed = false, allSourcesAnswered = false))
        assertFalse(seasonAutoPickMayDecide(timeoutSeconds = 3, timeoutElapsed = false, allSourcesAnswered = false))
        assertTrue(seasonAutoPickMayDecide(timeoutSeconds = 3, timeoutElapsed = true, allSourcesAnswered = false))
        assertFalse(
            seasonAutoPickMayDecide(timeoutSeconds = Int.MAX_VALUE, timeoutElapsed = true, allSourcesAnswered = false),
        )
        assertTrue(
            seasonAutoPickMayDecide(timeoutSeconds = Int.MAX_VALUE, timeoutElapsed = false, allSourcesAnswered = true),
        )
    }

    @Test
    fun `a partial download keeps a resume point inside the bytes on disk`() {
        assertTrue(partialPlaybackKeepsResume(resumeFraction = 0.05f, downloadedBytes = 300L, totalBytes = 1_000L))
    }

    @Test
    fun `a partial download starts from zero when the resume point is past the bytes on disk`() {
        assertFalse(partialPlaybackKeepsResume(resumeFraction = 0.40f, downloadedBytes = 100L, totalBytes = 1_000L))
        assertFalse(partialPlaybackKeepsResume(resumeFraction = 0.09f, downloadedBytes = 100L, totalBytes = 1_000L))
        assertFalse(partialPlaybackKeepsResume(resumeFraction = 0.05f, downloadedBytes = 100L, totalBytes = null))
        assertFalse(partialPlaybackKeepsResume(resumeFraction = null, downloadedBytes = 900L, totalBytes = 1_000L))
    }
}
