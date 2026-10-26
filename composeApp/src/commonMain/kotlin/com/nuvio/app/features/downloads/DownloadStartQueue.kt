package com.nuvio.app.features.downloads

/** At most two transfers run. A third episode waits. */
internal const val MAX_ACTIVE_DOWNLOADS = 2

/**
 * Cap while iOS moves transfers to the background session before suspension.
 * It used to be 64: every waiting file was submitted at once. Debrid links of one season sit on
 * different hosts, so the session's two-per-host limit did not hold them back and the system
 * started them in its own order (E7 and E8 before E3), many at once. Only the next two in
 * episode order are submitted now; a finished file starts the next one from its completion.
 */
internal const val BACKGROUND_SUBMIT_CAP = MAX_ACTIVE_DOWNLOADS

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
 * What to do with ids the start queue just let through. [accepted] are shown as downloading,
 * [deferred] go back to the front of the line (no free transfer slot), [launch] need a new
 * platform transfer: an accepted id that still holds a live handle keeps running as it is.
 * [pinned] ids (the file the player reads from) are never deferred, also past [maxActive].
 */
internal data class DownloadStartPlan(
    val accepted: List<String>,
    val deferred: List<String>,
    val launch: List<String>,
)

/**
 * Which held ids may be pulled into a slot. A waiting one is not pulled while downloads are
 * paused for playback or while the device is too hot; one that already runs is left to the
 * shrink keep-set.
 */
@Suppress("UNUSED_PARAMETER")
internal fun heldIdsToStart(
    held: Set<String>,
    active: Set<String>,
    pauseAll: Boolean,
    thermalBlocks: Boolean,
): Set<String> = if (pauseAll || thermalBlocks) emptySet() else held

internal fun planDownloadStarts(
    startedIds: List<String>,
    liveIds: Set<String>,
    maxActive: Int,
    pinned: Set<String> = emptySet(),
): DownloadStartPlan {
    val accepted = mutableListOf<String>()
    val deferred = mutableListOf<String>()
    startedIds.forEach { id ->
        val full = id !in liveIds && liveIds.size + accepted.size >= maxActive
        if (full && id !in pinned) deferred.add(id) else accepted.add(id)
    }
    return DownloadStartPlan(accepted, deferred, accepted.filter { it !in liveIds })
}

/**
 * Stops transfers a lower cap pushed back into the line and forgets their handles. iOS sends no
 * callback for a deliberate stop, so a handle left behind would make the id look live: a later
 * promote would skip its restart and count it against the cap. [stop] gets the handle when there
 * was one: stopping through it also stops a transfer that is still being opened.
 */
internal fun <H> MutableMap<String, H>.stopDemotedTransfers(ids: List<String>, stop: (String, H?) -> Unit) {
    ids.forEach { id ->
        stop(id, remove(id))
    }
}

/** Where an episode sits in its show, so waiting episodes of one show start lowest first. */
internal data class DownloadOrderKey(
    val showKey: String,
    val season: Int,
    val episode: Int,
) : Comparable<DownloadOrderKey> {
    override fun compareTo(other: DownloadOrderKey): Int =
        compareValuesBy(this, other, { it.season }, { it.episode })
}

/**
 * Waiting line of download ids. Episodes of the same show wait in ascending season/episode
 * order whatever order they were queued in; other titles keep the order they were queued.
 * A critical thermal state refuses the next start and leaves whatever is already active
 * running at full speed.
 */
internal class DownloadStartQueue(
    var maxActive: Int = MAX_ACTIVE_DOWNLOADS,
    private val orderKeyOf: (String) -> DownloadOrderKey? = { null },
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
        if (atFront) waiting.addFirst(id) else insertInOrder(id)
        return promote(thermalBlocksNextFile)
    }

    /**
     * Put [idsInOrder] at the front of the line in exactly that order, ahead of everything
     * already waiting, then fill the free slots once. Used by Resume season.
     */
    fun requestAllAtFront(idsInOrder: List<String>, thermalBlocksNextFile: Boolean): List<String> {
        idsInOrder.forEach { id ->
            active.remove(id)
            waiting.remove(id)
        }
        idsInOrder.asReversed().forEach { id -> waiting.addFirst(id) }
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

    /**
     * Moves the newest active ids back to the front of the line until at most [max] are active.
     * Ids in [keep] stay active even past [max].
     */
    fun shrinkTo(max: Int, keep: Set<String> = emptySet()): List<String> {
        val demoted = mutableListOf<String>()
        while (active.size > max) {
            val newest = active.lastOrNull { it !in keep } ?: break
            active.remove(newest)
            waiting.addFirst(newest)
            demoted.add(newest)
        }
        return demoted
    }

    /** Waiting [ids] become active whatever the cap. Returns the ids moved, in line order. */
    fun pin(ids: Set<String>): List<String> {
        val pinned = waiting.filter { it in ids }
        pinned.forEach { id ->
            waiting.remove(id)
            active.add(id)
        }
        return pinned
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
            if (id !in active && id !in waiting) insertInOrder(id)
        }
        return promote(thermalBlocksNextFile)
    }

    /** Before the first waiting episode of the same show that comes later; otherwise at the end. */
    private fun insertInOrder(id: String) {
        val key = orderKeyOf(id)
        if (key == null) {
            waiting.addLast(id)
            return
        }
        val index = waiting.indexOfFirst { other ->
            val otherKey = orderKeyOf(other)
            otherKey != null && otherKey.showKey == key.showKey && otherKey > key
        }
        if (index < 0) waiting.addLast(id) else waiting.add(index, id)
    }
}
