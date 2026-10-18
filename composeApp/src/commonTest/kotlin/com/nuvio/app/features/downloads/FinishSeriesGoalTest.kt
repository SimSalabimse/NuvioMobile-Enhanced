package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals

class FinishSeriesGoalTest {
    private fun ep(e: Int) = MetaVideo(id = "x:1:$e", title = "E$e", season = 1, episode = e)

    @Test
    fun goalStartsAfterTheFurthestWatchedEpisode() {
        val episodes = (1..5).map(::ep)
        // E2 skipped but E3 watched: the goal starts at E4, not at the old gap.
        assertEquals(3, goalStart(episodes) { it.episode in setOf(1, 3) })
        assertEquals(0, goalStart(episodes) { false })
        assertEquals(5, goalStart(episodes) { true })
    }
}
