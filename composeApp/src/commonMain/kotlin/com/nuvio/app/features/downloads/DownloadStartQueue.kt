package com.nuvio.app.features.downloads

/** At most two transfers run. A third episode waits. */
internal const val MAX_ACTIVE_DOWNLOADS = 2

/** Progress snapshots of the downloads JSON, not a cap on the transfer. */
internal const val DOWNLOAD_PROGRESS_PERSIST_INTERVAL_MS = 5_000L

/**
 * Flags for the iOS background URL session.
 * The active transfer is not rate limited. Discretionary is off so iOS does not defer it.
 */
internal data class BackgroundDownloadSessionPolicy(
    val discretionary: Boolean,
    val allowsCellularAccess: Boolean,
    val sendsLaunchEvents: Boolean,
)

internal val speedFirstBackgroundSessionPolicy = BackgroundDownloadSessionPolicy(
    discretionary = false,
    allowsCellularAccess = true,
    sendsLaunchEvents = true,
)

/**
 * Decides when the downloads JSON may be rewritten.
 * Status changes always write. Byte progress writes at most once per [intervalMs].
 */
internal class DownloadPersistGate(
    private val intervalMs: Long = DOWNLOAD_PROGRESS_PERSIST_INTERVAL_MS,
) {
    private var lastWriteAtMs: Long = Long.MIN_VALUE

    fun reset() {
        lastWriteAtMs = Long.MIN_VALUE
    }

    fun recordStatusWrite(nowMs: Long) {
        lastWriteAtMs = nowMs
    }

    fun allowProgressWrite(nowMs: Long): Boolean {
        if (lastWriteAtMs != Long.MIN_VALUE && nowMs - lastWriteAtMs < intervalMs) return false
        lastWriteAtMs = nowMs
        return true
    }
}

/**
 * FIFO of download ids. A critical thermal state refuses the next start and leaves
 * whatever is already active running at full speed.
 */
internal class DownloadStartQueue(
    private val maxActive: Int = MAX_ACTIVE_DOWNLOADS,
) {
    private val active = LinkedHashSet<String>()
    private val waiting = ArrayDeque<String>()

    fun activeIds(): Set<String> = active.toSet()

    fun waitingIds(): List<String> = waiting.toList()

    fun clear() {
        active.clear()
        waiting.clear()
    }

    /**
     * Line [id] up to run. [atFront] is an explicit resume: it becomes the next file
     * without interrupting a transfer that is already active.
     * Returns the ids that may start now, in order.
     */
    fun request(id: String, thermalBlocksNextFile: Boolean, atFront: Boolean = false): List<String> {
        active.remove(id)
        waiting.remove(id)
        if (atFront) waiting.addFirst(id) else waiting.addLast(id)
        return promote(thermalBlocksNextFile)
    }

    fun discard(id: String) {
        active.remove(id)
        waiting.remove(id)
    }

    /** [id] is no longer active or waiting. Returns ids that may start in the free slots. */
    fun release(id: String, thermalBlocksNextFile: Boolean): List<String> {
        active.remove(id)
        waiting.remove(id)
        return promote(thermalBlocksNextFile)
    }

    /** Put [id] back at the front of the line. It does not keep a slot. */
    fun demoteToFront(id: String) {
        active.remove(id)
        waiting.remove(id)
        waiting.addFirst(id)
    }

    fun promote(thermalBlocksNextFile: Boolean): List<String> {
        if (thermalBlocksNextFile) return emptyList()
        val started = mutableListOf<String>()
        while (active.size < maxActive && waiting.isNotEmpty()) {
            val next = waiting.removeFirst()
            active.add(next)
            started.add(next)
        }
        return started
    }

    /**
     * [activeIdsInOrder] are transfers already running in this process. They stay running
     * even when that is already the whole cap. [waitingIdsInOrder] are files that still
     * need a slot. A critical thermal state starts none of them.
     */
    fun restore(
        activeIdsInOrder: List<String>,
        waitingIdsInOrder: List<String>,
        thermalBlocksNextFile: Boolean,
    ): List<String> {
        clear()
        activeIdsInOrder.forEach { id -> active.add(id) }
        waitingIdsInOrder.forEach { id ->
            if (id !in active && id !in waiting) waiting.addLast(id)
        }
        return promote(thermalBlocksNextFile)
    }
}
