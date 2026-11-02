package com.nuvio.app.core.sync

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.serialization.Serializable

internal enum class OfflineOutboxKind {
    WatchedMark,
    WatchProgress,
    LibraryEdit,
}

internal enum class OfflineOutboxEntryState {
    Pending,
    Sending,
    Confirmed,
    HeldForNextEdge,
    DeferredWhilePlaying,
}

internal enum class OfflineFlushRunState {
    Idle,
    Debouncing,
    Flushing,
    PausedOffline,
    WaitingForPlayback,
    AttemptCapReached,
}

internal enum class OfflineConflictWinner {
    Local,
    Remote,
}

internal const val OfflineFlushMaxAttempts = 3
internal const val OfflineFlushBackoffBaseMs = 400L

@Serializable
internal data class OfflineOutboxEntry(
    val id: String,
    val profileId: Int,
    val kind: OfflineOutboxKind,
    val subjectKey: String,
    val timestampEpochMs: Long,
    val sequence: Long = 0L,
    val attempts: Int = 0,
    val state: OfflineOutboxEntryState = OfflineOutboxEntryState.Pending,
    val sendingTimestampEpochMs: Long = 0L,
    val superseded: Boolean = false,
    val valueToken: Long = 0L,
    val played: Boolean = true,
    val adding: Boolean = true,
    val mirrorTrackers: Boolean = false,
    val sourceName: String = "NUVIO_SYNC",
    val watchedItem: WatchedItem? = null,
    val progressEntry: WatchProgressEntry? = null,
    val libraryItem: LibraryItem? = null,
)

@Serializable
internal data class OfflineOutbox(
    val entries: List<OfflineOutboxEntry> = emptyList(),
    val run: OfflineFlushRunState = OfflineFlushRunState.Idle,
    val nextSequence: Long = 1L,
)

@Serializable
internal data class OfflineOutboxDocument(
    val entries: List<OfflineOutboxEntry> = emptyList(),
    val nextSequence: Long = 1L,
)

internal fun offlineOutboxId(profileId: Int, kind: OfflineOutboxKind, subjectKey: String): String =
    "$profileId|${kind.name}|$subjectKey"

internal fun offlineFlushBackoffMs(attempt: Int): Long {
    val shift = (attempt - 1).coerceAtLeast(0).coerceAtMost(4)
    return OfflineFlushBackoffBaseMs shl shift
}

internal fun resolveOfflineOutboxConflict(
    localTimestampEpochMs: Long,
    remoteTimestampEpochMs: Long,
): OfflineConflictWinner =
    if (remoteTimestampEpochMs > localTimestampEpochMs) {
        OfflineConflictWinner.Remote
    } else {
        OfflineConflictWinner.Local
    }

internal fun enqueueOfflineOutbox(outbox: OfflineOutbox, incoming: OfflineOutboxEntry): OfflineOutbox {
    val id = incoming.id.ifBlank { offlineOutboxId(incoming.profileId, incoming.kind, incoming.subjectKey) }
    val stamped = incoming.copy(id = id)
    val existing = outbox.entries.firstOrNull { it.id == id }
    if (existing == null) {
        val sequence = if (stamped.sequence > 0L) stamped.sequence else outbox.nextSequence
        return outbox.copy(
            entries = outbox.entries + stamped.copy(sequence = sequence, state = stamped.state),
            nextSequence = maxOf(outbox.nextSequence, sequence + 1L),
        )
    }
    if (stamped.timestampEpochMs < existing.timestampEpochMs) return outbox
    if (stamped.timestampEpochMs == existing.timestampEpochMs) {
        return outbox.copy(
            entries = outbox.entries.map { entry ->
                if (entry.id != id) entry else entry.withPayload(stamped)
            },
        )
    }
    val updated = existing.withPayload(stamped).copy(
        timestampEpochMs = stamped.timestampEpochMs,
        valueToken = stamped.valueToken,
        superseded = existing.state == OfflineOutboxEntryState.Sending,
        state = when (existing.state) {
            OfflineOutboxEntryState.Sending -> OfflineOutboxEntryState.Sending
            OfflineOutboxEntryState.DeferredWhilePlaying -> OfflineOutboxEntryState.DeferredWhilePlaying
            else -> OfflineOutboxEntryState.Pending
        },
        attempts = if (existing.state == OfflineOutboxEntryState.Sending ||
            existing.state == OfflineOutboxEntryState.DeferredWhilePlaying
        ) {
            existing.attempts
        } else {
            0
        },
    )
    return outbox.copy(entries = outbox.entries.map { if (it.id == id) updated else it })
}

