package com.nuvio.app.core.perf

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PosterOfflineImageDiskStoreTest {
    @Test
    fun diskRoundTripEvictsLeastRecentlyUsedAndIgnoresCorruptFile() = runBlocking {
        val directory = Files.createTempDirectory("poster-offline").toFile()
        try {
            fun cache() = PosterOfflineImageDiskCache(
                OkioPosterOfflineImageBlobStore(directory.absolutePath),
                maxBytes = 100,
            )
            val payload = ByteArray(40) { 9 }
            val first = cache()
            assertTrue(first.store("a", payload, freshUntilMillis = 10_000, nowMillis = 1, contentType = "image/jpeg"))
            assertTrue(first.store("b", payload, freshUntilMillis = 10_000, nowMillis = 2))
            assertTrue(first.open("a", nowMillis = 3) != null)
            assertTrue(first.store("c", payload, freshUntilMillis = 10_000, nowMillis = 4))

            val reloaded = cache()
            assertTrue(reloaded.contains("a"))
            assertFalse(reloaded.contains("b"))
            assertTrue(reloaded.contains("c"))
            assertEquals("image/jpeg", reloaded.open("a", nowMillis = 5)?.contentType)

            val key = posterOfflineImageFileKey("a")
            java.io.File(directory, "$key.bin").writeBytes(ByteArray(8))
            val afterCorrupt = cache()
            assertNull(afterCorrupt.open("a", nowMillis = 6))
            assertFalse(afterCorrupt.contains("a"))
            assertTrue(afterCorrupt.contains("c"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
