package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovieSegmentStatusTest {
    @Test
    fun movieQueryOmitsSeasonAndEpisode() {
        val query = theIntroDbMovieQuery(tmdbId = 550, imdbId = "tt0137523", durationMs = 8_000_000L)
        assertTrue(query.contains("tmdb_id=550"))
        assertTrue(query.contains("duration_ms=8000000"))
        assertFalse(query.contains("season="))
        assertFalse(query.contains("episode="))
        assertFalse(query.contains("imdb_id="), "tmdb id is enough; the imdb id is not added beside it")
    }

    @Test
    fun public404IsEmptyAndDoesNotConfirmThisAccount() {
        assertEquals(PublicMediaStatus.NoSubmissions, classifyPublicMediaHttp(404, "no_submissions"))
        assertEquals(PublicMediaStatus.NoSubmissions, classifyPublicMediaHttp(200, "no_submissions"))
        assertEquals(PublicMediaStatus.Found, classifyPublicMediaHttp(200, null))
        assertEquals(PublicMediaStatus.Unavailable, classifyPublicMediaHttp(429, null))
        val rows = movieSegmentRows(
            media = null,
            publicStatus = PublicMediaStatus.NoSubmissions,
            confirmed = setOf("intro"),
            pending = setOf("recap"),
            detected = setOf("preview"),
        )
        assertEquals(MOVIE_SEGMENT_TYPES, rows.map { it.type })
        val intro = rows.first { it.type == "intro" }
        assertTrue(intro.ownConfirmed)
        assertFalse(intro.publicAvailable)
        assertNull(intro.publicCount)
        val recap = rows.first { it.type == "recap" }
        assertTrue(recap.ownPending)
        assertFalse(recap.ownConfirmed)
        val preview = rows.first { it.type == "preview" }
        assertTrue(preview.detected)
        assertFalse(preview.ownConfirmed)
        assertFalse(rows.first { it.type == "outro" }.ownConfirmed)
    }

    @Test
    fun publicCountIsSeparateFromAConfirmedCheck() {
        val media = TheIntroDbMediaResponse(
            type = "movie",
            intro = listOf(TheIntroDbSegment(startMs = 0L, endMs = 90_000L, submissionCount = 12)),
            recap = listOf(TheIntroDbSegment(startMs = 90_000L, endMs = 120_000L)),
            credits = listOf(TheIntroDbSegment(startMs = 7_000_000L, endMs = 7_400_000L, submissionCount = 4)),
            preview = emptyList(),
        )
        val rows = movieSegmentRows(
            media = media,
            publicStatus = PublicMediaStatus.Found,
            confirmed = emptySet(),
            pending = emptySet(),
            detected = setOf("outro"),
        )
        val intro = rows.first { it.type == "intro" }
        assertTrue(intro.publicAvailable)
        assertEquals(12, intro.publicCount)
        assertFalse(intro.ownConfirmed, "a public GET is not this account's check")
        val recap = rows.first { it.type == "recap" }
        assertTrue(recap.publicAvailable)
        assertNull(recap.publicCount, "a range without submission_count is not invented")
        val credits = rows.first { it.type == "outro" }
        assertEquals(4, credits.publicCount)
        assertTrue(credits.detected)
        assertFalse(credits.ownConfirmed)
        assertFalse(rows.first { it.type == "preview" }.publicAvailable)
    }

    @Test
    fun confirmedBeatsPendingAndDetectionForTheSameType() {
        val rows = movieSegmentRows(
            media = null,
            publicStatus = PublicMediaStatus.Unavailable,
            confirmed = setOf("credits"),
            pending = setOf("outro", "intro"),
            detected = setOf("intro", "preview"),
        )
        val credits = rows.first { it.type == "outro" }
        assertTrue(credits.ownConfirmed)
        assertFalse(credits.ownPending)
        val intro = rows.first { it.type == "intro" }
        assertTrue(intro.ownPending)
        assertFalse(intro.detected)
        assertFalse(intro.ownConfirmed)
    }

    @Test
    fun anEmptyCatalogueIsOneLineAndALocalMarkKeepsTheRows() {
        val empty = movieSegmentRows(
            media = null,
            publicStatus = PublicMediaStatus.NoSubmissions,
            confirmed = emptySet(),
            pending = emptySet(),
            detected = emptySet(),
        )
        assertEquals(MOVIE_SEGMENT_TYPES, empty.map { it.type })
        assertTrue(movieSegmentCatalogueIsEmpty(empty))
        val withIntro = movieSegmentRows(
            media = null,
            publicStatus = PublicMediaStatus.NoSubmissions,
            confirmed = setOf("intro"),
            pending = emptySet(),
            detected = emptySet(),
        )
        assertFalse(movieSegmentCatalogueIsEmpty(withIntro))
        assertFalse(
            movieSegmentCatalogueIsEmpty(
                movieSegmentRows(
                    media = null,
                    publicStatus = PublicMediaStatus.Unavailable,
                    confirmed = emptySet(),
                    pending = emptySet(),
                    detected = setOf("preview"),
                ),
            ),
        )
    }

    @Test
    fun fixtureRowsKeepAPublicCountOffTheConfirmedCheck() {
        val rows = movieSegmentFixtureRows()
        assertEquals(MOVIE_SEGMENT_TYPES, rows.map { it.type })
        val intro = rows.first { it.type == "intro" }
        assertEquals(12, intro.publicCount)
        assertFalse(intro.ownConfirmed)
        assertTrue(rows.first { it.type == "recap" }.ownConfirmed)
        assertTrue(rows.first { it.type == "outro" }.ownPending)
        assertFalse(rows.first { it.type == "outro" }.ownConfirmed)
        assertTrue(rows.first { it.type == "preview" }.detected)
        assertFalse(rows.first { it.type == "preview" }.ownConfirmed)
    }
}
