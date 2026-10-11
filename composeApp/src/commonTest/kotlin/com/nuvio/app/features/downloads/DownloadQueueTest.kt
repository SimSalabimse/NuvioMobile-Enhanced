package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadQueueTest {
    @Test
    fun `a season keeps two transfers active and the rest wait in order`() {
        val queue = DownloadStartQueue()

        val first = queue.request("e1", thermalBlocksNextFile = false)
        val second = queue.request("e2", thermalBlocksNextFile = false)
        val third = queue.request("e3", thermalBlocksNextFile = false)
        val fourth = queue.request("e4", thermalBlocksNextFile = false)

        assertEquals(listOf("e1"), first)
        assertEquals(listOf("e2"), second)
        assertEquals(emptyList(), third)
        assertEquals(emptyList(), fourth)
        assertEquals(setOf("e1", "e2"), queue.activeIds())
        assertEquals(listOf("e3", "e4"), queue.waitingIds())
    }

    @Test
    fun `a free slot starts the oldest waiting file`() {
        val queue = DownloadStartQueue()
        queue.request("e1", thermalBlocksNextFile = false)
        queue.request("e2", thermalBlocksNextFile = false)
        queue.request("e3", thermalBlocksNextFile = false)
        queue.request("e4", thermalBlocksNextFile = false)

        val started = queue.release("e1", thermalBlocksNextFile = false)

        assertEquals(listOf("e3"), started)
        assertEquals(setOf("e2", "e3"), queue.activeIds())
        assertEquals(listOf("e4"), queue.waitingIds())
    }

    @Test
    fun `critical thermal state does not start the next file and leaves the active one running`() {
        val queue = DownloadStartQueue()
        queue.request("e1", thermalBlocksNextFile = false)
        queue.request("e2", thermalBlocksNextFile = true)

        assertEquals(setOf("e1"), queue.activeIds())
        assertEquals(listOf("e2"), queue.waitingIds())

        assertEquals(emptyList(), queue.release("e1", thermalBlocksNextFile = true))
        assertEquals(emptySet(), queue.activeIds())
        assertEquals(listOf("e2"), queue.waitingIds())

        assertEquals(listOf("e2"), queue.promote(thermalBlocksNextFile = false))
        assertEquals(setOf("e2"), queue.activeIds())
    }

    @Test
    fun `critical thermal state delays the first file until the phone cools`() {
        val queue = DownloadStartQueue()

        assertEquals(emptyList(), queue.request("e1", thermalBlocksNextFile = true))
        assertEquals(emptySet(), queue.activeIds())
        assertEquals(listOf("e1"), queue.waitingIds())

        assertEquals(listOf("e1"), queue.promote(thermalBlocksNextFile = false))
        assertEquals(setOf("e1"), queue.activeIds())
    }

    @Test
    fun `resume is the next file and does not pass an active transfer`() {
        val queue = DownloadStartQueue()
        queue.request("e1", thermalBlocksNextFile = false)
        queue.request("e2", thermalBlocksNextFile = false)
        queue.request("e3", thermalBlocksNextFile = false)

        assertEquals(emptyList(), queue.request("e4", thermalBlocksNextFile = false, atFront = true))
        assertEquals(listOf("e4", "e3"), queue.waitingIds())

        assertEquals(listOf("e4"), queue.release("e1", thermalBlocksNextFile = false))
        assertEquals(setOf("e2", "e4"), queue.activeIds())
    }

    @Test
    fun `restore keeps a running file and only fills free slots`() {
        val queue = DownloadStartQueue()
        val started = queue.restore(
            activeIdsInOrder = listOf("running"),
            waitingIdsInOrder = listOf("e2", "e3", "e4"),
            thermalBlocksNextFile = false,
        )

        assertEquals(listOf("e2"), started)
        assertEquals(setOf("running", "e2"), queue.activeIds())
        assertEquals(listOf("e3", "e4"), queue.waitingIds())

        val blocked = DownloadStartQueue()
        assertEquals(
            emptyList(),
            blocked.restore(
                activeIdsInOrder = listOf("running"),
                waitingIdsInOrder = listOf("e2"),
                thermalBlocksNextFile = true,
            ),
        )
        assertEquals(setOf("running"), blocked.activeIds())
        assertEquals(listOf("e2"), blocked.waitingIds())
    }

    @Test
    fun `the background session is not discretionary and has no rate cap`() {
        val policy = speedFirstBackgroundSessionPolicy

        assertFalse(policy.discretionary)
        assertTrue(policy.allowsCellularAccess)
        assertTrue(policy.sendsLaunchEvents)
    }
}

