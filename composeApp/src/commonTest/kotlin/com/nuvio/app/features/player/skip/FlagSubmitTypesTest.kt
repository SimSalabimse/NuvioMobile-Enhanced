package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlagSubmitTypesTest {
    @Test
    fun canonicalTypesCoverSkipAliases() {
        assertEquals("intro", canonicalFlagSegmentType("mixed-op"))
        assertEquals("intro", canonicalFlagSegmentType("Opening"))
        assertEquals("recap", canonicalFlagSegmentType("recap"))
        assertEquals("outro", canonicalFlagSegmentType("credits"))
        assertEquals("outro", canonicalFlagSegmentType("movie-credits"))
        assertEquals("preview", canonicalFlagSegmentType("Preview"))
        assertEquals(null, canonicalFlagSegmentType("chapter"))
    }

    @Test
    fun contentKeyUsesImdbSeasonAndEpisode() {
        assertEquals("tt0944947:1:2", flagSubmitContentKey("TT0944947", 1, 2, isMovie = false))
        assertEquals("tt0944947:movie", flagSubmitContentKey("tt0944947", 0, 0, isMovie = true))
        assertEquals("", flagSubmitContentKey("kitsu:1", 1, 1, isMovie = false))
    }

    @Test
    fun ownAcceptedAndPendingTypesGrayOutAndRejectedStaysOpen() {
        val records = listOf(
            record(segment = "intro", status = "accepted"),
            record(segment = "credits", status = "pending"),
            record(segment = "preview", status = "rejected"),
            record(segment = "recap", status = "accepted", season = 2, episode = 1),
            record(tmdbId = 99, segment = "recap", status = "accepted"),
        )
        assertEquals(
            setOf("intro", "outro"),
            submittedFlagTypesForTitle(records, tmdbId = 42, isMovie = false, season = 1, episode = 3),
        )
    }

    @Test
    fun movieSubmissionsDoNotGrayATvEpisode() {
        val records = listOf(record(type = "movie", segment = "intro", status = "accepted", season = null, episode = null))
        assertTrue(submittedFlagTypesForTitle(records, tmdbId = 42, isMovie = false, season = 1, episode = 1).isEmpty())
        assertEquals(
            setOf("intro"),
            submittedFlagTypesForTitle(records, tmdbId = 42, isMovie = true, season = 0, episode = 0),
        )
    }

    @Test
    fun ledgerRoundTripsCanonicalTypes() {
        val encoded = encodeFlagSubmitLedger(
            mapOf(
                "tt1:1:2" to setOf("credits", "intro", "nope"),
                "tt9:movie" to setOf("preview"),
                "bad=key" to setOf("intro"),
            ),
        )
        assertEquals(
            mapOf(
                "tt1:1:2" to setOf("intro", "outro"),
                "tt9:movie" to setOf("preview"),
            ),
            decodeFlagSubmitLedger(encoded),
        )
        assertEquals(emptyMap(), decodeFlagSubmitLedger("   "))
    }

    private fun record(
        tmdbId: Int = 42,
        type: String = "tv",
        season: Int? = 1,
        episode: Int? = 3,
        segment: String,
        status: String,
    ) = FlagSubmissionRecord(tmdbId, type, season, episode, segment, status)
}