internal fun reduceOnlineEdge(outbox: OfflineOutbox): OfflineOutbox {
    if (outbox.run == OfflineFlushRunState.Flushing) return outbox
    return outbox.copy(
        entries = outbox.entries.map { entry ->
            if (entry.state == OfflineOutboxEntryState.HeldForNextEdge) {
                entry.copy(state = OfflineOutboxEntryState.Pending, attempts = 0)
            } else {
                entry
            }
        },
        run = OfflineFlushRunState.Debouncing,
    )
}

internal fun claimFlushBatch(
    outbox: OfflineOutbox,
    playbackActive: Boolean,
    profileId: Int? = null,
): Pair<OfflineOutbox, List<OfflineOutboxEntry>> {
    val claimed = mutableListOf<OfflineOutboxEntry>()
    val entries = outbox.entries.map { entry ->
        if (profileId != null && entry.profileId != profileId) return@map entry
        if (entry.state != OfflineOutboxEntryState.Pending) return@map entry
        if (playbackActive && entry.kind != OfflineOutboxKind.WatchedMark) {
            entry.copy(state = OfflineOutboxEntryState.DeferredWhilePlaying)
        } else {
            val sending = entry.copy(
                state = OfflineOutboxEntryState.Sending,
                sendingTimestampEpochMs = entry.timestampEpochMs,
                superseded = false,
            )
            claimed += sending
            sending
        }
    }
    val orderedClaimed = claimed.sortedWith(flushOrder())
    return outbox.copy(entries = entries, run = OfflineFlushRunState.Flushing) to orderedClaimed
}

internal fun claimOne(outbox: OfflineOutbox, id: String): Pair<OfflineOutbox, OfflineOutboxEntry?> {
    val entry = outbox.entries.firstOrNull { it.id == id } ?: return outbox to null
    if (entry.state != OfflineOutboxEntryState.Pending) return outbox to null
    val claimed = entry.copy(
        state = OfflineOutboxEntryState.Sending,
        sendingTimestampEpochMs = entry.timestampEpochMs,
        superseded = false,
    )
    return outbox.copy(entries = outbox.entries.map { if (it.id == id) claimed else it }) to claimed
}

internal fun noteSendSucceeded(
    outbox: OfflineOutbox,
    id: String,
    sentTimestampEpochMs: Long,
): OfflineOutbox {
    val entries = outbox.entries.map { entry ->
        if (entry.id != id) return@map entry
        if (entry.state != OfflineOutboxEntryState.Sending && entry.state != OfflineOutboxEntryState.Confirmed) {
            return@map entry
        }
        if (entry.superseded || entry.timestampEpochMs > sentTimestampEpochMs) {
            entry.copy(
                state = OfflineOutboxEntryState.Pending,
                superseded = false,
                sendingTimestampEpochMs = 0L,
            )
        } else {
            entry.copy(state = OfflineOutboxEntryState.Confirmed, superseded = false)
        }
    }
    return outbox.copy(entries = entries)
}

internal fun dropConfirmed(outbox: OfflineOutbox): OfflineOutbox =
    outbox.copy(entries = outbox.entries.filterNot { it.state == OfflineOutboxEntryState.Confirmed })

internal fun noteSendFailed(outbox: OfflineOutbox, id: String): OfflineOutbox {
    val entries = outbox.entries.map { entry ->
        if (entry.id != id || entry.state != OfflineOutboxEntryState.Sending) return@map entry
        val attempts = entry.attempts + 1
        if (attempts >= OfflineFlushMaxAttempts) {
            entry.copy(attempts = attempts, state = OfflineOutboxEntryState.HeldForNextEdge, superseded = false)
        } else {
            entry.copy(attempts = attempts, state = OfflineOutboxEntryState.Pending)
        }
    }
    return outbox.copy(entries = entries)
}

internal fun noteOfflineDuringFlush(outbox: OfflineOutbox): OfflineOutbox =
    outbox.copy(
        entries = outbox.entries.map { entry ->
            if (entry.state == OfflineOutboxEntryState.Sending) {
                entry.copy(state = OfflineOutboxEntryState.Pending, sendingTimestampEpochMs = 0L)
            } else {
                entry
            }
        },
        run = OfflineFlushRunState.PausedOffline,
    )

