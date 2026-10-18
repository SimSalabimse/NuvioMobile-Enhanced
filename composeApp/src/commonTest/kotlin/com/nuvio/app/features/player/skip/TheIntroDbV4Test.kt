package com.nuvio.app.features.player.skip

import com.nuvio.app.features.settings.SubmissionStatus
import com.nuvio.app.features.settings.groupSubmissions
import com.nuvio.app.features.settings.segmentChipText
import com.nuvio.app.features.settings.statusTotals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TheIntroDbV4Test {
    private fun row(tmdb: Int? = null, tvdb: Int? = null) =
        TheIntroDbUserSubmission(tmdbId = tmdb, tvdbId = tvdb, season = 1, episode = 2, segment = "intro", startMs = 30_000, endMs = 90_000)

    @Test
    fun keepsOnlyTheRowsOfTheTitle() {
        val rows = listOf(row(tmdb = 1), row(tmdb = 2), row(tvdb = 10), row(tvdb = 11), row())
        assertEquals(listOf(rows[0], rows[2], rows[4]), submissionsOfTitle(rows, tmdbId = 1, tvdbId = 10))
        // tvdb id of the title unknown: rows naming only a tvdb id are kept.
        assertEquals(listOf(rows[0], rows[2], rows[3], rows[4]), submissionsOfTitle(rows, tmdbId = 1, tvdbId = null))
    }

    @Test
    fun v4MediaIsKeyedByTvdb() {
        val media = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<TheIntroDbMediaResponse>(
            """{"tvdb_id":121361,"type":"tv","season":1,"episode":1,"intro":[{"start_ms":1000,"end_ms":5000}]}""",
        )
        assertEquals(121361, media.tvdbId)
    }

    @Test
    fun submissionsGroupByShowThenEpisode() {
        val rows = listOf(
            row(tvdb = 10).copy(episode = 3, status = "rejected"),
            row(tvdb = 10).copy(segment = "credits", status = "accepted"),
            row(tvdb = 10),
            row(tmdb = 5).copy(type = "movie", season = null, episode = null, status = "approved"),
        )
        val groups = groupSubmissions(rows)
        assertEquals(listOf("tvdb:10", "tmdb:5"), groups.map { it.key })
        assertEquals(listOf(2, 3), groups[0].episodes.map { it.episode })
        assertEquals(2, groups[0].episodes[0].submissions.size)
        assertEquals(mapOf(SubmissionStatus.Accepted to 2, SubmissionStatus.Pending to 1, SubmissionStatus.Rejected to 1), statusTotals(rows))
        assertEquals("Credits 00:30–01:30", segmentChipText(rows[1]))
    }

    @Test
    fun skipLinkAsksBeforeImport() {
        val text = describeSharedSkipMark(SharedSkipMark("tt1", 1, 2, "intro", 30_000, 90_000))
        assertTrue(text.startsWith("Intro mark for S1E2 (00:30–01:30)"))
    }
}
