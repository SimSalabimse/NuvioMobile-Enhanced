package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals

class FinishSeriesGoalTest {
    private fun ep(s: Int, e: Int) = MetaVideo(id = "x:$s:$e", title = "S${s}E$e", season = s, episode = e)

    // Two seasons of 13 and a special (season 0), like The Punisher.
    private val videos = (1..2).flatMap { s -> (1..13).map { ep(s, it) } } + ep(0, 1)
    private val episodes = nextEpisodes(videos, season = 0, episode = 0, count = Int.MAX_VALUE)

    @Test
    fun goalStartsAfterTheFurthestWatchedEpisode() {
        // S1E2 skipped but S1E3 watched: the goal starts at S1E4, not at the old gap.
        assertEquals(3, goalStart(episodes, null) { it.season == 1 && it.episode in setOf(1, 3) })
        assertEquals(0, goalStart(episodes, null) { false })
    }

    @Test
    fun seriesEndsAfterTheLastEpisodeAndNeverWrapsAround() {
        assertEquals(26, episodes.size)
        // Last episode watched: done, no S1E1 and no special.
        assertEquals(episodes.size, goalStart(episodes, null) { it.season == 2 && it.episode == 13 })
        // Watched state not there (not loaded yet, or only the show is marked): the anchor holds.
        assertEquals(episodes.size, goalStart(episodes, 2 to 13) { false })
        assertEquals(25, goalStart(episodes, 2 to 12) { false })
        // Rewatching S1E1 does not move the goal back.
        assertEquals(25, goalStart(episodes, 2 to 12) { it.season == 1 && it.episode == 1 })
        // Nothing after S2E13.
        assertEquals(emptyList(), nextEpisodes(videos, 2, 13, 3))
    }

    @Test
    fun laterEpisodeKeepsTheFurthestPosition() {
        assertEquals(2 to 13, laterEpisode(2 to 13, 1 to 0))
        assertEquals(2 to 1, laterEpisode(1 to 13, 2 to 1))
        assertEquals(1 to 0, laterEpisode(null, 1 to 0))
    }
}
