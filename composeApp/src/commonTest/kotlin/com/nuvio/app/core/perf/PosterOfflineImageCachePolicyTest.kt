package com.nuvio.app.core.perf

import com.nuvio.app.core.network.NetworkCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Path
import okio.Path.Companion.toPath

class PosterOfflineImageCachePolicyTest {
    @Test
    fun stateTable_cachedBytesAndNetworkRequest() {
        val table = listOf(
            Row(PosterImageCacheFreshness.Fresh, PosterImageNetworkState.Online, useCachedBytes = true, makeNetworkRequest = false),
            Row(PosterImageCacheFreshness.Fresh, PosterImageNetworkState.Offline, useCachedBytes = true, makeNetworkRequest = false),
            Row(PosterImageCacheFreshness.Fresh, PosterImageNetworkState.RequestFailed, useCachedBytes = true, makeNetworkRequest = false),
            Row(PosterImageCacheFreshness.Expired, PosterImageNetworkState.Online, useCachedBytes = false, makeNetworkRequest = true),
            Row(PosterImageCacheFreshness.Expired, PosterImageNetworkState.Offline, useCachedBytes = true, makeNetworkRequest = false),
            Row(PosterImageCacheFreshness.Expired, PosterImageNetworkState.RequestFailed, useCachedBytes = true, makeNetworkRequest = false),
            Row(PosterImageCacheFreshness.NoCache, PosterImageNetworkState.Online, useCachedBytes = false, makeNetworkRequest = true),
            Row(PosterImageCacheFreshness.NoCache, PosterImageNetworkState.Offline, useCachedBytes = false, makeNetworkRequest = false),
            Row(PosterImageCacheFreshness.NoCache, PosterImageNetworkState.RequestFailed, useCachedBytes = false, makeNetworkRequest = false),
        )

        assertEquals(9, table.size)
        table.forEach { row ->
            val decision = PosterOfflineImageCachePolicy.decide(row.cache, row.network)
            assertEquals(row.useCachedBytes, decision.useCachedBytes, row.label)
            assertEquals(row.makeNetworkRequest, decision.makeNetworkRequest, row.label)
            assertEquals(!row.useCachedBytes && !row.makeNetworkRequest, decision.showExistingPlaceholder, row.label)
        }
    }

    @Test
    fun cacheControlExpiryIsIgnoredOnlyWhenOfflineOrTheRequestFailed() {
        val now = 1_000L
        assertEquals(now, PosterOfflineImageCachePolicy.freshUntilMillis(null, now))
        assertEquals(now, PosterOfflineImageCachePolicy.freshUntilMillis("no-store", now))
        assertEquals(now, PosterOfflineImageCachePolicy.freshUntilMillis("public, no-cache", now))
        assertEquals(now, PosterOfflineImageCachePolicy.freshUntilMillis("max-age=0", now))
        assertEquals(61_000L, PosterOfflineImageCachePolicy.freshUntilMillis("public, max-age=60", now))

        val expired = PosterOfflineImageCachePolicy.freshness(now, now)
        val fresh = PosterOfflineImageCachePolicy.freshness(61_000L, now)
        assertEquals(PosterImageCacheFreshness.Expired, expired)
        assertEquals(PosterImageCacheFreshness.Fresh, fresh)

        val offlineExpired = PosterOfflineImageCachePolicy.decide(expired, PosterImageNetworkState.Offline)
        assertTrue(offlineExpired.useCachedBytes)
        assertFalse(offlineExpired.makeNetworkRequest)

        val onlineExpired = PosterOfflineImageCachePolicy.decide(expired, PosterImageNetworkState.Online)
        assertFalse(onlineExpired.useCachedBytes)
        assertTrue(onlineExpired.makeNetworkRequest)

        val onlineFresh = PosterOfflineImageCachePolicy.decide(fresh, PosterImageNetworkState.Online)
        assertTrue(onlineFresh.useCachedBytes)
        assertFalse(onlineFresh.makeNetworkRequest)
    }

    @Test
    fun networkConditionMapsOnlyNoInternetToOffline() {
        assertEquals(
            PosterImageNetworkState.Offline,
            PosterOfflineImageCachePolicy.fromCondition(NetworkCondition.NoInternet),
        )
        assertEquals(
            PosterImageNetworkState.Online,
            PosterOfflineImageCachePolicy.fromCondition(NetworkCondition.Online),
        )
        assertEquals(
            PosterImageNetworkState.Online,
            PosterOfflineImageCachePolicy.fromCondition(NetworkCondition.Unknown),
        )
        assertEquals(
            PosterImageNetworkState.Online,
            PosterOfflineImageCachePolicy.fromCondition(NetworkCondition.Checking),
        )
        assertEquals(
            PosterImageNetworkState.Online,
            PosterOfflineImageCachePolicy.fromCondition(NetworkCondition.ServersUnreachable),
        )
    }

