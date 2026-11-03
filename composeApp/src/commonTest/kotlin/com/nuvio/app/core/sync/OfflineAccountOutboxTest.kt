package com.nuvio.app.core.sync

import com.nuvio.app.core.logging.InAppLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OfflineAccountOutboxTest {
    @Test
    fun outboxStateTable() {
        val kinds = listOf(
            OfflineOutboxKind.WatchedMark,
            OfflineOutboxKind.WatchProgress,
            OfflineOutboxKind.LibraryEdit,
        )
        val events = listOf(
            OutboxEvent.OnlineEdge,
            OutboxEvent.SecondEdgeDuringFlush,
            OutboxEvent.CrashMidFlush,
            OutboxEvent.ServerError,
            OutboxEvent.OfflineAgainMidFlush,
        )
        kinds.forEach { kind ->
            events.forEach { event ->
                val outcome = simulate(kind, event)
                when (event) {
                    OutboxEvent.OnlineEdge -> {
                        assertEquals(
                            listOf(offlineOutboxId(1, kind, outcome.subject)),
                            outcome.sentIds,
                            "$kind $event",
                        )
                        assertTrue(outcome.remaining.isEmpty(), "$kind $event")
                        if (kind == OfflineOutboxKind.WatchProgress) {
                            assertEquals(90L, outcome.sentValue, "$kind latest progress")
                        }
                    }
                    OutboxEvent.SecondEdgeDuringFlush -> {
                        assertEquals(1, outcome.sentIds.size, "$kind $event")
                        assertEquals(OfflineOutboxEntryState.Sending, outcome.remaining.single().state, "$kind $event")
                        assertEquals(OfflineFlushRunState.Flushing, outcome.run, "$kind $event")
                    }
                    OutboxEvent.CrashMidFlush -> {
                        assertEquals(OfflineOutboxEntryState.Pending, outcome.remaining.single().state, "$kind unconfirmed")
                        assertTrue(outcome.confirmedGone, "$kind confirmed crash")
                    }
                    OutboxEvent.ServerError -> {
                        assertEquals(OfflineOutboxEntryState.HeldForNextEdge, outcome.remaining.single().state, "$kind $event")
                        assertEquals(OfflineFlushMaxAttempts, outcome.remaining.single().attempts, "$kind $event")
                        assertEquals(1, outcome.remaining.size, "$kind $event")
                    }
                    OutboxEvent.OfflineAgainMidFlush -> {
                        assertEquals(OfflineOutboxEntryState.Pending, outcome.remaining.single().state, "$kind $event")
                        assertEquals(OfflineFlushRunState.PausedOffline, outcome.run, "$kind $event")
                        assertTrue(outcome.sentIds.isEmpty(), "$kind $event")
                    }
                }
            }
        }

        val ordered = claimFlushBatch(
            enqueueAllKinds(profileId = 4),
            playbackActive = false,
        ).second.map { it.kind }
        assertEquals(
            listOf(
                OfflineOutboxKind.WatchedMark,
                OfflineOutboxKind.WatchProgress,
                OfflineOutboxKind.LibraryEdit,
            ),
            ordered,
        )

        val isolated = claimFlushBatch(
            enqueue(OfflineOutbox(), OfflineOutboxKind.WatchedMark, profileId = 1, subject = "show:1:1", timestamp = 5L) 
                .let { enqueue(it, OfflineOutboxKind.LibraryEdit, profileId = 2, subject = "movie:b", timestamp = 6L) },
            playbackActive = false,
            profileId = 1,
        )
        assertEquals(listOf(1), isolated.second.map { it.profileId })
        assertEquals(
            OfflineOutboxEntryState.Pending,
            isolated.first.entries.first { it.profileId == 2 }.state,
        )

        val playback = claimFlushBatch(enqueueAllKinds(profileId = 7), playbackActive = true)
        assertEquals(listOf(OfflineOutboxKind.WatchedMark), playback.second.map { it.kind })
        assertEquals(
            OfflineOutboxEntryState.DeferredWhilePlaying,
            playback.first.entries.first { it.kind == OfflineOutboxKind.WatchProgress }.state,
        )
        assertEquals(
            OfflineOutboxEntryState.DeferredWhilePlaying,
            playback.first.entries.first { it.kind == OfflineOutboxKind.LibraryEdit }.state,
        )
        val resumed = claimFlushBatch(resumeAfterPlayback(playback.first), playbackActive = false).second
            .map { it.kind }
        assertEquals(
            listOf(OfflineOutboxKind.WatchProgress, OfflineOutboxKind.LibraryEdit),
            resumed,
        )

        val localAdd = enqueue(
            OfflineOutbox(),
            OfflineOutboxKind.LibraryEdit,
            profileId = 1,
            subject = "movie:added",
            timestamp = 20L,
        )
        val localId = localAdd.entries.single().id
        assertEquals(
            OfflineConflictWinner.Local,
            resolveOfflineOutboxConflict(20L, 10L),
        )
        assertEquals(
            1,
            applyRemoteConflict(localAdd, localId, remoteTimestampEpochMs = 10L).entries.size,
        )
        assertEquals(
            OfflineConflictWinner.Remote,
            resolveOfflineOutboxConflict(10L, 20L),
        )
        assertTrue(
            applyRemoteConflict(
                enqueue(OfflineOutbox(), OfflineOutboxKind.LibraryEdit, 1, "movie:added", 10L),
                offlineOutboxId(1, OfflineOutboxKind.LibraryEdit, "movie:added"),
                remoteTimestampEpochMs = 20L,
            ).entries.isEmpty(),
        )
        assertEquals(OfflineConflictWinner.Local, resolveOfflineOutboxConflict(5L, 5L))
    }

    @Test
    fun onlineEdgeHookFlushesEachQueuedKindOnce() = runBlocking {
        OfflineAccountFlush.resetForTest()
        val sent = mutableListOf<String>()
        try {
            OfflineAccountFlush.disk = MemoryOfflineOutboxDisk()
            OfflineAccountFlush.suspendDelay = { }
            OfflineAccountFlush.accountReady = { true }
            OfflineAccountFlush.playbackActive = { false }
            OfflineAccountFlush.seedStoredQueues = false
            OfflineAccountFlush.online = { false }
            OfflineAccountFlush.transport = object : OfflineAccountTransport {
                override suspend fun send(entry: OfflineOutboxEntry): Boolean {
                    sent += "${entry.kind.name}:${entry.subjectKey}:${entry.valueToken}"
                    return true
                }
            }
            OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchedMark, 3, "series:show:1:4", 10L, 1L))
            OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchProgress, 3, "show_s1e4", 11L, 20L))
            OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchProgress, 3, "show_s1e4", 12L, 80L))
            OfflineAccountFlush.note(sample(OfflineOutboxKind.LibraryEdit, 3, "movie:library", 13L, 1L))
            OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchedMark, 8, "movie:other", 14L, 1L))
            assertEquals(4, OfflineAccountFlush.snapshot().entries.size)
            OfflineAccountFlush.online = { true }
            OfflineAccountFlush.onOfflineToOnlineEdge()
            OfflineAccountFlush.awaitIdle()
            assertEquals(
                listOf(
                    "WatchedMark:series:show:1:4:1",
                    "WatchedMark:movie:other:1",
                    "WatchProgress:show_s1e4:80",
                    "LibraryEdit:movie:library:1",
                ),
                sent,
            )
            assertTrue(OfflineAccountFlush.snapshot().entries.isEmpty())
            OfflineAccountFlush.onOfflineToOnlineEdge()
            OfflineAccountFlush.awaitIdle()
            assertEquals(4, sent.size)
        } finally {
            OfflineAccountFlush.resetForTest()
        }
    }

    @Test
    fun playbackDefersProgressAndLibraryUntilThePlayerCloses() = runBlocking {
        OfflineAccountFlush.resetForTest()
        val sent = mutableListOf<OfflineOutboxKind>()
        var playing = true
        try {
            OfflineAccountFlush.disk = MemoryOfflineOutboxDisk()
            OfflineAccountFlush.suspendDelay = { }
            OfflineAccountFlush.accountReady = { true }
            OfflineAccountFlush.seedStoredQueues = false
            OfflineAccountFlush.online = { true }
            OfflineAccountFlush.playbackActive = { playing }
            OfflineAccountFlush.transport = object : OfflineAccountTransport {
                override suspend fun send(entry: OfflineOutboxEntry): Boolean {
                    sent += entry.kind
                    return true
                }
            }
            OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchedMark, 1, "series:show:1:1", 1L, 1L))
            OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchProgress, 1, "show_s1e1", 2L, 40L))
            OfflineAccountFlush.note(sample(OfflineOutboxKind.LibraryEdit, 1, "movie:keep", 3L, 1L))
            OfflineAccountFlush.onOfflineToOnlineEdge()
            OfflineAccountFlush.awaitIdle()
            assertEquals(listOf(OfflineOutboxKind.WatchedMark), sent)
            assertEquals(
                OfflineFlushRunState.WaitingForPlayback,
                OfflineAccountFlush.snapshot().run,
            )
            playing = false
            OfflineAccountFlush.onPlaybackClosed()
            OfflineAccountFlush.awaitIdle()
            assertEquals(
                listOf(
                    OfflineOutboxKind.WatchedMark,
                    OfflineOutboxKind.WatchProgress,
                    OfflineOutboxKind.LibraryEdit,
                ),
                sent,
            )
            assertTrue(OfflineAccountFlush.snapshot().entries.isEmpty())
        } finally {
            OfflineAccountFlush.resetForTest()
        }
    }

    @Test
    fun noteIsReadableFromAFreshStorageInstance() = runBlocking {
        OfflineAccountFlush.resetForTest()
        val bytes = SharedOutboxBytes()
        try {
            assertSame(defaultOfflineOutboxDiskDispatcher, kotlinx.coroutines.Dispatchers.Default)
            OfflineAccountFlush.disk = StoreDisk(bytes)
            OfflineAccountFlush.online = { false }
            OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchedMark, 1, "movie:disk", 1L, 1L))
            val fresh = StoreDisk(bytes)
            val document = Json { ignoreUnknownKeys = true; encodeDefaults = true }
                .decodeFromString(OfflineOutboxDocument.serializer(), requireNotNull(fresh.read()))
            assertEquals(
                "movie:disk",
                offlineOutboxFromDocument(document).entries.single().subjectKey,
            )
        } finally {
            OfflineAccountFlush.resetForTest()
        }
    }

    @Test
    fun oneFailingEntryDoesNotFreezeTheDrain() = runBlocking {
        OfflineAccountFlush.resetForTest()
        val sent = mutableListOf<String>()
        try {
            OfflineAccountFlush.disk = MemoryOfflineOutboxDisk()
            OfflineAccountFlush.suspendDelay = { }
            OfflineAccountFlush.accountReady = { true }
            OfflineAccountFlush.playbackActive = { false }
            OfflineAccountFlush.seedStoredQueues = false
            OfflineAccountFlush.online = { true }
            OfflineAccountFlush.transport = object : OfflineAccountTransport {
                override suspend fun send(entry: OfflineOutboxEntry): Boolean {
                    if (entry.subjectKey == "A") return false
                    sent += entry.subjectKey
                    return true
                }
            }
            OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchedMark, 1, "A", 1L, 0L))
            OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchedMark, 1, "B", 2L, 1L))
            OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchedMark, 1, "C", 3L, 1L))
            OfflineAccountFlush.onOfflineToOnlineEdge()
            OfflineAccountFlush.awaitIdle()
            assertEquals(listOf("B", "C"), sent)
            val remaining = OfflineAccountFlush.snapshot().entries.single()
            assertEquals("A", remaining.subjectKey)
            assertEquals(OfflineOutboxEntryState.HeldForNextEdge, remaining.state)
            assertEquals(OfflineFlushMaxAttempts, remaining.attempts)
        } finally {
            OfflineAccountFlush.resetForTest()
        }
    }

    @Test
    fun unknownEnumKeepsTheOtherEntriesAndTheRawCopy() {
        val raw = outboxDocument(
            entryJson(kind = "WatchedMark", subject = "movie:kept"),
            entryJson(kind = "RetiredKind", subject = "movie:old"),
            entryJson(kind = "LibraryEdit", subject = "movie:also"),
        )
        val disk = MemoryOfflineOutboxDisk()
        loadOutbox(disk, raw)
        assertEquals(listOf("movie:kept", "movie:also"), diskSubjects())
        assertEquals(raw, disk.bad)
        assertEquals(raw, disk.payload)
        assertEquals(1, disk.badWrites)
        assertTrue(outboxLogLines().any { it.contains("outbox entry decode failed") })

        loadOutbox(disk, raw)
        assertEquals(1, disk.badWrites)
        assertEquals(raw, disk.bad)
    }

    @Test
    fun transportThrowKeepsTheEntryQueuedAndWritesALogLine() = runBlocking {
        OfflineAccountFlush.resetForTest()
        InAppLogger.clear()
        try {
            OfflineAccountFlush.disk = MemoryOfflineOutboxDisk()
            OfflineAccountFlush.suspendDelay = { }
            OfflineAccountFlush.accountReady = { true }
            OfflineAccountFlush.playbackActive = { false }
            OfflineAccountFlush.seedStoredQueues = false
            OfflineAccountFlush.online = { true }
            OfflineAccountFlush.transport = object : OfflineAccountTransport {
                override suspend fun send(entry: OfflineOutboxEntry): Boolean {
                    throw IllegalStateException("transport down")
                }
            }
            OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchedMark, 1, "movie:queued", 1L, 1L))
            OfflineAccountFlush.onOfflineToOnlineEdge()
            OfflineAccountFlush.awaitIdle()
            val remaining = OfflineAccountFlush.snapshot().entries.single()
            assertEquals("movie:queued", remaining.subjectKey)
            assertEquals(OfflineOutboxEntryState.HeldForNextEdge, remaining.state)
            assertEquals(OfflineFlushMaxAttempts, remaining.attempts)
            assertTrue(
                outboxLogLines().any {
                    it.contains("send failed") &&
                        it.contains("IllegalStateException: transport down") &&
                        !it.contains("movie:queued")
                },
            )
            assertTrue(outboxLogLines().any { it.contains("held after max retries") })
        } finally {
            OfflineAccountFlush.resetForTest()
            InAppLogger.clear()
        }
    }

    @Test
    fun corruptOutboxTextStaysOnDiskAndDoesNotCrash() {
        val disk = MemoryOfflineOutboxDisk()
        val first = "%%%not-json"
        loadOutbox(disk, first)
        assertTrue(OfflineAccountFlush.snapshot().entries.isEmpty())
        assertEquals(first, disk.payload)
        assertEquals(first, disk.bad)
        assertEquals(1, disk.badWrites)
        assertTrue(outboxLogLines().any { it.contains("outbox decode failed") })

        loadOutbox(disk, first)
        assertEquals(1, disk.badWrites)

        val second = "{{{still-not"
        loadOutbox(disk, second)
        assertEquals(second, disk.bad)
        assertEquals(2, disk.badWrites)
        assertTrue(OfflineAccountFlush.snapshot().entries.isEmpty())
    }

    @Test
    fun healthyOutboxIsUnchanged() {
        val raw = """{"entries":[${entryJson(kind = "WatchedMark", subject = "movie:disk", timestamp = 10L, value = 4L)}],"nextSequence":3}"""
        val disk = MemoryOfflineOutboxDisk().also { it.bad = "leave-me" }
        loadOutbox(disk, raw)
        val entry = OfflineAccountFlush.snapshot().entries.single()
        assertEquals("movie:disk", entry.subjectKey)
        assertEquals(4L, entry.valueToken)
        assertEquals(OfflineOutboxEntryState.Pending, entry.state)
        assertEquals(3L, OfflineAccountFlush.snapshot().nextSequence)
        assertEquals(raw, disk.payload)
        assertEquals("leave-me", disk.bad)
        assertEquals(0, disk.badWrites)
        assertTrue(outboxLogLines().none { it.contains("decode failed") || it.contains("send failed") })

        OfflineAccountFlush.resetForTest()
        InAppLogger.clear()
        val stored = MemoryOfflineOutboxDisk()
        OfflineAccountFlush.disk = stored
        OfflineAccountFlush.online = { false }
        OfflineAccountFlush.note(sample(OfflineOutboxKind.WatchProgress, 2, "show_s1e2", 9L, 40L))
        val saved = stored.payload
        OfflineAccountFlush.reloadFromDiskForTest()
        assertEquals(saved, stored.payload)
        assertEquals(null, stored.bad)
        assertEquals(0, stored.badWrites)
        assertEquals("show_s1e2", OfflineAccountFlush.snapshot().entries.single().subjectKey)
        assertEquals(40L, OfflineAccountFlush.snapshot().entries.single().valueToken)
        assertTrue(outboxLogLines().none { it.contains("decode failed") })
        OfflineAccountFlush.resetForTest()
        InAppLogger.clear()
    }
}

