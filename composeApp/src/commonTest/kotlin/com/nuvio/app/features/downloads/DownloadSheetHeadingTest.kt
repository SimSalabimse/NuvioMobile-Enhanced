package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadSheetHeadingTest {
    @Test
    fun showAndEpisodeEachKeepTheirOwnLine() {
        assertEquals(
            listOf("Show", "S1E1 · Episode 1"),
            downloadSheetHeadingLines("Show", "S1E1", "Episode 1"),
        )
    }

    @Test
    fun aMissingShowOrEpisodeDropsThatLine() {
        assertEquals(listOf("S1E1 · Episode 1"), downloadSheetHeadingLines("  ", "S1E1", "Episode 1"))
        assertEquals(listOf("Show"), downloadSheetHeadingLines("Show", null, "  "))
        assertEquals(emptyList(), downloadSheetHeadingLines(" ", null, null))
    }
}

class SeasonFillChoiceTest {
    @Test
    fun theSmallestBudgetThatSelectsTheSameEpisodesIsTheSelectedChip() {
        val keys = (1..12).map { "e$it" }
        val oneGigabyte = 1_000_000_000L

        assertEquals(5, matchingSeasonFillGb(keys, keys.take(5), oneGigabyte))
        assertEquals(10, matchingSeasonFillGb(keys, keys.take(10), oneGigabyte))
        assertNull(matchingSeasonFillGb(keys, keys.take(3), oneGigabyte))
        assertNull(matchingSeasonFillGb(keys, emptyList(), oneGigabyte))
    }

    @Test
    fun aShortSeasonMatchesTheSmallestBudgetThatSelectsEveryEpisode() {
        val keys = listOf("e1", "e2")
        assertEquals(5, matchingSeasonFillGb(keys, keys, 1_000_000_000L))
    }

    @Test
    fun aSelectionThatIsNotALeadingRunMatchesNoChip() {
        val keys = listOf("e1", "e2", "e3", "e4", "e5")
        assertNull(matchingSeasonFillGb(keys, listOf("e1", "e3"), 1_000_000_000L))
    }
}
