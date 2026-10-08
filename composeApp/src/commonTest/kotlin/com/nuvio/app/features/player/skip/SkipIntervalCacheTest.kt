package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipIntervalCacheTest {
    @Test
    fun cachedSkipLookupIsAHitUntilPlayerDisposeClearsIt() {
        val key = "tt0944947:1:2"
        val intervals = listOf(
            SkipInterval(startTime = 12.0, endTime = 90.0, type = "intro", provider = "test"),
        )
        SkipIntroRepository.clearCache()
        SkipIntroRepository.skipIntervalCache.store(key, intervals)
        assertEquals(intervals, SkipIntroRepository.skipIntervalCache.lookup(key))
        SkipIntroRepository.clearCache()
        assertNull(SkipIntroRepository.skipIntervalCache.lookup(key))
    }
}
