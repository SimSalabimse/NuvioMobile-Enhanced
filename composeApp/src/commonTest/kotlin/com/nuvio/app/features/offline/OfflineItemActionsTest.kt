package com.nuvio.app.features.offline

import com.nuvio.app.core.sync.OfflineAccountFlush
import com.nuvio.app.core.sync.OfflineOutboxDisk
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.tracking.WatchProgressSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineItemActionsTest {
    @Test
    fun stateTable_onlineOfflineByCachedByDownloadedByAction() {
        val actions = OfflineSurfaceAction.entries
        listOf(true, false).forEach { online ->
            listOf(true, false).forEach { cached ->
                listOf(true, false).forEach { downloaded ->
                    val facts = OfflineItemFacts(
                        online = online,
                        cached = cached,
                        downloaded = downloaded,
                    )
                    actions.forEach { action ->
                        assertEquals(
                            expectedEnabled(facts, action),
                            offlineSurfaceActionEnabled(facts, action),
                            "online=$online cached=$cached downloaded=$downloaded action=$action",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun offlineWatchedToggleWritesOutboxImmediately() {
        val show = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "s1e1", title = "One", season = 1, episode = 1, released = "2026-03-01"),
                MetaVideo(id = "s1e2", title = "Two", season = 1, episode = 2, released = "2026-03-08"),
            ),
        )
        val cachedFacts = OfflineItemFacts(online = false, cached = true, downloaded = false)
        val unwatch = offlineWatchedToggleEntries(
            facts = cachedFacts,
            played = false,
            id = show.id,
            type = show.type,
            name = show.name,
            poster = null,
            releaseInfo = null,
            currentlyWatched = true,
            cachedMeta = show,
            downloadedEpisodes = emptyList(),
            todayIsoDate = "2026-03-30",
            markedAtEpochMs = 1_700_000_060_000L,
            profileId = 1,
            source = WatchProgressSource.NUVIO_SYNC,
        )
        assertEquals(3, unwatch.size)
        assertTrue(unwatch.all { !it.played })
        assertEquals(1_700_000_060_000L, unwatch.first().timestampEpochMs)
        assertTrue(unwatch.any { it.subjectKey.endsWith(":1:1") })
        assertTrue(unwatch.any { it.subjectKey.endsWith(":1:2") })

        val downloadedOnly = offlineWatchedToggleEntries(
            facts = OfflineItemFacts(online = false, cached = false, downloaded = true),
            played = true,
            id = "show",
            type = "series",
            name = "Show",
            poster = null,
            releaseInfo = null,
            currentlyWatched = false,
            cachedMeta = null,
            downloadedEpisodes = listOf(
                DownloadedEpisodeRef(1, 4, "Four", "s1e4", null),
            ),
            todayIsoDate = "2026-03-30",
            markedAtEpochMs = 1_700_000_120_000L,
            profileId = 1,
            source = WatchProgressSource.NUVIO_SYNC,
        )
        assertEquals(2, downloadedOnly.size)
        assertTrue(downloadedOnly.all { it.played })
        assertTrue(downloadedOnly.any { it.subjectKey.endsWith(":1:4") })

        val blocked = offlineWatchedToggleEntries(
            facts = OfflineItemFacts(online = false, cached = false, downloaded = false),
            played = true,
            id = "movie",
            type = "movie",
            name = "Movie",
            poster = null,
            releaseInfo = null,
            currentlyWatched = false,
            cachedMeta = null,
            downloadedEpisodes = emptyList(),
            todayIsoDate = "2026-03-30",
            markedAtEpochMs = 5L,
            profileId = 1,
            source = WatchProgressSource.NUVIO_SYNC,
        )
        assertTrue(blocked.isEmpty())

        val disk = MemoryOutboxDisk()
        OfflineAccountFlush.resetForTest()
        OfflineAccountFlush.disk = disk
        OfflineAccountFlush.online = { false }
        OfflineAccountFlush.seedStoredQueues = false
        try {
            unwatch.forEach(OfflineAccountFlush::note)
            val saved = OfflineAccountFlush.snapshot()
            assertEquals(unwatch.map { it.id }.toSet(), saved.entries.map { it.id }.toSet())
            assertTrue(saved.entries.all { !it.played })
            assertFalse(disk.payload.isNullOrBlank())
            downloadedOnly.forEach(OfflineAccountFlush::note)
            val marked = OfflineAccountFlush.snapshot().entries.first { it.subjectKey.endsWith(":1:4") }
            assertTrue(marked.played)
            assertEquals(1_700_000_120_000L, marked.timestampEpochMs)
        } finally {
            OfflineAccountFlush.resetForTest()
        }
    }
}

private fun expectedEnabled(facts: OfflineItemFacts, action: OfflineSurfaceAction): Boolean {
    val onDevice = facts.online || facts.cached || facts.downloaded
    return when (action) {
        OfflineSurfaceAction.LongPressMenu,
        OfflineSurfaceAction.Details,
        OfflineSurfaceAction.WatchedToggle,
        -> onDevice
        OfflineSurfaceAction.Play,
        -> facts.online || facts.downloaded
        OfflineSurfaceAction.StreamsLookup,
        OfflineSurfaceAction.Trailers,
        OfflineSurfaceAction.RemoteSearch,
        OfflineSurfaceAction.AccountSync,
        -> facts.online
    }
}

private class MemoryOutboxDisk : OfflineOutboxDisk {
    var payload: String? = null
    override fun read(): String? = payload
    override fun write(payload: String) {
        this.payload = payload
    }
}
