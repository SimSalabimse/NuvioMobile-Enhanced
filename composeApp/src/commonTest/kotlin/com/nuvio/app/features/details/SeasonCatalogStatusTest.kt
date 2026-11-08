package com.nuvio.app.features.details

import com.nuvio.app.features.player.skip.FlagSubmissionRecord
import com.nuvio.app.features.player.skip.OwnAccountMarks
import com.nuvio.app.features.player.skip.PublicMediaStatus
import com.nuvio.app.features.player.skip.TheIntroDbMediaResponse
import com.nuvio.app.features.player.skip.TheIntroDbSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class SeasonCatalogStatusTest {
    private val show = "tt9000101"
    private val undiscovered = request(episode = 4)
    private val played = request(episode = 13)

    @Test
    fun catalogueMinutesBecomeMillisecondsAndAnUnknownRuntimeIsOmitted() {
        assertEquals(2_520_000L, catalogueDurationMs(42))
        assertEquals(null, catalogueDurationMs(null))
        assertEquals(null, catalogueDurationMs(0))
    }

    @Test
    fun publicOwnBothNoneAndErrorStayDistinctAndPublicIsNotOwn() = runBlocking {
        val cache = SeasonPublicCache()
        var calls = 0
        val responses = mutableMapOf<Int, Pair<PublicMediaStatus, TheIntroDbMediaResponse?>>(
            4 to (PublicMediaStatus.Found to media(intro = segment(0, 90_000))),
            13 to (PublicMediaStatus.NoSubmissions to null),
            14 to (PublicMediaStatus.NoSubmissions to null),
            15 to (PublicMediaStatus.Found to media(intro = segment(0, 80_000), recap = segment(0, 40_000))),
        )
        val listing = OwnListing(
            failed = false,
            tmdbId = 77,
            records = listOf(
                record(season = 3, episode = 13, segment = "recap", status = "pending"),
                record(season = 3, episode = 15, segment = "intro", status = "approved"),
            ),
        )
        val requests = listOf(undiscovered, played, request(14), request(15))
        val (merged, other) = mergeOwnListing(
            marks = OwnAccountMarks(),
            other = OwnAccountMarks(confirmed = mapOf("tt9000999:1:1" to setOf("intro"))),
            requests = requests,
            listing = listing,
        )
        assertEquals(setOf("intro"), other.confirmed["tt9000999:1:1"])
        val facts = loadSeasonCatalog(
            requests = requests,
            ownListing = listing,
            merged = merged,
            publicFetch = { request ->
                calls += 1
                responses.getValue(request.episode)
            },
            cache = cache,
            nowMs = 1_000L,
        )
        val byEpisode = facts.associateBy { it.contentKey.substringAfterLast(':').toInt() }
        val publicOnly = byEpisode.getValue(4)
        assertEquals(CatalogRead.Listed, publicOnly.publicRead)
        assertEquals(setOf("intro"), publicOnly.publicTypes)
        assertEquals(CatalogRead.None, publicOnly.ownRead)
        assertTrue(publicOnly.ownNotSubmitted)
        assertTrue(publicOnly.ownConfirmed.isEmpty())

        val ownOnly = byEpisode.getValue(13)
        assertEquals(CatalogRead.None, ownOnly.publicRead)
        assertTrue(ownOnly.publicTypes.isEmpty())
        assertEquals(setOf("recap"), ownOnly.ownPending)
        assertTrue("recap" !in ownOnly.ownConfirmed)

        val neither = byEpisode.getValue(14)
        assertEquals(CatalogRead.None, neither.publicRead)
        assertEquals(CatalogRead.None, neither.ownRead)

        val both = byEpisode.getValue(15)
        assertEquals(setOf("intro", "recap"), both.publicTypes)
        assertEquals(setOf("intro"), both.ownConfirmed)
        assertTrue(both.publicTypes.subtract(both.ownConfirmed).contains("recap"))
        assertEquals(4, calls)

        val again = loadSeasonCatalog(
            requests = requests,
            ownListing = listing,
            merged = merged,
            publicFetch = {
                calls += 1
                PublicMediaStatus.Unavailable to null
            },
            cache = cache,
            nowMs = 2_000L,
        )
        assertEquals(facts, again)
        assertEquals(4, calls)
    }

    @Test
    fun anUnavailableReadStopsTheRestAndABlankIdIsNotARequest() = runBlocking {
        val cache = SeasonPublicCache()
        var calls = 0
        val facts = loadSeasonCatalog(
            requests = listOf(request(1), request(2), request(3, imdb = "")),
            ownListing = OwnListing(failed = true),
            merged = OwnAccountMarks(),
            publicFetch = {
                calls += 1
                PublicMediaStatus.Unavailable to null
            },
            cache = cache,
            nowMs = 5_000L,
        )
        assertEquals(1, calls)
        assertEquals(CatalogRead.Unavailable, facts[0].publicRead)
        assertEquals(CatalogRead.Unavailable, facts[1].publicRead)
        assertEquals(CatalogRead.NotChecked, facts[2].publicRead)
        assertEquals(CatalogRead.Unavailable, facts[0].ownRead)
        val blocked = loadSeasonCatalog(
            requests = listOf(request(9)),
            ownListing = null,
            merged = OwnAccountMarks(),
            publicFetch = {
                calls += 1
                PublicMediaStatus.Found to media(intro = segment(0, 10_000))
            },
            cache = cache,
            nowMs = 5_000L + 1_000L,
        )
        assertEquals(1, calls)
        assertEquals(CatalogRead.Unavailable, blocked.single().publicRead)
        assertEquals(CatalogRead.NotChecked, blocked.single().ownRead)
    }

    @Test
    fun cancellationDoesNotReadTheNextEpisode() {
        assertFailsWith<CancellationException> {
            runBlocking {
                loadSeasonCatalog(
                    requests = listOf(request(1), request(2)),
                    ownListing = null,
                    merged = OwnAccountMarks(),
                    publicFetch = { throw CancellationException() },
                    cache = SeasonPublicCache(),
                    nowMs = 0L,
                )
            }
        }
    }

    @Test
    fun deletingTheFileDoesNotChangePublicOrOwnFacts() = runBlocking {
        val request = request(8)
        val listing = OwnListing(
            failed = false,
            tmdbId = 77,
            records = listOf(record(3, 8, "outro", "confirmed")),
        )
        val (merged, _) = mergeOwnListing(OwnAccountMarks(), OwnAccountMarks(), listOf(request), listing)
        val withFile = loadSeasonCatalog(
            requests = listOf(request),
            ownListing = listing,
            merged = merged,
            publicFetch = { PublicMediaStatus.Found to media(intro = segment(1_000, 20_000)) },
            cache = SeasonPublicCache(),
            nowMs = 0L,
        ).single()
        val afterDelete = factsForEpisode(
            contentKey = request.contentKey,
            publicStatus = PublicMediaStatus.Found,
            media = media(intro = segment(1_000, 20_000)),
            ownListed = true,
            ownFailed = false,
            merged = merged,
        )
        assertEquals(withFile.publicTypes, afterDelete.publicTypes)
        assertEquals(withFile.ownConfirmed, afterDelete.ownConfirmed)
        assertEquals(setOf("outro"), afterDelete.ownConfirmed)
        assertTrue("intro" !in afterDelete.ownConfirmed)
    }

    private fun request(episode: Int, imdb: String = show) = SeasonStatusRequest(
        imdbId = imdb,
        season = 3,
        episode = episode,
        durationMs = catalogueDurationMs(42),
        contentKey = if (imdb.startsWith("tt")) "$imdb:3:$episode" else "",
    )

    private fun segment(start: Long, end: Long) = TheIntroDbSegment(startMs = start, endMs = end, submissionCount = 2)

    private fun media(
        intro: TheIntroDbSegment? = null,
        recap: TheIntroDbSegment? = null,
    ) = TheIntroDbMediaResponse(
        intro = intro?.let { listOf(it) },
        recap = recap?.let { listOf(it) },
    )

    private fun record(season: Int, episode: Int, segment: String, status: String) = FlagSubmissionRecord(
        tmdbId = 77,
        type = "tv",
        season = season,
        episode = episode,
        segment = segment,
        status = status,
    )
}