    @Test
    fun evictsTheLeastRecentlyUsedEntryAtTheCap() = runBlocking {
        assertEquals(250L * 1024L * 1024L, PosterOfflineImageCachePolicy.MaxBytes)
        val blobs = MemoryPosterOfflineImageBlobStore()
        val cache = PosterOfflineImageDiskCache(blobs, maxBytes = 100)
        val payload = ByteArray(40) { 7 }

        assertTrue(cache.store("a", payload, freshUntilMillis = 10_000, nowMillis = 1))
        assertTrue(cache.store("b", payload, freshUntilMillis = 10_000, nowMillis = 2))
        assertTrue(cache.open("a", nowMillis = 3) != null)
        assertTrue(cache.store("c", payload, freshUntilMillis = 10_000, nowMillis = 4))

        assertTrue(cache.contains("a"))
        assertFalse(cache.contains("b"))
        assertTrue(cache.contains("c"))
        assertEquals(80, blobs.retainedBytes())
    }

    @Test
    fun corruptEntryIsIgnored() = runBlocking {
        val blobs = MemoryPosterOfflineImageBlobStore()
        val cache = PosterOfflineImageDiskCache(blobs, maxBytes = 1_000)
        assertTrue(cache.store("poster", ByteArray(40) { 3 }, freshUntilMillis = 5_000, nowMillis = 1))
        blobs.damage(posterOfflineImageFileKey("poster"))

        assertNull(cache.open("poster", nowMillis = 2))
        assertFalse(cache.contains("poster"))
        assertFalse(cache.store("tiny", ByteArray(4), freshUntilMillis = 5_000, nowMillis = 3))
        assertNull(cache.open("tiny", nowMillis = 4))

        val valid = PosterOfflineImageIndexRow(
            key = "ab".repeat(32),
            sizeBytes = 40,
            freshUntilMillis = 9,
            lastAccessMillis = 1,
            contentType = "image/jpeg",
        )
        val parsed = PosterOfflineImageCachePolicy.parseIndex(
            PosterOfflineImageCachePolicy.indexText(listOf(valid)) + "\ncorrupt\nshort\t1\t2\n",
        )
        assertEquals(listOf(valid), parsed)
    }

    @Test
    fun htmlResponsesAreNotCachedImages() {
        assertTrue(PosterOfflineImageCachePolicy.acceptsResponseContentType(null))
        assertTrue(PosterOfflineImageCachePolicy.acceptsResponseContentType("image/jpeg; charset=binary"))
        assertFalse(PosterOfflineImageCachePolicy.acceptsResponseContentType("text/html"))
    }

    private data class Row(
        val cache: PosterImageCacheFreshness,
        val network: PosterImageNetworkState,
        val useCachedBytes: Boolean,
        val makeNetworkRequest: Boolean,
    ) {
        val label: String = "$cache x $network"
    }
}

private class MemoryPosterOfflineImageBlobStore : PosterOfflineImageBlobStore {
    private val blobs = mutableMapOf<String, ByteArray>()
    private val damaged = mutableSetOf<String>()
    private var index = ""

    fun damage(key: String) {
        damaged += key
    }

    fun retainedBytes(): Int = blobs.values.sumOf { it.size }

    override fun loadIndex(): List<PosterOfflineImageIndexRow> =
        PosterOfflineImageCachePolicy.parseIndex(index)

    override fun writeIndex(rows: Collection<PosterOfflineImageIndexRow>) {
        index = PosterOfflineImageCachePolicy.indexText(rows)
    }

    override fun blobSize(key: String): Long? {
        if (key in damaged) return null
        return blobs[key]?.size?.toLong()
    }

    override fun blobPath(key: String): Path? {
        if (key in damaged || key !in blobs) return null
        return "/mem/$key.bin".toPath()
    }

    override fun writeBlob(key: String, bytes: ByteArray) {
        damaged.remove(key)
        blobs[key] = bytes.copyOf()
    }

    override fun deleteBlob(key: String) {
        blobs.remove(key)
        damaged.remove(key)
    }

    override fun deleteUnknownBlobs(knownKeys: Set<String>) {
        blobs.keys.toList().forEach { key ->
            if (key !in knownKeys) blobs.remove(key)
        }
    }
}
