package com.nuvio.app.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedCacheTest {
    @Test
    fun `capped cache evicts the least recently used entry`() {
        var evicted = 0
        val cache = BoundedCache<String, Int>(maxEntries = 2, capEnabled = { true }, onEvicted = { evicted += it })
        cache["a"] = 1
        cache["b"] = 2
        assertEquals(1, cache["a"])
        cache["c"] = 3

        assertEquals(2, cache.size)
        assertTrue("a" in cache)
        assertFalse("b" in cache)
        assertTrue("c" in cache)
        assertEquals(1, evicted)
    }

    @Test
    fun `uncapped cache keeps every entry`() {
        var evicted = 0
        val cache = BoundedCache<Int, Int>(maxEntries = 2, capEnabled = { false }, onEvicted = { evicted += it })
        repeat(10) { cache[it] = it }
        assertEquals(10, cache.size)
        assertEquals(0, evicted)
    }

    @Test
    fun `cached nulls stay distinguishable from misses`() {
        val cache = BoundedCache<String, String?>(maxEntries = 4, capEnabled = { true }, onEvicted = {})
        cache["missing"] = null
        assertTrue(cache.containsKey("missing"))
        assertNull(cache["missing"])
        assertFalse(cache.containsKey("other"))
    }
}
