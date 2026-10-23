package com.nuvio.app.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackQuietModeTest {
    @Test
    fun `work runs immediately while no player is open`() {
        assertFalse(PlaybackQuietMode.deferWhilePlaying("test/idle") { error("must not be stored") })
    }

    @Test
    fun `work waits for the last player to close and keeps only the latest per key`() {
        val ran = mutableListOf<String>()
        PlaybackQuietMode.playerOpened()
        PlaybackQuietMode.playerOpened()
        try {
            assertTrue(PlaybackQuietMode.isQuiet())
            assertTrue(PlaybackQuietMode.deferWhilePlaying("test/refresh") { ran += "first" })
            assertTrue(PlaybackQuietMode.deferWhilePlaying("test/refresh") { ran += "latest" })
            assertTrue(PlaybackQuietMode.deferWhilePlaying("test/other") { ran += "other" })
            PlaybackQuietMode.playerClosed()
            assertTrue(ran.isEmpty())
        } finally {
            PlaybackQuietMode.playerClosed()
        }
        assertEquals(listOf("latest", "other"), ran)
        assertFalse(PlaybackQuietMode.isQuiet())
    }
}
