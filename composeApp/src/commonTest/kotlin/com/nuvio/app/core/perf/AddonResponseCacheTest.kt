package com.nuvio.app.core.perf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonResponseCacheTest {
    private class Harness(scope: TestScope, dedupe: Boolean = true) {
        var now = 0L
        var dedupe = dedupe
        val cache = AddonResponseCache(
            dedupeEnabled = { this.dedupe },
            nowMs = { now },
            scope = scope.backgroundScope,
            responseTtlMs = 1_000L,
        )
        var loads = 0
        fun loader(body: String = "body"): suspend () -> String = {
            loads++
            "$body#$loads"
        }
    }

    @Test
    fun `TheIntroDB hosts are always fresh`() {
        assertTrue(AddonResponseCache.isAlwaysFresh("https://api.theintrodb.org/v4/submit"))
        assertTrue(AddonResponseCache.isAlwaysFresh("https://api.theintrodb.org/v4/preview?id=1"))
        assertTrue(AddonResponseCache.isAlwaysFresh("https://introdb.app/api/segments"))
        assertTrue(AddonResponseCache.isAlwaysFresh("HTTPS://API.THEINTRODB.ORG:443/v4/media"))
        assertFalse(AddonResponseCache.isAlwaysFresh("https://addon.example/stream/series/tt1:1:1.json"))
        assertFalse(AddonResponseCache.isAlwaysFresh("https://addon.example/introdb/stream.json"))
    }

    @Test
    fun `TheIntroDB is never cached, prefetched or shared`() = runTest {
        val harness = Harness(this)
        val url = "https://api.theintrodb.org/v4/preview?id=1"
        assertEquals("body#1", harness.cache.fetch(url, forceRefresh = false, load = harness.loader()))
        assertEquals("body#2", harness.cache.fetch(url, forceRefresh = false, load = harness.loader()))
        assertFalse(harness.cache.prefetch(url, ttlMs = 60_000L, load = harness.loader()))
        runCurrent()
        assertEquals(2, harness.loads)

        val gate = CompletableDeferred<Unit>()
        val slow: suspend () -> String = { harness.loads++; gate.await(); "slow" }
        val first = async { harness.cache.fetch(url, forceRefresh = false, load = slow) }
        val second = async { harness.cache.fetch(url, forceRefresh = false, load = slow) }
        runCurrent()
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(4, harness.loads)
    }

    @Test
    fun `identical requests share one load and reuse the body until it expires`() = runTest {
        val harness = Harness(this)
        val url = "https://addon.example/catalog/movie/top.json"
        val gate = CompletableDeferred<Unit>()
        val slow: suspend () -> String = { harness.loads++; gate.await(); "shared" }
        val first = async { harness.cache.fetch(url, forceRefresh = false, load = slow) }
        val second = async { harness.cache.fetch(url, forceRefresh = false, load = slow) }
        runCurrent()
        gate.complete(Unit)
        assertEquals("shared", first.await())
        assertEquals("shared", second.await())
        assertEquals(1, harness.loads)

        assertEquals("shared", harness.cache.fetch(url, forceRefresh = false, load = harness.loader()))
        assertEquals(1, harness.loads)

        harness.now = 1_000L
        assertEquals("body#2", harness.cache.fetch(url, forceRefresh = false, load = harness.loader()))
    }

    @Test
    fun `force refresh always loads`() = runTest {
        val harness = Harness(this)
        val url = "https://addon.example/catalog/movie/top.json"
        harness.cache.fetch(url, forceRefresh = false, load = harness.loader())
        assertEquals("body#2", harness.cache.fetch(url, forceRefresh = true, load = harness.loader()))
        assertEquals("body#2", harness.cache.fetch(url, forceRefresh = false, load = harness.loader()))
        assertEquals(2, harness.loads)
    }

    @Test
    fun `dedupe off loads every time but still serves a prefetched body`() = runTest {
        val harness = Harness(this, dedupe = false)
        val catalog = "https://addon.example/catalog/movie/top.json"
        harness.cache.fetch(catalog, forceRefresh = false, load = harness.loader())
        harness.cache.fetch(catalog, forceRefresh = false, load = harness.loader())
        assertEquals(2, harness.loads)

        val stream = "https://addon.example/stream/series/tt1:1:2.json"
        assertTrue(harness.cache.prefetch(stream, ttlMs = 60_000L, load = harness.loader("streams")))
        assertFalse(harness.cache.prefetch(stream, ttlMs = 60_000L, load = harness.loader("streams")))
        runCurrent()
        assertEquals(3, harness.loads)
        assertEquals("streams#3", harness.cache.fetch(stream, forceRefresh = false, load = harness.loader()))
        assertEquals(3, harness.loads)

        harness.now = 60_000L
        assertEquals("body#4", harness.cache.fetch(stream, forceRefresh = false, load = harness.loader()))
    }

    @Test
    fun `a request joins a prefetch that is still in flight`() = runTest {
        val harness = Harness(this, dedupe = false)
        val stream = "https://addon.example/stream/series/tt1:1:2.json"
        val gate = CompletableDeferred<Unit>()
        harness.cache.prefetch(stream, ttlMs = 60_000L) { harness.loads++; gate.await(); "prefetched" }
        runCurrent()
        val request = async { harness.cache.fetch(stream, forceRefresh = false, load = harness.loader()) }
        runCurrent()
        gate.complete(Unit)
        assertEquals("prefetched", request.await())
        assertEquals(1, harness.loads)
    }
}
