package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadsSeasonOverviewTest {
    @Test
    fun downloadsSeasonUsesTheSameEpisodesAndDoesNotCarryResume() {
        val first = item(showId = "tt10", season = 1, episode = 1, videoId = "tt10:1:1")
        val second = item(showId = "tt10", season = 1, episode = 2, videoId = "tt10:1:2")
        val other = item(showId = "tt10", season = 2, episode = 1, videoId = "tt10:2:1")
        val content = downloadsSeasonOverviewContent(listOf(second, other, first), "tt10", 1)
        assertEquals(listOf(1, 2), content!!.episodes.map { it.episode })
        assertEquals("tt10", content.meta.id)
        assertEquals("series", content.meta.type)
        assertNull(downloadsSeasonOverviewContent(listOf(other), "tt10", 1))
        val request = second.toEpisodeSubmitRequest()
        assertEquals("tt10:1:2", request.videoId)
        assertEquals(1, request.seasonNumber)
        assertEquals(2, request.episodeNumber)
        assertEquals("tt10", request.parentMetaId)
    }

    private fun item(showId: String, season: Int, episode: Int, videoId: String) = DownloadItem(
        id = videoId,
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        videoId = videoId,
        title = "Show",
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = "Episode $episode",
        streamTitle = "File",
        providerName = "Local",
        sourceUrl = "https://example.invalid/$videoId",
        fileName = "$videoId.mp4",
        status = DownloadStatus.Completed,
        createdAtEpochMs = episode.toLong(),
        updatedAtEpochMs = episode.toLong(),
        localFileUri = "file:///tmp/$videoId.mp4",
    )
}
