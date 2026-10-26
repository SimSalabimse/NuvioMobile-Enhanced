package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmdbStandaloneMetaCacheTest {
    @Test
    fun `a series fallback with no episodes is not cached`() {
        assertFalse(MetaDetailsRepository.shouldCacheFetchedStandaloneMeta(type = "series", videoCount = 0))
        assertFalse(MetaDetailsRepository.shouldCacheFetchedStandaloneMeta(type = "tv", videoCount = 0))
    }

    @Test
    fun `a series fallback that has episodes is cached`() {
        assertTrue(MetaDetailsRepository.shouldCacheFetchedStandaloneMeta(type = "series", videoCount = 22))
    }

    @Test
    fun `a movie fallback is cached even though it has no episode list`() {
        assertTrue(MetaDetailsRepository.shouldCacheFetchedStandaloneMeta(type = "movie", videoCount = 0))
    }
}
