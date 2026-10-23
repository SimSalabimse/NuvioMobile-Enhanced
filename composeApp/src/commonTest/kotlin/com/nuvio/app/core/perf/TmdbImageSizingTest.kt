package com.nuvio.app.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbImageSizingTest {
    @Test
    fun `picks the smallest size that still covers the requested width`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w342/poster.jpg",
            TmdbImageSizing.forWidth("https://image.tmdb.org/t/p/w500/poster.jpg", 300),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
            TmdbImageSizing.forWidth("https://image.tmdb.org/t/p/original/backdrop.jpg", 1_024),
        )
    }

    @Test
    fun `never upsizes or touches other hosts`() {
        assertNull(TmdbImageSizing.forWidth("https://image.tmdb.org/t/p/w185/poster.jpg", 400))
        assertNull(TmdbImageSizing.forWidth("https://image.tmdb.org/t/p/w342/poster.jpg", 342))
        assertNull(TmdbImageSizing.forWidth("https://image.tmdb.org/t/p/original/poster.jpg", 4_000))
        assertNull(TmdbImageSizing.forWidth("https://example.com/t/p/w500/poster.jpg", 100))
        assertNull(TmdbImageSizing.forWidth("https://image.tmdb.org/t/p/h632/profile.jpg", 100))
        assertNull(TmdbImageSizing.forWidth("https://image.tmdb.org/t/p/w500/poster.jpg", 0))
    }
}
