package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.ShowAutoSkip
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import com.nuvio.app.features.watchprogress.isStaleContinueWatching
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Build181LogicTest {
    private val gb = 1_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun item(
        id: String,
        title: String = "Show",
        bytes: Long? = null,
        updated: Long = 0L,
        episodeTitle: String? = null,
        season: Int? = 1,
    ) = DownloadItem(
        id = id, contentType = "series", parentMetaId = "tt1", parentMetaType = "series", videoId = id,
        title = title, seasonNumber = season, episodeNumber = season?.let { 1 }, episodeTitle = episodeTitle,
        streamTitle = "", providerName = "", sourceUrl = "https://x", fileName = "$id.mkv",
        status = DownloadStatus.Completed, totalBytes = bytes, createdAtEpochMs = 0L, updatedAtEpochMs = updated,
    )

    @Test
    fun `autopilot deletes oldest watched first until enough is free`() {
        val candidates = listOf(
            AutopilotCandidate("new", 4 * gb, finishedAtEpochMs = 300),
            AutopilotCandidate("old", 2 * gb, finishedAtEpochMs = 100),
            AutopilotCandidate("mid", 3 * gb, finishedAtEpochMs = 200),
        )
        assertEquals(listOf("old", "mid"), autopilotPicks(candidates, freeBytes = 1 * gb, keepFreeBytes = 5 * gb))
        assertEquals(listOf("old"), autopilotPicks(candidates, freeBytes = 4 * gb, keepFreeBytes = 5 * gb))
        assertEquals(emptyList(), autopilotPicks(candidates, freeBytes = 6 * gb, keepFreeBytes = 5 * gb))
        // Not enough to reach the target: everything that may go, goes.
        assertEquals(listOf("old", "mid", "new"), autopilotPicks(candidates, freeBytes = 0L, keepFreeBytes = 50 * gb))
    }

    @Test
    fun `fill by size takes the leading episodes that fit`() {
        val keys = listOf("e1", "e2", "e3", "e4")
        assertEquals(listOf("e1", "e2"), fillSelection(keys, episodeBytes = 2 * gb, budgetBytes = 5 * gb))
        assertEquals(keys, fillSelection(keys, episodeBytes = 1 * gb, budgetBytes = 50 * gb))
        assertEquals(emptyList(), fillSelection(keys, episodeBytes = 6 * gb, budgetBytes = 5 * gb))
    }

    @Test
    fun `typical episode size is the median of the show's downloads, else a guess`() {
        val items = listOf(item("a", bytes = 1 * gb), item("b", bytes = 5 * gb), item("c", bytes = 2 * gb))
        assertEquals(2 * gb, typicalEpisodeBytes("tt1", items))
        assertEquals(1_500_000_000L, typicalEpisodeBytes("tt9", items))
    }

    @Test
    fun `stale continue watching counts a restore as a touch and 0 days as never`() {
        val now = 100 * day
        assertTrue(isStaleContinueWatching(lastUpdatedEpochMs = 10 * day, restoredAtEpochMs = null, days = 60, nowEpochMs = now))
        assertFalse(isStaleContinueWatching(lastUpdatedEpochMs = 50 * day, restoredAtEpochMs = null, days = 60, nowEpochMs = now))
        assertFalse(isStaleContinueWatching(lastUpdatedEpochMs = 10 * day, restoredAtEpochMs = 90 * day, days = 60, nowEpochMs = now))
        assertFalse(isStaleContinueWatching(lastUpdatedEpochMs = 0L, restoredAtEpochMs = null, days = 0, nowEpochMs = now))
    }

    @Test
    fun `health check flags cut-off files and short probes, and stays quiet when unsure`() {
        assertNotNull(downloadProblem(expectedBytes = 4 * gb, actualBytes = 1 * gb, probedDurationMs = null, expectedDurationMs = null))
        assertNull(downloadProblem(expectedBytes = 4 * gb, actualBytes = 4 * gb - 1, probedDurationMs = null, expectedDurationMs = null))
        assertNotNull(downloadProblem(expectedBytes = null, actualBytes = 1 * gb, probedDurationMs = 10 * 60_000L, expectedDurationMs = 45 * 60_000L))
        assertNull(downloadProblem(expectedBytes = null, actualBytes = 1 * gb, probedDurationMs = 40 * 60_000L, expectedDurationMs = 45 * 60_000L))
        assertNull(downloadProblem(expectedBytes = null, actualBytes = null, probedDurationMs = null, expectedDurationMs = 45 * 60_000L))
    }

    @Test
    fun `runtime text is read in minutes`() {
        assertEquals(130, runtimeMinutes("130 min"))
        assertEquals(130, runtimeMinutes("2h 10min"))
        assertEquals(120, runtimeMinutes("2h"))
        assertEquals(45, runtimeMinutes("45"))
        assertNull(runtimeMinutes("unknown"))
    }

    @Test
    fun `downloads search ignores case and accents and sort orders groups`() {
        val items = listOf(
            item("a", title = "Amélie", bytes = 1 * gb, updated = 3, season = null),
            item("b", title = "Zodiac", bytes = 9 * gb, updated = 1, season = null),
            item("c", title = "The Mentalist", bytes = 4 * gb, updated = 2, episodeTitle = "Red John"),
        )
        assertEquals(listOf("a"), items.matchingDownloadsQuery("AMELIE").map { it.id })
        assertEquals(listOf("c"), items.matchingDownloadsQuery("red john").map { it.id })
        assertEquals(items, items.matchingDownloadsQuery("  "))
        val groups = items.map(::listOf)
        assertEquals(listOf("a", "c", "b"), groups.sortedDownloadGroups(DownloadsSort.Name).map { it.first().id })
        assertEquals(listOf("b", "c", "a"), groups.sortedDownloadGroups(DownloadsSort.Size).map { it.first().id })
        assertEquals(listOf("a", "c", "b"), groups.sortedDownloadGroups(DownloadsSort.Date).map { it.first().id })
    }

    @Test
    fun `per-show auto-skip override`() {
        val chosen = setOf(AutoSkipSegmentType.entries.first())
        assertEquals(chosen, ShowAutoSkip.Default.segmentTypes(chosen))
        assertEquals(AutoSkipSegmentType.entries.toSet(), ShowAutoSkip.Always.segmentTypes(chosen))
        assertEquals(emptySet(), ShowAutoSkip.Never.segmentTypes(chosen))
        assertEquals(ShowAutoSkip.Default, ShowAutoSkip.Never.next())
    }

    @Test
    fun `unaired queue looks again at the next air date, else tomorrow`() {
        val now = 1_000 * day
        val soon = MetaVideo(id = "e2", title = "E2", released = "1970-01-01T00:00:00Z")
        assertEquals(now + day, nextAirCheckEpochMs(listOf(soon), now))
        assertEquals(now + day, nextAirCheckEpochMs(emptyList(), now))
        val later = MetaVideo(id = "e3", title = "E3", released = "1972-10-01") // day 1004
        assertEquals(1_004 * day, nextAirCheckEpochMs(listOf(soon, later), now))
    }
}