private enum class OutboxEvent {
    OnlineEdge,
    SecondEdgeDuringFlush,
    CrashMidFlush,
    ServerError,
    OfflineAgainMidFlush,
}

private data class Simulation(
    val subject: String,
    val sentIds: List<String>,
    val sentValue: Long,
    val remaining: List<OfflineOutboxEntry>,
    val run: OfflineFlushRunState,
    val confirmedGone: Boolean,
)

private fun simulate(kind: OfflineOutboxKind, event: OutboxEvent): Simulation {
    val subject = "title:$kind"
    var box = enqueue(OfflineOutbox(), kind, profileId = 1, subject = subject, timestamp = 10L, value = 10L)
    if (kind == OfflineOutboxKind.WatchProgress) {
        box = enqueue(box, kind, profileId = 1, subject = subject, timestamp = 20L, value = 90L)
    }
    val id = box.entries.single().id
    val sent = mutableListOf<String>()
    var sentValue = 0L
    var confirmedGone = false
    when (event) {
        OutboxEvent.OnlineEdge -> {
            box = reduceOnlineEdge(box)
            val planned = claimFlushBatch(box, playbackActive = false)
            box = planned.first
            planned.second.forEach { entry ->
                sent += entry.id
                sentValue = entry.valueToken
                box = dropConfirmed(noteSendSucceeded(box, entry.id, entry.sendingTimestampEpochMs))
            }
        }
        OutboxEvent.SecondEdgeDuringFlush -> {
            val planned = claimFlushBatch(box, playbackActive = false)
            box = planned.first
            planned.second.forEach { entry -> sent += entry.id }
            box = reduceOnlineEdge(box)
        }
        OutboxEvent.CrashMidFlush -> {
            val planned = claimFlushBatch(box, playbackActive = false)
            val recovered = recoverOfflineOutbox(planned.first)
            box = recovered
            val confirmed = noteSendSucceeded(planned.first, id, planned.second.single().sendingTimestampEpochMs)
            confirmedGone = recoverOfflineOutbox(confirmed).entries.none { it.id == id }
        }
        OutboxEvent.ServerError -> {
            val planned = claimFlushBatch(box, playbackActive = false)
            box = planned.first
            repeat(OfflineFlushMaxAttempts) {
                val current = box.entries.first { it.id == id }
                if (current.state != OfflineOutboxEntryState.Sending) {
                    val again = claimOne(box, id)
                    box = again.first
                }
                box = noteSendFailed(box, id)
            }
        }
        OutboxEvent.OfflineAgainMidFlush -> {
            val planned = claimFlushBatch(box, playbackActive = false)
            box = noteOfflineDuringFlush(planned.first)
        }
    }
    return Simulation(
        subject = subject,
        sentIds = sent,
        sentValue = sentValue,
        remaining = box.entries.filter { it.subjectKey == subject && it.profileId == 1 },
        run = box.run,
        confirmedGone = confirmedGone,
    )
}