internal fun recoverOfflineOutbox(outbox: OfflineOutbox): OfflineOutbox =
    outbox.copy(
        entries = outbox.entries.mapNotNull { entry ->
            when (entry.state) {
                OfflineOutboxEntryState.Confirmed -> null
                OfflineOutboxEntryState.Sending -> entry.copy(
                    state = OfflineOutboxEntryState.Pending,
                    sendingTimestampEpochMs = 0L,
                    superseded = false,
                )
                OfflineOutboxEntryState.DeferredWhilePlaying -> entry.copy(
                    state = OfflineOutboxEntryState.Pending,
                )
                else -> entry
            }
        },
        run = OfflineFlushRunState.Idle,
    )

internal fun resumeAfterPlayback(outbox: OfflineOutbox): OfflineOutbox {
    val entries = outbox.entries.map { entry ->
        if (entry.state == OfflineOutboxEntryState.DeferredWhilePlaying) {
            entry.copy(state = OfflineOutboxEntryState.Pending)
        } else {
            entry
        }
    }
    return outbox.copy(entries = entries, run = OfflineFlushRunState.Debouncing)
}

internal fun releaseSending(outbox: OfflineOutbox, id: String): OfflineOutbox =
    outbox.copy(
        entries = outbox.entries.map { entry ->
            if (entry.id == id && entry.state == OfflineOutboxEntryState.Sending) {
                entry.copy(state = OfflineOutboxEntryState.Pending, sendingTimestampEpochMs = 0L)
            } else {
                entry
            }
        },
    )

internal fun releaseAllSending(outbox: OfflineOutbox): OfflineOutbox =
    outbox.copy(
        entries = outbox.entries.map { entry ->
            if (entry.state == OfflineOutboxEntryState.Sending) {
                entry.copy(state = OfflineOutboxEntryState.Pending, sendingTimestampEpochMs = 0L)
            } else {
                entry
            }
        },
    )

internal fun applyRemoteConflict(
    outbox: OfflineOutbox,
    id: String,
    remoteTimestampEpochMs: Long,
): OfflineOutbox {
    val entry = outbox.entries.firstOrNull { it.id == id } ?: return outbox
    return if (
        resolveOfflineOutboxConflict(entry.timestampEpochMs, remoteTimestampEpochMs) ==
        OfflineConflictWinner.Remote
    ) {
        outbox.copy(entries = outbox.entries.filterNot { it.id == id })
    } else {
        outbox
    }
}

internal fun finishFlushRun(outbox: OfflineOutbox): OfflineOutbox {
    val state = when {
        outbox.run == OfflineFlushRunState.PausedOffline -> OfflineFlushRunState.PausedOffline
        outbox.entries.any { it.state == OfflineOutboxEntryState.DeferredWhilePlaying } ->
            OfflineFlushRunState.WaitingForPlayback
        outbox.entries.any { it.state == OfflineOutboxEntryState.HeldForNextEdge } ->
            OfflineFlushRunState.AttemptCapReached
        else -> OfflineFlushRunState.Idle
    }
    return outbox.copy(run = state)
}

internal fun mergeLoadedOutbox(disk: OfflineOutbox, memory: OfflineOutbox): OfflineOutbox {
    val byId = LinkedHashMap<String, OfflineOutboxEntry>()
    (disk.entries + memory.entries).forEach { entry ->
        val current = byId[entry.id]
        byId[entry.id] = when {
            current == null -> entry
            entry.timestampEpochMs >= current.timestampEpochMs -> entry
            else -> current
        }
    }
    return OfflineOutbox(
        entries = byId.values.sortedWith(flushOrder()),
        run = OfflineFlushRunState.Idle,
        nextSequence = maxOf(disk.nextSequence, memory.nextSequence),
    )
}

internal fun offlineOutboxDocument(outbox: OfflineOutbox): OfflineOutboxDocument =
    OfflineOutboxDocument(entries = outbox.entries, nextSequence = outbox.nextSequence)

internal fun offlineOutboxFromDocument(document: OfflineOutboxDocument): OfflineOutbox =
    OfflineOutbox(entries = document.entries, nextSequence = document.nextSequence)

private fun flushOrder(): Comparator<OfflineOutboxEntry> =
    compareBy({ it.kind.ordinal }, { it.profileId }, { it.sequence })

private fun OfflineOutboxEntry.withPayload(incoming: OfflineOutboxEntry): OfflineOutboxEntry =
    copy(
        valueToken = incoming.valueToken,
        played = incoming.played,
        adding = incoming.adding,
        mirrorTrackers = incoming.mirrorTrackers,
        sourceName = incoming.sourceName,
        watchedItem = incoming.watchedItem ?: watchedItem,
        progressEntry = incoming.progressEntry ?: progressEntry,
        libraryItem = incoming.libraryItem ?: libraryItem,
    )
