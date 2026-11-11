package com.nuvio.app.features.watched

import kotlin.test.Test
import kotlin.test.assertEquals

class CopyDirtyWatchedKeysTest {
    @Test
    fun theSavedKeySetDoesNotShareTheLiveSet() {
        val live = mutableSetOf("episode-1")
        val saved = copyDirtyWatchedKeys(live)
        live += "episode-2"
        assertEquals(setOf("episode-1"), saved)
    }
}
