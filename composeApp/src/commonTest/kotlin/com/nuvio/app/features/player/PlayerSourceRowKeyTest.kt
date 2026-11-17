package com.nuvio.app.features.player

import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerSourceRowKeyTest {
    @Test
    fun serverStreamsThatShareATitleGetDistinctRowKeys() {
        val item = ServerItemRef(connectionId = "home", itemId = "episode")
        val streams = listOf(
            serverStream(item, "direct"),
            serverStream(item, "transcode"),
            serverStream(item, "direct"),
        )
        val keys = streams.stablePlayerKeys()
        assertEquals(3, keys.size)
        assertEquals(keys.toSet().size, keys.size)
        assertTrue(keys[0].contains("direct"))
        assertTrue(keys[1].contains("transcode"))
        assertTrue(keys[0] != keys[2])
    }

    private fun serverStream(item: ServerItemRef, mediaSourceId: String) = StreamItem(
        name = "Episode 1",
        addonName = "Home",
        addonId = "server:home",
        serverTarget = ServerPlaybackTarget(item = item, mediaSourceId = mediaSourceId),
    )
}
