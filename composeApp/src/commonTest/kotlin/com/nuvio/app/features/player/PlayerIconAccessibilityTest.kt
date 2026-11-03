package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerIconAccessibilityTest {
    @Test
    fun everyIconOnlyControlHasAName() {
        PlayerIconControl.entries.forEach { control ->
            val description = playerIconControlDescription(
                control = control,
                decrease = "Decrease",
                increase = "Increase",
                clearSearch = "Clear search",
            )
            assertTrue(description.isNotBlank(), control.name)
        }
        assertEquals(
            "Decrease",
            playerIconControlDescription(PlayerIconControl.SubtitleDecrease, "Decrease", "Increase", "Clear search"),
        )
        assertEquals(
            "Increase",
            playerIconControlDescription(PlayerIconControl.SubtitleIncrease, "Decrease", "Increase", "Clear search"),
        )
        assertEquals(
            "Clear search",
            playerIconControlDescription(PlayerIconControl.LiveSearchClear, "Decrease", "Increase", "Clear search"),
        )
    }

    @Test
    fun everyDownloadIndicatorHasAName() {
        val indicators = listOf(
            PlayerDownloadIndicator.NotDownloaded,
            PlayerDownloadIndicator.Done,
            PlayerDownloadIndicator.Progress(40),
            PlayerDownloadIndicator.Spinner,
            PlayerDownloadIndicator.Waiting,
            PlayerDownloadIndicator.Paused,
            PlayerDownloadIndicator.Failed,
        )
        indicators.forEach { indicator ->
            assertTrue(playerDownloadContentDescription(indicator).isNotBlank(), indicator.toString())
        }
        assertEquals("Download", playerDownloadContentDescription(PlayerDownloadIndicator.NotDownloaded))
        assertEquals("Downloaded", playerDownloadContentDescription(PlayerDownloadIndicator.Done))
        assertEquals("Downloading 40%", playerDownloadContentDescription(PlayerDownloadIndicator.Progress(40)))
        assertEquals("Downloading", playerDownloadContentDescription(PlayerDownloadIndicator.Spinner))
        assertEquals("Download queued", playerDownloadContentDescription(PlayerDownloadIndicator.Waiting))
        assertEquals("Download paused", playerDownloadContentDescription(PlayerDownloadIndicator.Paused))
        assertEquals("Download failed", playerDownloadContentDescription(PlayerDownloadIndicator.Failed))
    }
}
