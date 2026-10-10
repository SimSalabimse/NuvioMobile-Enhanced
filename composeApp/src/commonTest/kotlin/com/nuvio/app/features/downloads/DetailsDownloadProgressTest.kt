package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailsDownloadProgressTest {
    @Test
    fun movieAndEpisodePercentsMatchTheDownloadsBar() {
        val movie = item(
            id = "movie",
            parentMetaId = "tt1",
            status = DownloadStatus.Downloading,
            downloadedBytes = 42L,
            totalBytes = 100L,
        )
        val episode = item(
            id = "episode",
            parentMetaId = "tt1",
            seasonNumber = 2,
            episodeNumber = 5,
            status = DownloadStatus.Downloading,
            downloadedBytes = 1L,
            totalBytes = 3L,
        )
        val items = listOf(movie, episode)

        val movieProgress = detailsDownloadProgress(items, movie.logicalContentKey)
        val episodeProgress = detailsDownloadProgress(items, episode.logicalContentKey)

        assertEquals(DetailsDownloadProgressKind.Determinate, movieProgress.kind)
        assertEquals(movie.downloadsScreenProgressPercent(), movieProgress.percent)
        assertEquals((movie.progressFraction * 100f).toInt(), movieProgress.percent)
        assertEquals(movie.progressFraction, movieProgress.fraction)
        assertEquals("tt1|movie", movie.logicalContentKey)

        assertEquals(DetailsDownloadProgressKind.Determinate, episodeProgress.kind)
        assertEquals(episode.downloadsScreenProgressPercent(), episodeProgress.percent)
        assertEquals(episode.progressFraction, episodeProgress.fraction)
        assertEquals("tt1|2|5", episode.logicalContentKey)
        assertEquals(downloadLogicalContentKey(" tt1 ", 2, 5), episode.logicalContentKey)
    }

    @Test
    fun unknownSizeIsIndeterminateAndAFinishedDownloadIsACompletedMark() {
        val unknown = item(
            id = "unknown",
            parentMetaId = "tt9",
            status = DownloadStatus.Downloading,
            downloadedBytes = 20L,
            totalBytes = null,
        )
        val zeroTotal = unknown.copy(id = "zero", parentMetaId = "tt0", totalBytes = 0L)
        val finished = item(
            id = "done",
            parentMetaId = "tt9",
            seasonNumber = 1,
            episodeNumber = 1,
            status = DownloadStatus.Completed,
            downloadedBytes = 80L,
            totalBytes = 80L,
            localFileUri = "file:///done.mkv",
        )
        val items = listOf(unknown, zeroTotal, finished)

        val unknownProgress = detailsDownloadProgress(items, unknown.logicalContentKey)
        assertEquals(DetailsDownloadProgressKind.Indeterminate, unknownProgress.kind)
        assertNull(unknownProgress.percent)
        assertNull(unknown.downloadsScreenProgressPercent())
        assertEquals(
            DetailsDownloadProgressKind.Indeterminate,
            detailsDownloadProgress(items, zeroTotal.logicalContentKey).kind,
        )
        assertEquals(
            DetailsDownloadProgressKind.Completed,
            detailsDownloadProgress(items, finished.logicalContentKey).kind,
        )
        assertEquals(DetailsDownloadProgress.None, detailsDownloadProgress(items, "other|movie"))
    }

    @Test
    fun pausedQueuedAndFailedRowsDoNotShowADownloadingBar() {
        val parent = "tt4"
        val items = listOf(
            item(id = "paused", parentMetaId = parent, status = DownloadStatus.Paused, downloadedBytes = 10L, totalBytes = 40L),
            item(id = "queued", parentMetaId = "tt5", status = DownloadStatus.Queued, totalBytes = 40L),
            item(id = "failed", parentMetaId = "tt6", status = DownloadStatus.Failed, totalBytes = 40L),
        )

        items.forEach { item ->
            assertEquals(DetailsDownloadProgress.None, detailsDownloadProgress(items, item.logicalContentKey))
        }
    }

    @Test
    fun anActiveDownloadWinsOverAFinishedCopyOfTheSameTitle() {
        val finished = item(
            id = "old",
            parentMetaId = "tt2",
            status = DownloadStatus.Completed,
            downloadedBytes = 50L,
            totalBytes = 50L,
            localFileUri = "file:///old.mkv",
        )
        val active = finished.copy(
            id = "new",
            status = DownloadStatus.Downloading,
            downloadedBytes = 10L,
            totalBytes = 50L,
            localFileUri = null,
        )

        val progress = detailsDownloadProgress(listOf(finished, active), active.logicalContentKey)

        assertEquals(DetailsDownloadProgressKind.Determinate, progress.kind)
        assertEquals(active.downloadsScreenProgressPercent(), progress.percent)
    }

    @Test
    fun playUsesAPlayablePartialAndOtherwiseOpensTheStreamList() {
        val movie = item(
            id = "partial-movie",
            parentMetaId = "tt1",
            videoId = "tt1",
            status = DownloadStatus.Downloading,
            downloadedBytes = 9_000_000L,
            totalBytes = 40_000_000L,
            earlyPlayReady = true,
        )
        val notReady = movie.copy(id = "not-ready", earlyPlayReady = false)
        val episode = item(
            id = "partial-episode",
            parentMetaId = "tt1",
            seasonNumber = 1,
            episodeNumber = 4,
            videoId = "tt1:1:4",
            status = DownloadStatus.Paused,
            downloadedBytes = 9_000_000L,
            totalBytes = 20_000_000L,
            earlyPlayReady = true,
        )
        val finished = item(
            id = "finished-movie",
            parentMetaId = "tt8",
            videoId = "tt8",
            status = DownloadStatus.Completed,
            localFileUri = "file:///finished.mkv",
        )

        assertTrue(movie.isPlayable)
        assertEquals(
            movie.id,
            matchingPlayableDownload(listOf(notReady, movie), "tt1", null, null, "tt1", DownloadItem::isPlayable)?.id,
        )
        assertNull(
            matchingPlayableDownload(listOf(notReady), "tt1", null, null, "tt1", DownloadItem::isPlayable),
        )
        assertEquals(
            episode.id,
            matchingPlayableDownload(listOf(movie, episode), "tt1", 1, 4, "tt1:1:4", DownloadItem::isPlayable)?.id,
        )
        assertNull(
            matchingPlayableDownload(
                listOf(episode.copy(earlyPlayReady = false)),
                "tt1",
                1,
                4,
                "tt1:1:4",
                DownloadItem::isPlayable,
            ),
        )
        assertEquals(
            finished.id,
            matchingPlayableDownload(listOf(finished), "tt8", null, null, "tt8", DownloadItem::isPlayable)?.id,
        )
        assertNull(
            matchingPlayableDownload(emptyList(), "tt1", null, null, "tt1", DownloadItem::isPlayable),
        )
    }

    private fun item(
        id: String,
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String = id,
        status: DownloadStatus,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = null,
        earlyPlayReady: Boolean = false,
        localFileUri: String? = null,
    ) = DownloadItem(
        id = id,
        contentType = if (seasonNumber != null) "series" else "movie",
        parentMetaId = parentMetaId,
        parentMetaType = if (seasonNumber != null) "series" else "movie",
        videoId = videoId,
        title = "Title",
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        streamTitle = "Stream",
        providerName = "Addon",
        sourceUrl = "https://cdn.example/$id.mkv",
        fileName = "$id.mkv",
        status = status,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        localFileUri = localFileUri,
        earlyPlayReady = earlyPlayReady,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
