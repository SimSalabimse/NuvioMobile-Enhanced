package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The playback cap (2 -> 0 while buffering -> 1 while playing -> 2 when the player closes) must
 * restart the transfers it stopped. Mirrors how DownloadsRepository drives the start queue, its
 * live handles and the platform downloader, with a fake platform layer.
 */
class PlaybackCapRestartTest {
    private class FakeHandle(val id: String)

    private class Harness {
        val queue = DownloadStartQueue()
        val handles = mutableMapOf<String, FakeHandle>()
        val starts = mutableMapOf<String, Int>()
        val stopped = mutableListOf<String>()
        /** Rows shown as Downloading (the rest are Queued). */
        val downloading = mutableSetOf<String>()

        fun enqueue(id: String) = applyStarts(queue.request(id, thermalBlocksNextFile = false))

        fun setCap(cap: Int) {
            if (queue.maxActive == cap) return
            val shrinking = cap < queue.maxActive
            queue.maxActive = cap
            if (shrinking) {
                val demoted = queue.shrinkTo(cap)
                downloading -= demoted.toSet()
                // Like iOS, a deliberate stop sends no callback: nothing else clears the handle.
                handles.stopDemotedTransfers(demoted) { stopped += it }
            } else {
                applyStarts(queue.promote(thermalBlocksNextFile = false))
            }
        }

        fun finish(id: String) {
            handles.remove(id)
            downloading -= id
            applyStarts(queue.release(id, thermalBlocksNextFile = false))
        }

        private fun applyStarts(startedIds: List<String>) {
            val plan = planDownloadStarts(startedIds, handles.keys.toSet(), queue.maxActive)
            plan.deferred.forEach(queue::demoteToFront)
            downloading -= plan.deferred.toSet()
            downloading += plan.accepted
            plan.launch.forEach { id ->
                starts[id] = (starts[id] ?: 0) + 1
                handles[id] = FakeHandle(id)
            }
        }

        fun startsOf(id: String) = starts[id] ?: 0
    }

    @Test
    fun `stopped transfers start again when the cap rises and a file queued at zero follows`() {
        val h = Harness()
        h.enqueue("a")
        h.enqueue("b")
        assertEquals(setOf("a", "b"), h.handles.keys)

        h.setCap(0)
        assertEquals(listOf("b", "a"), h.stopped)
        assertTrue(h.handles.isEmpty(), "no handle may stay behind for a stopped transfer")

        h.enqueue("c")
        assertEquals(0, h.startsOf("c"))
        assertTrue(h.handles.isEmpty())

        h.setCap(1)
        assertEquals(2, h.startsOf("a"), "a is started again, not just shown as downloading")
        assertEquals(1, h.startsOf("b"))
        assertEquals(setOf("a"), h.handles.keys)
        assertEquals(setOf("a"), h.queue.activeIds())

        h.finish("a")
        assertEquals(2, h.startsOf("b"))
        assertEquals(setOf("b"), h.handles.keys)

        h.setCap(2)
        assertEquals(1, h.startsOf("c"), "the file queued while the cap was 0 starts")
        assertEquals(2, h.startsOf("a"))
        assertEquals(2, h.startsOf("b"), "b kept running under the lower cap and is not started twice")
        assertEquals(setOf("b", "c"), h.handles.keys)
    }

    @Test
    fun `full cycle 2 to 0 to 1 to 2 restarts each stopped transfer exactly once`() {
        val h = Harness()
        h.enqueue("a")
        h.enqueue("b")

        h.setCap(0)
        h.enqueue("c")
        h.setCap(1)
        h.setCap(2)

        assertEquals(2, h.startsOf("a"))
        assertEquals(2, h.startsOf("b"))
        assertEquals(0, h.startsOf("c"), "both slots are taken by the resumed transfers")
        assertEquals(setOf("a", "b"), h.handles.keys)
        assertEquals(listOf("c"), h.queue.waitingIds())

        h.finish("a")
        assertEquals(1, h.startsOf("c"))
    }

    @Test
    fun `a transfer still running under the lower cap is not restarted`() {
        val h = Harness()
        h.enqueue("a")
        h.enqueue("b")

        h.setCap(1)
        assertEquals(listOf("b"), h.stopped)
        assertEquals(setOf("a"), h.handles.keys)

        h.setCap(2)
        assertEquals(1, h.startsOf("a"))
        assertEquals(2, h.startsOf("b"))
        assertEquals(setOf("a", "b"), h.handles.keys)
    }

    @Test
    fun `opening the player (2 to 1) stops one transfer and closing it restarts that one`() {
        val h = Harness()
        h.enqueue("a")
        h.enqueue("b")

        h.setCap(PlaybackPriority.Level.Playing.maxDownloads)
        assertEquals(setOf("a"), h.handles.keys, "the stopped transfer leaves no handle behind")
        h.enqueue("c")
        assertEquals(setOf("a"), h.downloading)

        h.setCap(PlaybackPriority.Level.Normal.maxDownloads)
        assertEquals(1, h.startsOf("a"))
        assertEquals(2, h.startsOf("b"))
        assertEquals(setOf("a", "b"), h.handles.keys)
        assertEquals(setOf("a", "b"), h.downloading)

        h.finish("a")
        assertEquals(1, h.startsOf("c"))
        assertEquals(setOf("b", "c"), h.downloading)
    }

    @Test
    fun `pause during playback (2 to 0 on open) restarts every stopped transfer on close`() {
        val h = Harness()
        h.enqueue("a")
        h.enqueue("b")

        h.setCap(0)
        assertTrue(h.handles.isEmpty())
        assertTrue(h.downloading.isEmpty())

        h.setCap(PlaybackPriority.Level.Normal.maxDownloads)
        assertEquals(2, h.startsOf("a"))
        assertEquals(2, h.startsOf("b"))
        assertEquals(setOf("a", "b"), h.handles.keys)
        assertEquals(setOf("a", "b"), h.downloading)
    }

    @Test
    fun `a new file queued while the cap is lowered reaches Downloading when it is back to normal`() {
        val h = Harness()
        h.enqueue("a")

        h.setCap(0)
        h.enqueue("next-episode")
        assertTrue(h.downloading.isEmpty())

        h.setCap(PlaybackPriority.Level.Normal.maxDownloads)
        assertEquals(setOf("a", "next-episode"), h.downloading, "a stale handle for a must not hold the slot")
        assertEquals(2, h.startsOf("a"))
        assertEquals(1, h.startsOf("next-episode"))
        assertTrue(h.queue.waitingIds().isEmpty())
    }

    @Test
    fun `a live handle is accepted without a new launch and counts against the cap`() {
        val alreadyRunning = planDownloadStarts(startedIds = listOf("a"), liveIds = setOf("a"), maxActive = 1)
        assertEquals(listOf("a"), alreadyRunning.accepted)
        assertTrue(alreadyRunning.launch.isEmpty())

        val leftover = planDownloadStarts(startedIds = listOf("b", "c"), liveIds = setOf("stale"), maxActive = 2)
        assertEquals(listOf("b"), leftover.accepted)
        assertEquals(listOf("c"), leftover.deferred)
        assertEquals(listOf("b"), leftover.launch)
    }
}