private fun enqueueAllKinds(profileId: Int): OfflineOutbox {
    var box = enqueue(OfflineOutbox(), OfflineOutboxKind.LibraryEdit, profileId, "movie:z", 3L)
    box = enqueue(box, OfflineOutboxKind.WatchProgress, profileId, "show_s1e1", 2L, 15L)
    box = enqueue(box, OfflineOutboxKind.WatchedMark, profileId, "series:show:1:1", 1L)
    return box
}

private fun enqueue(
    outbox: OfflineOutbox,
    kind: OfflineOutboxKind,
    profileId: Int,
    subject: String,
    timestamp: Long,
    value: Long = 1L,
): OfflineOutbox =
    enqueueOfflineOutbox(outbox, sample(kind, profileId, subject, timestamp, value))

private fun sample(
    kind: OfflineOutboxKind,
    profileId: Int,
    subject: String,
    timestamp: Long,
    value: Long,
): OfflineOutboxEntry =
    OfflineOutboxEntry(
        id = offlineOutboxId(profileId, kind, subject),
        profileId = profileId,
        kind = kind,
        subjectKey = subject,
        timestampEpochMs = timestamp,
        valueToken = value,
    )

private fun loadOutbox(disk: MemoryOfflineOutboxDisk, raw: String) {
    OfflineAccountFlush.resetForTest()
    InAppLogger.clear()
    disk.payload = raw
    OfflineAccountFlush.disk = disk
    OfflineAccountFlush.seedStoredQueues = false
    OfflineAccountFlush.reloadFromDiskForTest()
}

