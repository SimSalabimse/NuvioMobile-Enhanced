package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeasonDownloadSheetStateTest {
    @Test
    fun `download enablement follows source changes cancels and failures`() {
        val initial = listOf(
            row(key = "e1", sourceId = "auto-1080", anchor = true),
            row(key = "e3", sourceId = "auto-1080"),
            row(key = "e4", sourceId = "auto-1080"),
        )
        assertTrue(seasonSourcePickIsAutomatic(autoPicked = true))
        val initialButton = seasonDownloadButtonState(selectionConfirmed = true, queuing = false, rows = initial)
        assertTrue(initialButton.enabled)
        assertFalse(initialButton.loading)

        // Manual top Change: the anchor adopts the new source immediately; the others re-resolve.
        // While that lookup is open the button shows progress instead of sitting disabled.
        val resolving = applySeasonSourceRows(initial, seasonSourceId = "manual-720")
        assertEquals("manual-720", resolving.first { it.key == "e1" }.sourceId)
        assertTrue(resolving.first { it.key == "e3" }.matching)
        assertTrue(resolving.first { it.key == "e4" }.matching)
        val resolvingButton = seasonDownloadButtonState(selectionConfirmed = true, queuing = false, rows = resolving)
        assertFalse(resolvingButton.enabled)
        assertTrue(resolvingButton.loading)
        assertFalse(seasonSourcePickIsAutomatic(autoPicked = false))

        val afterTopChange = finishSeasonResolution(
            resolving,
            resolvedSourceIds = mapOf("e3" to "manual-720", "e4" to "manual-720"),
        )
        assertEquals(listOf("manual-720", "manual-720", "manual-720"), afterTopChange.map { it.sourceId })
        assertTrue(noneMatching(afterTopChange))
        assertEnabled(afterTopChange)

        // Per-episode Change touches only that episode. Download stays enabled.
        val afterEpisode = applyEpisodeSourceRow(afterTopChange, episodeKey = "e3", sourceId = "episode-4k")
        assertEquals("episode-4k", afterEpisode.first { it.key == "e3" }.sourceId)
        assertTrue(afterEpisode.first { it.key == "e3" }.overridden)
        assertEquals("manual-720", afterEpisode.first { it.key == "e1" }.sourceId)
        assertEquals("manual-720", afterEpisode.first { it.key == "e4" }.sourceId)
        assertFalse(afterEpisode.first { it.key == "e1" }.overridden)
        assertFalse(afterEpisode.first { it.key == "e4" }.overridden)
        assertEnabled(afterEpisode)

        // A later season-wide change keeps the manual episode and moves the ones still on the default.
        val seasonAgain = applySeasonSourceRows(afterEpisode, seasonSourceId = "season-480")
        assertEquals("episode-4k", seasonAgain.first { it.key == "e3" }.sourceId)
        assertTrue(seasonAgain.first { it.key == "e3" }.overridden)
        assertFalse(seasonAgain.first { it.key == "e3" }.matching)
        assertEquals("season-480", seasonAgain.first { it.key == "e1" }.sourceId)
        assertTrue(seasonAgain.first { it.key == "e4" }.matching)
        val seasonSettled = finishSeasonResolution(seasonAgain, resolvedSourceIds = mapOf("e4" to "season-480"))
        assertEquals("episode-4k", seasonSettled.first { it.key == "e3" }.sourceId)
        assertEquals("season-480", seasonSettled.first { it.key == "e4" }.sourceId)
        assertEnabled(seasonSettled)

        // Dismissing the picker without a choice does not resolve and does not disable Download.
        val cancelled = rowsAfterSourcePickerCancel(seasonSettled)
        assertEquals(seasonSettled, cancelled)
        assertEnabled(cancelled)

        // A failed lookup must not leave matching set. The anchor is still a source, so Download enables.
        val failedStart = applySeasonSourceRows(seasonSettled, seasonSourceId = "broken")
        assertTrue(seasonDownloadButtonState(selectionConfirmed = true, queuing = false, rows = failedStart).loading)
        val failed = failSeasonResolution(failedStart)
        assertTrue(noneMatching(failed))
        assertEquals("broken", failed.first { it.key == "e1" }.sourceId)
        assertEquals("episode-4k", failed.first { it.key == "e3" }.sourceId)
        assertTrue(failed.first { it.key == "e4" }.unresolved)
        assertEnabled(failed)

        // Quality chip is the same season-wide change: manual picks stay, then Download enables.
        val quality = applySeasonSourceRows(failed, seasonSourceId = "quality-1080")
        assertEquals("episode-4k", quality.first { it.key == "e3" }.sourceId)
        assertTrue(quality.first { it.key == "e3" }.overridden)
        val afterQuality = finishSeasonResolution(quality, resolvedSourceIds = mapOf("e4" to "quality-1080"))
        assertEquals("quality-1080", afterQuality.first { it.key == "e1" }.sourceId)
        assertEquals("episode-4k", afterQuality.first { it.key == "e3" }.sourceId)
        assertEquals("quality-1080", afterQuality.first { it.key == "e4" }.sourceId)
        assertFalse(seasonSourcePickIsAutomatic(autoPicked = false))
        assertEnabled(afterQuality)
    }

    @Test
    fun `a successful queue does not report already in downloads`() {
        assertEquals(
            SeasonDownloadNotice.Queued,
            seasonDownloadNotice(queued = 3, failureMessage = "Already in Downloads", alreadyInDownloads = 1),
        )
        assertEquals(
            SeasonDownloadNotice.AlreadyInDownloads,
            seasonDownloadNotice(queued = 0, failureMessage = null, alreadyInDownloads = 3),
        )
        assertEquals(
            SeasonDownloadNotice.Failed,
            seasonDownloadNotice(queued = 0, failureMessage = "No direct stream link available", alreadyInDownloads = 0),
        )
        assertEquals(
            SeasonDownloadNotice.None,
            seasonDownloadNotice(queued = 0, failureMessage = null, alreadyInDownloads = 0),
        )
    }

    @Test
    fun `reduce motion dismisses without a slide`() {
        assertEquals(300, seasonSheetDismissDurationMs(reduceMotion = false))
        assertTrue(seasonSheetDismissDurationMs(reduceMotion = false) <= 300)
        assertEquals(0, seasonSheetDismissDurationMs(reduceMotion = true))
    }

    private fun assertEnabled(rows: List<SeasonSheetRow>) {
        val state = seasonDownloadButtonState(selectionConfirmed = true, queuing = false, rows = rows)
        assertTrue(state.enabled)
        assertFalse(state.loading)
    }

    private fun noneMatching(rows: List<SeasonSheetRow>): Boolean = rows.none { it.matching }

    private fun row(
        key: String,
        sourceId: String?,
        anchor: Boolean = false,
        overridden: Boolean = false,
    ): SeasonSheetRow = SeasonSheetRow(
        key = key,
        confirmed = true,
        anchor = anchor,
        existingDownload = false,
        overridden = overridden,
        userChecked = true,
        matching = false,
        sourceId = sourceId,
        unresolved = false,
    )
}
