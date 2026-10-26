package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PlayerEpisodesLoadTest {
    @Test
    fun `spinner while the list is still loading`() {
        assertEquals(
            EpisodesPanelBody.Loading,
            episodesPanelBody(loading = true, failed = false, episodeCount = 0, emptyNeedsRetry = false),
        )
    }

    @Test
    fun `failure offers retry instead of an empty catalogue`() {
        assertEquals(
            EpisodesPanelBody.Failed,
            episodesPanelBody(loading = false, failed = true, episodeCount = 0, emptyNeedsRetry = false),
        )
    }

    @Test
    fun `empty result before a cache bypass offers retry`() {
        assertEquals(
            EpisodesPanelBody.Failed,
            episodesPanelBody(loading = false, failed = false, episodeCount = 0, emptyNeedsRetry = true),
        )
    }

    @Test
    fun `no episodes only after a finished load that is truly empty`() {
        assertEquals(
            EpisodesPanelBody.Empty,
            episodesPanelBody(loading = false, failed = false, episodeCount = 0, emptyNeedsRetry = false),
        )
    }

    @Test
    fun `a loaded list stays on screen while another load is in flight`() {
        assertEquals(
            EpisodesPanelBody.List,
            episodesPanelBody(loading = true, failed = true, episodeCount = 3, emptyNeedsRetry = true),
        )
    }

    @Test
    fun `empty incoming list does not replace episodes already loaded`() {
        val current = listOf(episode("tt1:1:22"))
        val merged = mergePlayerEpisodes(current, emptyList())
        assertSame(current, merged)
    }

    @Test
    fun `non-empty incoming list replaces the player episodes`() {
        val incoming = listOf(episode("tt1:1:1"), episode("tt1:1:2"))
        assertEquals(incoming, mergePlayerEpisodes(listOf(episode("old")), incoming))
    }

    @Test
    fun `empty incoming list is kept when nothing was loaded yet`() {
        assertEquals(emptyList(), mergePlayerEpisodes(emptyList(), emptyList()))
    }

    private fun episode(id: String) = MetaVideo(id = id, title = id)
}
