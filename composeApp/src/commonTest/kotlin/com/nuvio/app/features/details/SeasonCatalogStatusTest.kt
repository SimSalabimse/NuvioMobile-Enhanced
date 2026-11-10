package com.nuvio.app.features.details

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.player.skip.FlagSubmissionRecord
import com.nuvio.app.features.player.skip.OwnAccountMarks
import com.nuvio.app.features.player.skip.PublicMediaStatus
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.TheIntroDbMediaResponse
import com.nuvio.app.features.player.skip.TheIntroDbSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
                val (status, body) = responses.getValue(request.episode)
                PublicMediaFetch(status, body)
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
                PublicMediaFetch(PublicMediaStatus.Unavailable)
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
                PublicMediaFetch(PublicMediaStatus.Unavailable)
            },
            cache = cache,
            nowMs = 5_000L,
        )
        assertEquals(1, calls)
        assertEquals(CatalogRead.Unavailable, facts[0].publicRead)
        assertEquals(CatalogRead.NotChecked, facts[1].publicRead)
        assertEquals(CatalogRead.Unavailable, facts[1].ownRead)
        assertEquals(CatalogRead.NotChecked, facts[2].publicRead)
        assertEquals(CatalogRead.Unavailable, facts[0].ownRead)
        val blocked = loadSeasonCatalog(
            requests = listOf(request(9)),
            ownListing = null,
            merged = OwnAccountMarks(),
            publicFetch = {
                calls += 1
                PublicMediaFetch(PublicMediaStatus.Found, media(intro = segment(0, 10_000)))
            },
            cache = cache,
            nowMs = 5_000L + 1_000L,
        )
        assertEquals(1, calls)
        assertEquals(CatalogRead.NotChecked, blocked.single().publicRead)
        assertEquals(CatalogRead.NotChecked, blocked.single().ownRead)
    }

    @Test
    fun aRateLimitKeepsTheServerDeadlineAndAGenericFailureDoesNot() = runBlocking {
        val now = 1_700_000_000_000L
        val cache = SeasonPublicCache()
        var calls = 0
        val facts = loadSeasonCatalog(
            requests = listOf(request(13), request(14)),
            ownListing = null,
            merged = OwnAccountMarks(),
            publicFetch = {
                calls += 1
                val body = TheIntroDb.getMedia(
                    imdbId = show,
                    season = 3,
                    episode = 13,
                    nowEpochMs = now,
                    transport = { http(429, mapOf("Retry-After" to "600")) },
                )
                publicFetchFromLastGet(body)
            },
            cache = cache,
            nowMs = now,
        )
        assertEquals(1, calls)
        assertEquals(1_700_000_600_000L, cache.blockedUntilMs)
        assertEquals(CatalogRead.Unavailable, facts[0].publicRead)
        assertEquals(CatalogRead.NotChecked, facts[1].publicRead)
        assertEquals(CatalogRead.NotChecked, facts[0].ownRead)
        val again = loadSeasonCatalog(
            requests = listOf(request(13), request(14)),
            ownListing = null,
            merged = OwnAccountMarks(),
            publicFetch = {
                calls += 1
                PublicMediaFetch(PublicMediaStatus.Found, media(intro = segment(0, 10_000)))
            },
            cache = cache,
            nowMs = now + 599_000L,
        )
        assertEquals(1, calls)
        assertEquals(CatalogRead.Unavailable, again[0].publicRead)
        assertEquals(CatalogRead.NotChecked, again[1].publicRead)
        val dated = TheIntroDb.getMedia(
            imdbId = show,
            season = 3,
            episode = 13,
            nowEpochMs = 1_445_412_000_000L,
            transport = { http(429, mapOf("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT")) },
        )
        assertNull(dated)
        assertEquals(1_445_412_480_000L, publicFetchFromLastGet(dated).retryUntilMs)
        val fallback = TheIntroDb.getMedia(
            imdbId = show,
            season = 3,
            episode = 13,
            nowEpochMs = now,
            transport = { http(429, emptyMap()) },
        )
        assertEquals(now + 60_000L, publicFetchFromLastGet(fallback).retryUntilMs)
        val generic = TheIntroDb.getMedia(
            imdbId = show,
            season = 3,
            episode = 13,
            nowEpochMs = now,
            transport = { http(500, mapOf("Retry-After" to "600")) },
        )
        assertNull(publicFetchFromLastGet(generic).retryUntilMs)
        assertEquals(PublicMediaStatus.Unavailable, publicFetchFromLastGet(generic).status)
        val genericCache = SeasonPublicCache()
        loadSeasonCatalog(
            requests = listOf(request(13), request(14)),
            ownListing = null,
            merged = OwnAccountMarks(),
            publicFetch = { publicFetchFromLastGet(generic) },
            cache = genericCache,
            nowMs = now,
        )
        assertEquals(now + SEASON_PUBLIC_BACKOFF_MS, genericCache.blockedUntilMs)
        assertFailsWith<CancellationException> {
            TheIntroDb.getMedia(
                imdbId = show,
                season = 3,
                episode = 13,
                nowEpochMs = now,
                transport = { throw CancellationException() },
            )
        }
        assertNull(TheIntroDb.lastPublicRetryUntilMs)
        val afterDeadline = loadSeasonCatalog(
            requests = listOf(request(14)),
            ownListing = null,
            merged = OwnAccountMarks(),
            publicFetch = {
                calls += 1
                PublicMediaFetch(PublicMediaStatus.NoSubmissions)
            },
            cache = cache,
            nowMs = now + 600_000L,
        )
        assertEquals(2, calls)
        assertEquals(CatalogRead.None, afterDeadline.single().publicRead)
    }

    @Test
    fun anAccountSwitchReloadsThatAccountsListing() = runBlocking {
        SeasonOwnCache.clearForTest()
        val first = OwnListing(
            failed = false,
            tmdbId = 77,
            records = listOf(record(3, 13, "recap", "pending")),
        )
        val second = OwnListing(failed = false, tmdbId = 77, records = emptyList())
        SeasonOwnCache.put("account-a", first)
        SeasonOwnCache.put("account-b", second)
        val requests = listOf(request(13))
        val fromFirst = factsForListing(SeasonOwnCache.get("account-a"), requests)
        val fromSecond = factsForListing(SeasonOwnCache.get("account-b"), requests)
        assertEquals(setOf("recap"), fromFirst.single().ownPending)
        assertEquals(CatalogRead.None, fromSecond.single().ownRead)
        assertTrue(fromSecond.single().ownPending.isEmpty())
        val keyA = seasonCatalogReloadKey(show, show, false, "account-a", requests.map { it.contentKey })
        val keyB = seasonCatalogReloadKey(show, show, false, "account-b", requests.map { it.contentKey })
        assertNotEquals(keyA, keyB)
        assertEquals(keyA, seasonCatalogReloadKey(show, show, false, "account-a", requests.map { it.contentKey }))
        SeasonOwnCache.clearForTest()
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
            publicFetch = { PublicMediaFetch(PublicMediaStatus.Found, media(intro = segment(1_000, 20_000))) },
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
        val storedOnly = factsForEpisode(
            contentKey = request.contentKey,
            publicStatus = null,
            media = null,
            ownListed = false,
            ownFailed = false,
            merged = merged,
        )
        assertEquals(CatalogRead.Listed, storedOnly.ownRead)
        assertEquals(CatalogRead.NotChecked, storedOnly.publicRead)
    }

    private suspend fun factsForListing(
        listing: OwnListing?,
        requests: List<SeasonStatusRequest>,
    ): List<EpisodeCatalogFacts> {
        val merged = if (listing == null) {
            OwnAccountMarks()
        } else {
            mergeOwnListing(OwnAccountMarks(), OwnAccountMarks(), requests, listing).first
        }
        return loadSeasonCatalog(
            requests = requests,
            ownListing = listing,
            merged = merged,
            publicFetch = { PublicMediaFetch(PublicMediaStatus.NoSubmissions) },
            cache = SeasonPublicCache(),
            nowMs = 0L,
        )
    }

    private fun http(status: Int, headers: Map<String, String>) = RawHttpResponse(
        status = status,
        statusText = "",
        url = "",
        body = "",
        headers = headers,
    )

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
