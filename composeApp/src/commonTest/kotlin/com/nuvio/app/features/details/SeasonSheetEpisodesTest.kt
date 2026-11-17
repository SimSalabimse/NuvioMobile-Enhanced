package com.nuvio.app.features.details

import com.nuvio.app.features.player.skip.MovieSegmentSurface
import com.nuvio.app.features.player.skip.OwnAccountMarks
import com.nuvio.app.features.player.skip.movieSegmentStatusAttached
import com.nuvio.app.features.player.skip.movieSegmentTapPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonSheetEpisodesTest {
    private val catalog = listOf(
        MetaVideo(id = "tt1:3:1", title = "One", season = 3, episode = 1, imdbId = "tt1"),
        MetaVideo(id = "tt1:3:2", title = "Two", season = 3, episode = 2, imdbId = "tt1"),
    )

    @Test
    fun deletedEpisodeComesFromTheLedgerAndAForeignKeyDoesNot() {
        val ledger = OwnAccountMarks(
            confirmed = mapOf("tt1:3:4" to setOf("intro")),
            pending = mapOf(
                "tt1:3:2" to setOf("recap"),
                "tt9:3:4" to setOf("intro"),
                "not-an-episode" to setOf("intro"),
            ),
        )
        val rows = seasonSheetEpisodes(catalog, ledger, setOf("tt1"), season = 3)
        assertEquals(listOf(1, 2, 4), rows.map { it.episode })
        assertEquals("Two", rows[1].title)
        assertEquals("", rows.last().title)
        assertEquals("tt1", rows.last().imdbId)
        assertFalse(rows.any { it.id.startsWith("tt9") })
        assertNull(parseLedgerEpisodeKey("tt1:movie"))
    }

    @Test
    fun swipeActionIsHiddenUntilTheRowMoves() {
        assertEquals(false, seasonSwipeRevealsAction(currentSettled = true, targetSettled = true))
        assertEquals(true, seasonSwipeRevealsAction(currentSettled = true, targetSettled = false))
    }

    @Test
    fun movieStatusIsOnTheDetailsAndPlayerRoutesOnly() {
        assertTrue(movieSegmentStatusAttached(MovieSegmentSurface.Details, isMovie = true))
        assertTrue(movieSegmentStatusAttached(MovieSegmentSurface.PlayerSubmit, isMovie = true))
        assertFalse(movieSegmentStatusAttached(MovieSegmentSurface.Details, isMovie = false))
        assertEquals("DetailRoute > movie page > Overview", movieSegmentTapPath(MovieSegmentSurface.Details))
        assertEquals("PlayerRoute > controls > Flag > submit strip", movieSegmentTapPath(MovieSegmentSurface.PlayerSubmit))
    }
}
