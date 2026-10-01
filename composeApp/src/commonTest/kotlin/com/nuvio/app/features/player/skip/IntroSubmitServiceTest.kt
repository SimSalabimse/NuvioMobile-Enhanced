package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntroSubmitServiceTest {
    @Test
    fun `empty keys open on IntroDB`() {
        assertEquals(IntroSubmitService.INTRODB, defaultIntroSubmitService("", ""))
    }

    @Test
    fun `an introdb app key selects IntroDB and stays out of TheIntroDB`() {
        assertEquals(IntroSubmitService.INTRODB, defaultIntroSubmitService("idb_example", ""))
        assertEquals("idb_example", savedKeyForService(IntroSubmitService.INTRODB, "idb_example", ""))
        assertEquals("", savedKeyForService(IntroSubmitService.THE_INTRODB, "idb_example", ""))
        assertEquals(IntroSubmitKeyProblem.NONE, introSubmitKeyProblem(IntroSubmitService.INTRODB, "IDB_example"))
    }

    @Test
    fun `a theintrodb key selects TheIntroDB including the legacy introdb field`() {
        assertEquals(IntroSubmitService.THE_INTRODB, defaultIntroSubmitService("", "the-key"))
        assertEquals(IntroSubmitService.THE_INTRODB, defaultIntroSubmitService("legacy-key", ""))
        assertEquals("the-key", savedKeyForService(IntroSubmitService.THE_INTRODB, "legacy-key", "the-key"))
        assertEquals("legacy-key", savedKeyForService(IntroSubmitService.THE_INTRODB, "legacy-key", ""))
        assertEquals("", savedKeyForService(IntroSubmitService.INTRODB, "legacy-key", ""))
    }

    @Test
    fun `key shape is checked before a title can be submitted`() {
        assertEquals(IntroSubmitKeyProblem.MISSING, introSubmitKeyProblem(IntroSubmitService.INTRODB, "  "))
        assertEquals(IntroSubmitKeyProblem.INTRODB_PREFIX, introSubmitKeyProblem(IntroSubmitService.INTRODB, "the-key"))
        assertEquals(
            IntroSubmitKeyProblem.THEINTRODB_PREFIX,
            introSubmitKeyProblem(IntroSubmitService.THE_INTRODB, "idb_example"),
        )
        assertEquals(
            IntroSubmitBlockReason.INTRODB_MOVIE,
            introSubmitBlockReason(IntroSubmitService.INTRODB, "idb_example", isMovie = true, imdbId = "tt1", season = 1, episode = 1),
        )
        assertEquals(
            IntroSubmitBlockReason.INTRODB_EPISODE,
            introSubmitBlockReason(IntroSubmitService.INTRODB, "idb_example", isMovie = false, imdbId = "tt1", season = 0, episode = 1),
        )
        assertNull(
            introSubmitBlockReason(IntroSubmitService.THE_INTRODB, "the-key", isMovie = true, imdbId = "", season = 0, episode = 0),
        )
    }

    @Test
    fun `flag dialog hides the service section only when that service already has a working key`() {
        assertFalse(introSubmitShowsServiceSection(IntroSubmitService.INTRODB, "idb_example"))
        assertFalse(introSubmitShowsServiceSection(IntroSubmitService.THE_INTRODB, "the-key"))
        assertTrue(introSubmitShowsServiceSection(IntroSubmitService.INTRODB, "  "))
        assertTrue(introSubmitShowsServiceSection(IntroSubmitService.INTRODB, "the-key"))
        assertTrue(introSubmitShowsServiceSection(IntroSubmitService.THE_INTRODB, ""))
        assertTrue(introSubmitShowsServiceSection(IntroSubmitService.THE_INTRODB, "idb_example"))
        assertFalse(introSubmitShowsApiKeyField(IntroSubmitService.INTRODB, "idb_example"))
        assertTrue(introSubmitShowsApiKeyField(IntroSubmitService.INTRODB, "the-key"))
    }
}
