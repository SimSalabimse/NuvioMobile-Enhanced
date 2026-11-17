package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeriesSmartDownloadCountTest {
    private fun episode(season: Int, episode: Int) =
        MetaVideo(id = "series:$season:$episode", title = "S${season}E$episode", season = season, episode = episode)

    private val videos = (1..6).map { episode(1, it) }

    @Test
    fun unsetOverrideUsesTheGlobalCount() {
        assertEquals(1, effectiveSmartDownloadCount(overrideCount = null, globalCount = 1))
        assertEquals(3, effectiveSmartDownloadCount(overrideCount = null, globalCount = 3))
        assertEquals(SeriesSmartDownloadCount.Default, SeriesSmartDownloadCount.fromStored(null))
        assertEquals(null, SeriesSmartDownloadCount.Default.count)
    }

    @Test
    fun eachChoiceReplacesTheGlobalCount() {
        val expected = mapOf(
            SeriesSmartDownloadCount.Zero to 0,
            SeriesSmartDownloadCount.One to 1,
            SeriesSmartDownloadCount.Two to 2,
            SeriesSmartDownloadCount.Three to 3,
            SeriesSmartDownloadCount.Five to 5,
        )
        expected.forEach { (choice, count) ->
            assertEquals(count, choice.count)
            assertEquals(count, effectiveSmartDownloadCount(choice.count, globalCount = 1))
            assertEquals(count, effectiveSmartDownloadCount(choice.count, globalCount = 3))
        }
    }

    @Test
    fun zeroDisablesNextEpisodeDownloads() {
        val keep = effectiveSmartDownloadCount(overrideCount = 0, globalCount = 3)
        assertEquals(0, keep)
        assertEquals(emptyList(), nextEpisodes(videos, season = 1, episode = 1, count = keep))
    }

    @Test
    fun globalChangeDoesNotMoveASetOverride() {
        assertEquals(2, effectiveSmartDownloadCount(overrideCount = 2, globalCount = 1))
        assertEquals(2, effectiveSmartDownloadCount(overrideCount = 2, globalCount = 3))
        assertEquals(1, effectiveSmartDownloadCount(overrideCount = null, globalCount = 1))
        assertEquals(3, effectiveSmartDownloadCount(overrideCount = null, globalCount = 3))
    }

    @Test
    fun pickerListsChoicesSelectedStateAndSeriesVisibility() {
        val selected = smartDownloadCountPickerState("series", SeriesSmartDownloadCount.Two)
        assertTrue(selected.visible)
        assertEquals("Smart downloads", selected.title)
        assertEquals(listOf("Default", "0", "1", "2", "3", "5"), selected.choices.map { it.label })
        assertEquals(
            listOf(
                SeriesSmartDownloadCount.Default,
                SeriesSmartDownloadCount.Zero,
                SeriesSmartDownloadCount.One,
                SeriesSmartDownloadCount.Two,
                SeriesSmartDownloadCount.Three,
                SeriesSmartDownloadCount.Five,
            ),
            selected.choices.map { it.count },
        )
        assertEquals(SeriesSmartDownloadCount.Two, selected.choices.single { it.selected }.count)
        assertEquals(listOf(false, false, false, true, false, false), selected.choices.map { it.selected })

        val movie = smartDownloadCountPickerState("movie", SeriesSmartDownloadCount.Five)
        assertFalse(movie.visible)
        assertEquals(selected.choices.map { it.label }, movie.choices.map { it.label })
        assertEquals(SeriesSmartDownloadCount.Five, movie.choices.single { it.selected }.count)
    }
}
