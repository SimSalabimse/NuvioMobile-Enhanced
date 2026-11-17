package com.nuvio.app.features.details

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.perf.PlaybackQuietMode
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.capContinueWatchingItems
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineDetailsCachePolicyTest {
    @Test
    fun `state table shows cache and requests refresh`() {
        val profiles = listOf("profile-a", "profile-b")
        for (requestProfile in profiles) {
            for (lookup in OfflineCacheLookup.entries) {
                for (network in OfflineCacheNetwork.entries) {
                    val gated = lookupForProfile(
                        requestProfile = requestProfile,
                        storedProfile = "profile-a",
                        lookup = lookup,
                    )
                    val decision = offlineCacheDecision(gated, network)
                    val visibleLookup = if (requestProfile == "profile-a" || lookup == OfflineCacheLookup.Miss) {
                        lookup
                    } else {
                        OfflineCacheLookup.Miss
                    }
                    assertEquals(visibleLookup, gated, "$requestProfile $lookup $network")
                    assertEquals(showsCached(visibleLookup), decision.showCached, "$requestProfile $lookup $network")
                    assertEquals(
                        requestsRefresh(visibleLookup, network),
                        decision.refreshRequested,
                        "$requestProfile $lookup $network",
                    )
                    if (requestProfile == "profile-b" && lookup != OfflineCacheLookup.Miss) {
                        assertFalse(decision.showCached)
                    }
                }
            }
        }
    }

    @Test
    fun `profile b never reads profile a entries`() {
        val storage = MemoryStorage()
        val now = 10_000L
        val original = newCache(storage = storage, now = now)
        original.upsert("profile-a", "tt1", "from-a", savedAtMs = now)
        original.upsert("profile-b", "tt9", "from-b", savedAtMs = now)

        assertEquals("from-b", original.read("profile-b", "tt9")?.payload)
        assertNull(original.read("profile-b", "tt1"))
        assertEquals("from-a", original.read("profile-a", "tt1")?.payload)

        storage.files[original.pathFor("profile-b")] = storage.files.getValue(original.pathFor("profile-a"))
        val reloaded = newCache(storage = storage, now = now)
        assertNull(reloaded.read("profile-b", "tt1"))
        assertFalse(reloaded.keys("profile-b").contains("tt1"))
        assertEquals(listOf("tt1"), reloaded.keys("profile-a"))
    }

    @Test
    fun `least recently used entries and bytes are evicted at the cap`() {
        val storage = MemoryStorage()
        val entryCache = newCache(storage = storage, maxEntries = 2, maxBytes = 100, now = 1L)
        entryCache.upsert("profile-a", "a", "one", savedAtMs = 1L)
        entryCache.upsert("profile-a", "b", "two", savedAtMs = 1L)
        entryCache.read("profile-a", "a")
        entryCache.upsert("profile-a", "c", "three", savedAtMs = 1L)

        assertEquals(listOf("a", "c"), entryCache.keys("profile-a"))
        assertNull(entryCache.read("profile-a", "b"))

        val byteCache = newCache(storage = MemoryStorage(), maxEntries = 10, maxBytes = 10, now = 1L)
        byteCache.upsert("profile-a", "old", "123456", savedAtMs = 1L)
        byteCache.upsert("profile-a", "new", "abcdef", savedAtMs = 1L)

        assertEquals(listOf("new"), byteCache.keys("profile-a"))
        assertNull(byteCache.read("profile-a", "old"))
    }

    @Test
    fun `schema mismatch and corrupt files are ignored`() {
        val storage = MemoryStorage()
        val seeded = newCache(storage = storage, now = 1L)
        val path = seeded.pathFor("profile-a")
        storage.files[path] = """
            {"schemaVersion":99,"profileKey":"profile-a","entries":[{"key":"tt1","savedAtMs":1,"touchedAtMs":1,"payload":"stale-schema"}]}
        """.trimIndent()
        val mismatched = newCache(storage = storage, now = 1L)
        assertNull(mismatched.read("profile-a", "tt1"))
        assertTrue(mismatched.keys("profile-a").isEmpty())

        storage.files[path] = "{not-json"
        val corrupt = newCache(storage = storage, now = 1L)
        assertNull(corrupt.read("profile-a", "tt1"))
        assertTrue(corrupt.keys("profile-a").isEmpty())
    }

    @Test
    fun `stale offline still shows and online or timeout refetches`() {
        var now = 5_000L
        val storage = MemoryStorage()
        val cache = newCache(storage = storage, ttlMs = 1_000L, nowProvider = { now })
        cache.upsert("profile-a", "tt1", "opened", savedAtMs = 5_000L)
        now = 5_000L + 1_000L + 1L
        val hit = cache.read("profile-a", "tt1")
        assertEquals("opened", hit?.payload)
        assertEquals(false, hit?.fresh)

        val offline = offlineCacheDecision(OfflineCacheLookup.DiskStale, OfflineCacheNetwork.Offline)
        assertTrue(offline.showCached)
        assertFalse(offline.refreshRequested)

        val online = offlineCacheDecision(OfflineCacheLookup.DiskStale, OfflineCacheNetwork.Online)
        assertTrue(online.showCached)
        assertTrue(online.refreshRequested)

        val timeout = offlineCacheDecision(OfflineCacheLookup.DiskStale, OfflineCacheNetwork.Timeout)
        assertTrue(timeout.showCached)
        assertTrue(timeout.refreshRequested)

        val fresh = offlineCacheDecision(OfflineCacheLookup.DiskFresh, OfflineCacheNetwork.Online)
        assertTrue(fresh.showCached)
        assertFalse(fresh.refreshRequested)

        val memory = offlineCacheDecision(OfflineCacheLookup.MemoryHit, OfflineCacheNetwork.Online)
        assertTrue(memory.showCached)
        assertFalse(memory.refreshRequested)

        val miss = offlineCacheDecision(OfflineCacheLookup.Miss, OfflineCacheNetwork.Timeout)
        assertFalse(miss.showCached)
        assertTrue(miss.refreshRequested)
    }

    @Test
    fun `writes wait until the player is closed and lists are still`() {
        val storage = MemoryStorage()
        var allowed = false
        val cache = newCache(storage = storage, writeGate = { allowed })
        cache.upsert("profile-a", "tt1", "held", savedAtMs = 1L)
        assertNull(storage.files[cache.pathFor("profile-a")])
        allowed = true
        cache.flushForTests()
        assertTrue(storage.files.getValue(cache.pathFor("profile-a")).contains("held"))

        try {
            PlaybackQuietMode.playerOpened()
            assertFalse(OfflineDetailsWriteGate.writesAllowed())
        } finally {
            PlaybackQuietMode.playerClosed()
        }
        assertTrue(OfflineDetailsWriteGate.writesAllowed())
        try {
            OfflineDetailsWriteGate.bindListScrolling("home", true)
            assertFalse(OfflineDetailsWriteGate.writesAllowed())
            OfflineDetailsWriteGate.bindListScrolling("home", false)
            assertTrue(OfflineDetailsWriteGate.writesAllowed())
        } finally {
            OfflineDetailsWriteGate.clearForTests()
        }
    }

    @Test
    fun `stored title keeps render fields and drops stream and token urls`() {
        val meta = MetaDetails(
            id = "tt1",
            type = "series",
            name = "Example",
            poster = "https://images.example/poster.jpg",
            website = "https://addon.example/manifest.json?token=secret",
            links = listOf(MetaLink(name = "Addon", category = "addon", url = "https://addon.example/key/manifest.json")),
            videos = listOf(
                MetaVideo(
                    id = "tt1:1:1",
                    title = "Episode 1",
                    season = 1,
                    episode = 1,
                    overview = "Cold open",
                    thumbnail = "https://images.example/ep.jpg?token=secret",
                    streams = listOf(
                        StreamItem(
                            url = "https://play.example/file.mp4?api_key=secret",
                            addonName = "Addon",
                            addonId = "addon",
                        ),
                    ),
                ),
            ),
        )
        val stored = meta.toStoredRenderMeta()
        val encoded = Json.encodeToString(StoredMetaDetails.serializer(), stored)
        assertEquals("https://images.example/poster.jpg", stored.poster)
        assertEquals("Episode 1", stored.videos.single().title)
        assertEquals("Cold open", stored.videos.single().overview)
        assertNull(stored.videos.single().thumbnail)
        assertFalse(encoded.contains("api_key"))
        assertFalse(encoded.contains("token"))
        assertFalse(encoded.contains("file.mp4"))
        assertFalse(encoded.contains("manifest.json"))
        assertEquals(1, stored.toMetaDetails().videos.single().episode)
        assertTrue(stored.toMetaDetails().videos.single().streams.isEmpty())
    }

    @Test
    fun `continue watching rejects another profile a bad schema and a corrupt file`() {
        val raw = """
            {"schemaVersion":1,"savedAtMs":5,"profileId":1,"nextUp":[{"contentId":"a","contentType":"series","name":"Only A","videoId":"v","lastWatched":1,"sortTimestamp":1}],"inProgress":[]}
        """.trimIndent()
        assertEquals("Only A", ContinueWatchingEnrichmentCache.storedEnrichmentName(raw, profileId = 1))
        assertNull(ContinueWatchingEnrichmentCache.storedEnrichmentName(raw, profileId = 2))
        assertNull(ContinueWatchingEnrichmentCache.storedEnrichmentName("{", profileId = 1))
        assertNull(
            ContinueWatchingEnrichmentCache.storedEnrichmentName(
                raw.replace("\"schemaVersion\":1", "\"schemaVersion\":9"),
                profileId = 1,
            ),
        )
        val nextUp = (1..61).map { index ->
            CachedNextUpItem(
                contentId = "id$index",
                contentType = "series",
                name = "n$index",
                videoId = "v$index",
                lastWatched = index.toLong(),
                sortTimestamp = index.toLong(),
            )
        }
        val capped = capContinueWatchingItems(nextUp, emptyList())
        assertEquals(OfflineDetailsCacheLimits.ContinueWatchingMaxItems, capped.first.size)
        assertEquals("n61", capped.first.first().name)
    }

    @Test
    fun `documented caps and network mapping stay stable`() {
        assertEquals(150, OfflineDetailsCacheLimits.MetaMaxEntries)
        assertEquals(8 * 1024 * 1024, OfflineDetailsCacheLimits.MetaMaxBytes)
        assertEquals(12L * 60 * 60 * 1000, OfflineDetailsCacheLimits.MetaTtlMs)
        assertEquals(400, OfflineDetailsCacheLimits.SeasonListMaxEntries)
        assertEquals(2 * 1024 * 1024, OfflineDetailsCacheLimits.SeasonListMaxBytes)
        assertEquals(12L * 60 * 60 * 1000, OfflineDetailsCacheLimits.SeasonListTtlMs)
        assertEquals(400, OfflineDetailsCacheLimits.SeasonRatingsMaxEntries)
        assertEquals(7L * 24 * 60 * 60 * 1000, OfflineDetailsCacheLimits.SeasonRatingsTtlMs)
        assertEquals(30L * 60 * 1000, OfflineDetailsCacheLimits.ContinueWatchingTtlMs)
        assertEquals(60, OfflineDetailsCacheLimits.ContinueWatchingMaxItems)
        assertEquals(1, OfflineDetailsCacheLimits.SchemaVersion)
        assertEquals(OfflineCacheNetwork.Offline, offlineNetwork(NetworkCondition.NoInternet))
        assertEquals(OfflineCacheNetwork.Offline, offlineNetwork(NetworkCondition.ServersUnreachable))
        assertEquals(OfflineCacheNetwork.Online, offlineNetwork(NetworkCondition.Online))
        assertEquals(OfflineCacheNetwork.Online, offlineNetwork(NetworkCondition.Unknown))
        assertEquals(OfflineCacheNetwork.Online, offlineNetwork(NetworkCondition.Checking))
    }

    private fun showsCached(lookup: OfflineCacheLookup): Boolean = lookup != OfflineCacheLookup.Miss

    private fun requestsRefresh(lookup: OfflineCacheLookup, network: OfflineCacheNetwork): Boolean = when (lookup) {
        OfflineCacheLookup.MemoryHit,
        OfflineCacheLookup.DiskFresh,
        -> false
        OfflineCacheLookup.DiskStale,
        OfflineCacheLookup.Miss,
        -> network != OfflineCacheNetwork.Offline
    }

    private fun newCache(
        storage: MemoryStorage,
        maxEntries: Int = 4,
        maxBytes: Int = 1_000,
        ttlMs: Long = 1_000L,
        now: Long = 1L,
        nowProvider: () -> Long = { now },
        writeGate: () -> Boolean = { true },
    ) = ProfileOfflineDiskCache(
        namespace = "test",
        schemaVersion = OfflineDetailsCacheLimits.SchemaVersion,
        maxEntries = maxEntries,
        maxBytes = maxBytes,
        ttlMs = ttlMs,
        storage = storage,
        clock = nowProvider,
        writeGate = writeGate,
        writesImmediate = true,
    )

    private class MemoryStorage : OfflineDiskStorage {
        val files = mutableMapOf<String, String>()

        override fun read(path: String): String? = files[path]

        override fun write(path: String, payload: String) {
            files[path] = payload
        }
    }
}
