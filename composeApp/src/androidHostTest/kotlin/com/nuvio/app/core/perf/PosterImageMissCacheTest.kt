package com.nuvio.app.core.perf

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PosterImageMissCacheTest {
    @Test
    fun anEmptyResultIsRefetchedAfterArtworkAppears() = runBlocking {
        val directory = Files.createTempDirectory("poster-miss").toFile()
        try {
            val cache = PosterOfflineImageDiskCache(OkioPosterOfflineImageBlobStore(directory.absolutePath))
            val now = 1_000L
            assertTrue(cache.storeMiss("still", freshUntilMillis = now + PosterOfflineImageCachePolicy.NegativeTtlMs, nowMillis = now))
            val freshMiss = cache.open("still", nowMillis = now + 1_000L)
            assertEquals(PosterImageEntryKind.Miss, freshMiss?.kind)
            assertEquals(PosterImageCacheFreshness.Fresh, freshMiss?.freshness)
            val held = PosterOfflineImageCachePolicy.decideEntry(
                PosterImageEntryKind.Miss,
                PosterImageCacheFreshness.Fresh,
                PosterImageNetworkState.Online,
            )
            assertFalse(held.makeNetworkRequest)

            val expired = cache.open("still", nowMillis = now + PosterOfflineImageCachePolicy.NegativeTtlMs)
            assertEquals(PosterImageCacheFreshness.Expired, expired?.freshness)
            val retry = PosterOfflineImageCachePolicy.decideEntry(
                PosterImageEntryKind.Miss,
                PosterImageCacheFreshness.Expired,
                PosterImageNetworkState.Online,
            )
            assertTrue(retry.makeNetworkRequest)

            val artwork = ByteArray(40) { 7 }
            assertTrue(cache.store("still", artwork, freshUntilMillis = now + 60_000L, nowMillis = now + 20_000L, contentType = "image/jpeg"))
            val loaded = cache.open("still", nowMillis = now + 21_000L)
            assertEquals(PosterImageEntryKind.Image, loaded?.kind)
            assertEquals("image/jpeg", loaded?.contentType)
            assertTrue(loaded?.path != null)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aFailureTtlExpires() {
        val now = 5_000L
        val freshUntil = PosterOfflineImageCachePolicy.freshUntilMillis(null, now)
        assertEquals(now, freshUntil)
        assertEquals(
            PosterImageCacheFreshness.Expired,
            PosterOfflineImageCachePolicy.freshness(now + PosterOfflineImageCachePolicy.NegativeTtlMs, now + PosterOfflineImageCachePolicy.NegativeTtlMs),
        )
        val expired = PosterOfflineImageCachePolicy.decideEntry(
            PosterImageEntryKind.Miss,
            PosterImageCacheFreshness.Expired,
            PosterImageNetworkState.Online,
        )
        assertTrue(expired.makeNetworkRequest)
        assertFalse(expired.useCachedBytes)
        val within = PosterOfflineImageCachePolicy.decideEntry(
            PosterImageEntryKind.Miss,
            PosterImageCacheFreshness.Fresh,
            PosterImageNetworkState.Online,
        )
        assertFalse(within.makeNetworkRequest)
    }

    @Test
    fun aLegacyEmptyEntryIsNotServed() = runBlocking {
        val directory = Files.createTempDirectory("poster-legacy").toFile()
        try {
            val key = posterOfflineImageFileKey("still")
            java.io.File(directory, "index.v1").writeText(
                "$key\t40\t999999\t1\timage/jpeg\n",
            )
            java.io.File(directory, "$key.bin").writeBytes(ByteArray(40) { 1 })
            val cache = PosterOfflineImageDiskCache(OkioPosterOfflineImageBlobStore(directory.absolutePath))
            assertNull(cache.open("still", nowMillis = 10L))
            assertFalse(cache.contains("still"))
            assertFalse(java.io.File(directory, "$key.bin").exists())
            val artwork = ByteArray(48) { 3 }
            assertTrue(cache.store("still", artwork, freshUntilMillis = 50_000L, nowMillis = 20L, contentType = "image/jpeg"))
            assertEquals(PosterImageEntryKind.Image, cache.open("still", nowMillis = 21L)?.kind)
        } finally {
            directory.deleteRecursively()
        }
    }
}
