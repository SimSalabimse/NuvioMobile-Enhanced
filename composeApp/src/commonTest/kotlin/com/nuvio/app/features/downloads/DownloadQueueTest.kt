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
}