private fun diskSubjects(): List<String> =
    OfflineAccountFlush.snapshot().entries.map { it.subjectKey }

private fun outboxLogLines(): List<String> =
    InAppLogger.entries.value.filter { it.tag == "Sync/OfflineOutbox" }.map { it.message }

private fun entryJson(
    kind: String,
    subject: String,
    timestamp: Long = 10L,
    value: Long = 1L,
): String =
    """{"id":"1|$kind|$subject","profileId":1,"kind":"$kind","subjectKey":"$subject","timestampEpochMs":$timestamp,"valueToken":$value}"""

private fun outboxDocument(vararg entries: String): String =
    """{"entries":[${entries.joinToString(",")}],"nextSequence":4}"""

private class MemoryOfflineOutboxDisk : OfflineOutboxDisk {
    var payload: String? = null
    var writes: Int = 0
    var bad: String? = null
    var badWrites: Int = 0

    override fun read(): String? = payload

    override fun write(payload: String) {
        writes += 1
        this.payload = payload
    }

    override fun readBad(): String? = bad

    override fun writeBad(payload: String) {
        badWrites += 1
        bad = payload
    }
}

private class SharedOutboxBytes {
    var payload: String? = null
}

private class StoreDisk(private val bytes: SharedOutboxBytes) : OfflineOutboxDisk {
    override fun read(): String? = bytes.payload

    override fun write(payload: String) {
        bytes.payload = payload
    }
}
