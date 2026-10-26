package com.nuvio.app.features.details

import com.nuvio.app.features.player.EpisodesPanelBody
import com.nuvio.app.features.player.episodesKeptAfterIncompleteFetch
import com.nuvio.app.features.player.episodesPanelBody
import com.nuvio.app.features.tmdb.seasonsRefetchedOnRetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncompleteSeasonFallbackTest {
    private val loaded = listOf(
        MetaVideo(id = "tmdb:7:0:1", title = "Special", season = 0, episode = 1),
        MetaVideo(id = "tmdb:7:2:1", title = "Season two", season = 2, episode = 1),
    )

    @Test
    fun `one season failing keeps the loaded episodes visible and is not cached`() {
        assertEquals(loaded, MetaDetailsRepository.visibleFallbackEpisodes(loaded, episodesComplete = false))
        assertFalse(
            MetaDetailsRepository.shouldCacheFetchedStandaloneMeta(
                type = "series",
                videoCount = loaded.size,
                seasonsComplete = false,
            ),
        )
        assertEquals(
            loaded,
            episodesKeptAfterIncompleteFetch(
                current = emptyList(),
                incoming = loaded,
                episodesComplete = false,
            ),
        )
        assertEquals(
            EpisodesPanelBody.Partial,
            episodesPanelBody(
                loading = false,
                failed = false,
                episodeCount = loaded.size,
                emptyNeedsRetry = false,
                episodesComplete = false,
            ),
        )
    }

    @Test
    fun `load runs again when the season fill is incomplete`() {
        assertFalse(
            MetaDetailsRepository.shouldSkipLoadedMetaReload(
                sameShow = true,
                isLoading = false,
                episodesComplete = false,
            ),
        )
        assertTrue(
            MetaDetailsRepository.shouldSkipLoadedMetaReload(
                sameShow = true,
                isLoading = false,
                episodesComplete = true,
            ),
        )
    }

    @Test
    fun `retry refetches only the season that failed`() {
        assertEquals(
            listOf(1),
            seasonsRefetchedOnRetry(requested = listOf(0, 1, 2), failedSeasons = setOf(1)),
        )
    }
}
