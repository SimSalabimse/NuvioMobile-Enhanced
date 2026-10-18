package com.nuvio.app.features.player.skip

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
    fun allSubmissionsListNamesTheShow() {
        assertEquals("TVDB 10 · S1E2 · intro 00:30–01:30 · pending", submissionLine(row(tvdb = 10), withTitle = true))
        assertEquals("S1E2 · intro 00:30–01:30 · pending", submissionLine(row(tvdb = 10)))
    }

    @Test
    fun skipLinkAsksBeforeImport() {
        val text = describeSharedSkipMark(SharedSkipMark("tt1", 1, 2, "intro", 30_000, 90_000))
        assertTrue(text.startsWith("Intro mark for S1E2 (00:30–01:30)"))
    }
}