class DownloadPersistGateTest {
    @Test
    fun `status writes are immediate and byte progress waits five seconds`() {
        val gate = DownloadPersistGate()

        gate.recordStatusWrite(1_000L)
        assertFalse(gate.allowProgressWrite(1_000L))
        assertFalse(gate.allowProgressWrite(5_999L))
        assertTrue(gate.allowProgressWrite(6_000L))
        assertFalse(gate.allowProgressWrite(6_001L))

        gate.recordStatusWrite(6_100L)
        assertFalse(gate.allowProgressWrite(6_100L))
        assertTrue(gate.allowProgressWrite(11_100L))
    }

    @Test
    fun `the first progress write is allowed when nothing has been written`() {
        val gate = DownloadPersistGate()

        assertTrue(gate.allowProgressWrite(50L))
        assertFalse(gate.allowProgressWrite(51L))
    }

    @Test
    fun `leaving the foreground only fills the two slots and the rest wait`() {
        val queue = DownloadStartQueue()
        listOf("e1", "e2", "e3", "e4").forEach { queue.request(it, thermalBlocksNextFile = false) }

        queue.maxActive = BACKGROUND_SUBMIT_CAP
        assertEquals(emptyList(), queue.promote(thermalBlocksNextFile = false))
        assertEquals(listOf("e3", "e4"), queue.waitingIds())

        queue.maxActive = MAX_ACTIVE_DOWNLOADS
        assertEquals(listOf("e3"), queue.release("e1", thermalBlocksNextFile = false))
    }

    @Test
    fun `episodes of one show wait lowest first whatever order they were queued in`() {
        val keys = mapOf(
            "e7" to DownloadOrderKey("tt1", 1, 7),
            "e8" to DownloadOrderKey("tt1", 1, 8),
            "e3" to DownloadOrderKey("tt1", 1, 3),
            "e4" to DownloadOrderKey("tt1", 1, 4),
            "s2e1" to DownloadOrderKey("tt1", 2, 1),
        )
        val queue = DownloadStartQueue(maxActive = 0, orderKeyOf = { keys[it] })
        listOf("s2e1", "e7", "movie", "e8", "e3", "e4").forEach { queue.request(it, thermalBlocksNextFile = false) }
        assertEquals(listOf("e3", "e4", "e7", "e8", "s2e1", "movie"), queue.waitingIds())

        queue.maxActive = MAX_ACTIVE_DOWNLOADS
        assertEquals(listOf("e3", "e4"), queue.promote(thermalBlocksNextFile = false))
    }

    @Test
    fun `restore puts waiting episodes back in episode order`() {
        val keys = (1..6).associate { "e$it" to DownloadOrderKey("tt1", 1, it) }
        val queue = DownloadStartQueue(orderKeyOf = { keys[it] })
        val started = queue.restore(
            activeIdsInOrder = emptyList(),
            waitingIdsInOrder = listOf("e6", "e5", "e3", "e4"),
            thermalBlocksNextFile = false,
        )
        assertEquals(listOf("e3", "e4"), started)
        assertEquals(listOf("e5", "e6"), queue.waitingIds())
    }

    @Test
    fun `resume season puts its episodes first in the given order and starts two`() {
        val queue = DownloadStartQueue(maxActive = 0)
        queue.request("other", thermalBlocksNextFile = false)
        queue.maxActive = MAX_ACTIVE_DOWNLOADS

        val started = queue.requestAllAtFront(listOf("e16", "e17", "e18", "e19"), thermalBlocksNextFile = false)
        assertEquals(listOf("e16", "e17"), started)
        assertEquals(listOf("e18", "e19", "other"), queue.waitingIds())
    }
}
