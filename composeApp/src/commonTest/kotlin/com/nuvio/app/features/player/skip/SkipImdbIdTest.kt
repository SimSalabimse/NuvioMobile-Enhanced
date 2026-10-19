package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class SkipImdbIdTest {
    private suspend fun resolve(contentId: String?, videoId: String?) = resolveMovieSkipImdbId(
        contentId, videoId,
        resolveTmdb = { if (it == 67178) "tt5675620" else null },
        resolveAnime = { _, _ -> null },
    )

    @Test
    fun imdbEpisodeIdIsUsedDirectly() = runTest {
        assertEquals("tt5675620", resolve("tt5675620", "tt5675620:2:13"))
    }

    @Test
    fun tmdbKeyedEpisodeResolvesToImdb() = runTest {
        assertEquals("tt5675620", resolve("tmdb:67178", "tmdb:67178:2:13"))
    }

    @Test
    fun unknownIdsGiveNothing() = runTest {
        assertNull(resolve("custom:abc", "custom:abc:2:13"))
    }
}
