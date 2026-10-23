package com.nuvio.app.features

import com.nuvio.app.features.details.OwnedTitles
import com.nuvio.app.features.details.ownedKeys
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.planTonight
import com.nuvio.app.features.player.sleepFadeFactor
import com.nuvio.app.features.player.skip.SharedSkipMark
import com.nuvio.app.features.player.skip.TheIntroDbUserSubmission
import com.nuvio.app.features.player.skip.parseSkipMarkLink
import com.nuvio.app.features.player.skip.submissionLine
import com.nuvio.app.features.player.skip.toLink
import com.nuvio.app.features.stats.ShowStat
import com.nuvio.app.features.stats.WatchStats
import com.nuvio.app.features.stats.WatchStatsData
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Build181PhaseBTest {
    @Test
    fun sleepFadeRampsDownOverTheLastTwentySeconds() {
        assertEquals(1f, sleepFadeFactor(60_000L))
        assertEquals(0.5f, sleepFadeFactor(10_000L))
        assertEquals(0f, sleepFadeFactor(0L))
    }

    @Test
    fun skipMarkLinkRoundTrips() {
        val mark = SharedSkipMark("tt123", 2, 5, "intro", 31_000L, 95_500L)
        assertEquals(mark, parseSkipMarkLink(Url(mark.toLink())))
        val movie = SharedSkipMark("tt9", null, null, "credits", 1L, 2L)
        assertEquals(movie, parseSkipMarkLink(Url(movie.toLink())))
        assertNull(parseSkipMarkLink(Url("nuvio://skip?id=tt1&type=intro&from=5")))
    }

    private fun cw(id: String, durationMin: Long, resumeMin: Long = 0L) = ContinueWatchingItem(
        parentMetaId = id, parentMetaType = "series", videoId = id, title = id, subtitle = "", imageUrl = null,
        resumePositionMs = resumeMin * 60_000L, durationMs = durationMin * 60_000L, progressFraction = 0f,
    )

    @Test
    fun tonightPlanPutsGoalsFirstAndFitsTheBudget() {
        val items = listOf(cw("a", 45), cw("b", 25), cw("goal", 50, resumeMin = 10), cw("c", 60))
        val plan = planTonight(items, 90 * 60_000L) { it.parentMetaId == "goal" }
        assertEquals(listOf("goal", "a"), plan.map { it.parentMetaId })
        assertEquals(listOf("b"), planTonight(items, 30 * 60_000L) { false }.map { it.parentMetaId })
    }

    @Test
    fun weeklyRecapCountsTheLastSevenDays() {
        val data = WatchStatsData(
            dayMs = mapOf("2026-10-17" to 3_600_000L, "2026-10-11" to 60_000L, "2026-10-10" to 999_000L),
            shows = mapOf("s1" to ShowStat("Done Show"), "s2" to ShowStat("Ongoing"), "m1" to ShowStat("Film", type = "movie")),
            daySkippedMs = mapOf("2026-10-16" to 90_000L, "2026-10-01" to 5_000L),
            dayCompletedShows = mapOf("2026-10-15" to setOf("s1", "s2", "m1")),
        )
        val recap = WatchStats.weeklyRecap(data, isSeriesFinished = { id, _ -> id != "s2" }, today = "2026-10-17")
        assertEquals(3_660_000L, recap.watchedMs)
        assertEquals(listOf("Done Show"), recap.finishedSeries)
        assertEquals(90_000L, recap.skippedMs)
    }

    @Test
    fun ownedTitlesMatchByIdOrTitle() {
        val owned = OwnedTitles(
            downloaded = ownedKeys("tt1", "Dark").toSet(),
            watching = emptySet(),
            library = ownedKeys("tmdb:5", "Other").toSet(),
        )
        fun preview(id: String, name: String) = MetaPreview(id = id, type = "series", name = name)
        assertEquals("Downloaded", owned.statusLabel(preview("tmdb:70523", "Dark")))
        assertEquals("In library", owned.statusLabel(preview("tmdb:5", "Renamed")))
        assertNull(owned.statusLabel(preview("tmdb:6", "Nope")))
    }

    @Test
    fun submissionLineShowsEpisodeTimesAndStatus() {
        val line = submissionLine(TheIntroDbUserSubmission(season = 1, episode = 2, segment = "intro", status = "accepted", startMs = 30_000L))
        assertEquals("S1E2 · intro 00:30–end · accepted", line)
    }
}
