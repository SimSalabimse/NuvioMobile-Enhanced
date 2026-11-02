package com.nuvio.app.core.sync

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.perf.PlaybackQuietMode
import com.nuvio.app.core.startup.ConnectivityRegainDebounceMs
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.libraryItemKey
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildWatchProgressKey
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal const val OfflineFlushDebounceMs = ConnectivityRegainDebounceMs

internal val defaultOfflineOutboxDiskDispatcher: CoroutineDispatcher = Dispatchers.Default

internal fun accountSyncIsOffline(): Boolean =
    NetworkStatusRepository.uiState.value.isOfflineLike

internal interface OfflineOutboxDisk {
    fun read(): String?
    fun write(payload: String)
}

internal fun interface OfflineAccountTransport {
    suspend fun send(entry: OfflineOutboxEntry): Boolean
}

internal object RepositoryOfflineAccountTransport : OfflineAccountTransport {
    override suspend fun send(entry: OfflineOutboxEntry): Boolean = when (entry.kind) {
        OfflineOutboxKind.WatchedMark -> WatchedRepository.deliverOutbox(entry)
        OfflineOutboxKind.WatchProgress -> WatchProgressRepository.deliverOutbox(entry)
        OfflineOutboxKind.LibraryEdit -> LibraryRepository.deliverOutbox(entry)
    }
}

internal fun watchedOutboxEntry(
    profileId: Int,
    item: WatchedItem,
    played: Boolean,
    mirrorTrackers: Boolean,
    source: WatchProgressSource,
): OfflineOutboxEntry {
    val subjectKey = watchedItemKey(item.type, item.id, item.season, item.episode)
    return OfflineOutboxEntry(
        id = offlineOutboxId(profileId, OfflineOutboxKind.WatchedMark, subjectKey),
        profileId = profileId,
        kind = OfflineOutboxKind.WatchedMark,
        subjectKey = subjectKey,
        timestampEpochMs = item.markedAtEpochMs,
        valueToken = if (played) 1L else 0L,
        played = played,
        mirrorTrackers = mirrorTrackers,
        sourceName = source.name,
        watchedItem = item,
    )
}

internal fun progressOutboxEntry(profileId: Int, entry: WatchProgressEntry): OfflineOutboxEntry {
    val subjectKey = buildWatchProgressKey(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
    return OfflineOutboxEntry(
        id = offlineOutboxId(profileId, OfflineOutboxKind.WatchProgress, subjectKey),
        profileId = profileId,
        kind = OfflineOutboxKind.WatchProgress,
        subjectKey = subjectKey,
        timestampEpochMs = entry.lastUpdatedEpochMs,
        valueToken = entry.lastPositionMs,
        progressEntry = entry,
    )
}

internal fun libraryOutboxEntry(
    profileId: Int,
    item: LibraryItem,
    adding: Boolean,
): OfflineOutboxEntry {
    val subjectKey = libraryItemKey(item.id, item.type)
    return OfflineOutboxEntry(
        id = offlineOutboxId(profileId, OfflineOutboxKind.LibraryEdit, subjectKey),
        profileId = profileId,
        kind = OfflineOutboxKind.LibraryEdit,
        subjectKey = subjectKey,
        timestampEpochMs = item.savedAtEpochMs,
        valueToken = if (adding) 1L else 0L,
        adding = adding,
        libraryItem = item,
    )
}

internal object OfflineAccountFlush {
    private val lock = SynchronizedObject()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val watchJob = SupervisorJob()
    private val watchScope = CoroutineScope(watchJob + Dispatchers.Default)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var outbox = OfflineOutbox()
    private var loaded = false
    private val suppressedIds = mutableSetOf<String>()
    private var flushRunning = false
    private var playbackWatchStarted = false
    private var debounceJob: Job? = null

    internal var diskDispatcher: CoroutineDispatcher = defaultOfflineOutboxDiskDispatcher
    internal var suspendDelay: suspend (Long) -> Unit = { delay(it) }
    internal var online: () -> Boolean = { NetworkStatusRepository.uiState.value.isOnline }
    internal var playbackActive: () -> Boolean = { PlaybackQuietMode.playerOpen.value }
    internal var accountReady: () -> Boolean = { signedInAccount() }
    internal var disk: OfflineOutboxDisk = PlatformOfflineOutboxDisk
    internal var transport: OfflineAccountTransport = RepositoryOfflineAccountTransport
    internal var seedStoredQueues: Boolean = true

    fun onOfflineToOnlineEdge() {
        ensurePlaybackWatch()
        if (!scheduleEdge()) return
        debounceJob = scope.launch {
            suspendDelay(OfflineFlushDebounceMs)
            drain()
        }
    }

    fun onSessionReady() {
        ensurePlaybackWatch()
        if (!online()) return
        if (!scheduleEdge()) return
        debounceJob = scope.launch {
            suspendDelay(OfflineFlushDebounceMs)
            drain()
        }
    }

    fun onPlaybackClosed() {
        scope.launch {
            if (playbackActive()) return@launch
            val resume = synchronized(lock) {
                if (outbox.entries.none { it.state == OfflineOutboxEntryState.DeferredWhilePlaying }) {
                    false
                } else {
                    outbox = resumeAfterPlayback(outbox)
                    true
                }
            }
            if (resume) drain()
        }
    }

    fun note(incoming: OfflineOutboxEntry) {
        synchronized(lock) {
            outbox = enqueueOfflineOutbox(outbox, incoming)
        }
        persistAsync()
    }

    fun noteRemoteChange(
        profileId: Int,
        kind: OfflineOutboxKind,
        subjectKey: String,
        remoteTimestampEpochMs: Long,
    ): Boolean {
        val removed = synchronized(lock) {
            val before = outbox.entries.size
            outbox = applyRemoteConflict(
                outbox = outbox,
                id = offlineOutboxId(profileId, kind, subjectKey),
                remoteTimestampEpochMs = remoteTimestampEpochMs,
            )
            before != outbox.entries.size
        }
        if (removed) {
            suppressUntilLoaded(offlineOutboxId(profileId, kind, subjectKey))
            persistAsync()
        }
        return removed
    }

    fun drop(id: String) {
        val removed = synchronized(lock) {
            val before = outbox.entries.size
            outbox = outbox.copy(entries = outbox.entries.filterNot { it.id == id })
            if (before != outbox.entries.size) suppressUntilLoaded(id)
            before != outbox.entries.size
        }
        if (removed) persistAsync()
    }

    fun tryClaim(id: String): OfflineOutboxEntry? {
        val claimed = synchronized(lock) {
            val result = claimOne(outbox, id)
            outbox = result.first
            result.second
        }
        if (claimed != null) persistAsync()
        return claimed
    }

    suspend fun confirm(id: String, sentTimestampEpochMs: Long) {
        val confirmed = synchronized(lock) {
            outbox = noteSendSucceeded(outbox, id, sentTimestampEpochMs)
            outbox
        }
        writeSnapshot()
        val superseded = confirmed.entries.any { entry ->
            entry.id == id && entry.state == OfflineOutboxEntryState.Pending
        }
        synchronized(lock) {
            outbox.entries.filter { it.state == OfflineOutboxEntryState.Confirmed }.forEach { entry ->
                suppressUntilLoaded(entry.id)
            }
            outbox = dropConfirmed(outbox)
        }
        writeSnapshot()
        if (superseded && online() && accountReady()) {
            drain()
        }
    }

    fun release(id: String) {
        synchronized(lock) {
            outbox = releaseSending(outbox, id)
        }
        persistAsync()
    }

    fun markFailed(id: String, scheduleRetry: Boolean) {
        val attempt = synchronized(lock) {
            outbox = noteSendFailed(outbox, id)
            outbox.entries.firstOrNull { it.id == id }
        }
        persistAsync()
        val held = attempt?.state == OfflineOutboxEntryState.HeldForNextEdge
        if (scheduleRetry && !held && attempt != null && online() && accountReady()) {
            scope.launch {
                suspendDelay(offlineFlushBackoffMs(attempt.attempts))
                drain()
            }
        }
    }

    fun clear() {
        debounceJob?.cancel()
        synchronized(lock) {
            outbox = OfflineOutbox()
            loaded = true
            suppressedIds.clear()
            flushRunning = false
        }
        persistAsync()
    }

    fun snapshot(): OfflineOutbox = synchronized(lock) { outbox }

    suspend fun persistNow() {
        writeSnapshot()
    }

    suspend fun awaitIdle() {
        while (true) {
            val children = job.children.toList()
            val busy = synchronized(lock) { flushRunning || outbox.run == OfflineFlushRunState.Flushing }
            if (children.isEmpty() && !busy) return
            children.forEach { child -> child.join() }
        }
    }

    fun resetForTest() {
        debounceJob?.cancel()
        job.cancelChildren()
        watchJob.cancelChildren()
        synchronized(lock) {
            outbox = OfflineOutbox()
            loaded = true
            suppressedIds.clear()
            flushRunning = false
            playbackWatchStarted = false
        }
        diskDispatcher = defaultOfflineOutboxDiskDispatcher
        suspendDelay = { delay(it) }
        online = { NetworkStatusRepository.uiState.value.isOnline }
        playbackActive = { PlaybackQuietMode.playerOpen.value }
        accountReady = { signedInAccount() }
        disk = PlatformOfflineOutboxDisk
        transport = RepositoryOfflineAccountTransport
        seedStoredQueues = true
    }

    private fun persistAsync() {
        scope.launch(diskDispatcher) {
            ensureLoaded()
            disk.write(encode(synchronized(lock) { outbox }))
        }
    }

    private suspend fun writeSnapshot() {
        withContext(diskDispatcher) {
            ensureLoaded()
            disk.write(encode(synchronized(lock) { outbox }))
        }
    }

    private fun suppressUntilLoaded(id: String) {
        if (!loaded) suppressedIds += id
    }

    private fun ensureLoaded() {
        if (loaded) return
        val raw = try {
            disk.read()
        } catch (_: Throwable) {
            null
        }
        synchronized(lock) {
            if (loaded) return
            val recovered = if (raw.isNullOrBlank()) {
                OfflineOutbox()
            } else {
                try {
                    recoverOfflineOutbox(
                        offlineOutboxFromDocument(
                            json.decodeFromString(OfflineOutboxDocument.serializer(), raw),
                        ),
                    )
                } catch (_: Throwable) {
                    OfflineOutbox()
                }
            }
            val merged = mergeLoadedOutbox(recovered, outbox)
            outbox = merged.copy(entries = merged.entries.filterNot { it.id in suppressedIds })
            suppressedIds.clear()
            loaded = true
        }
    }

    private fun encode(snapshot: OfflineOutbox): String =
        json.encodeToString(OfflineOutboxDocument.serializer(), offlineOutboxDocument(snapshot))

    private suspend fun drain() {
        val start = synchronized(lock) {
            if (flushRunning) {
                false
            } else {
                flushRunning = true
                true
            }
        }
        if (!start) return
        var stopForOrder = false
        try {
            if (!online() || !accountReady()) {
                synchronized(lock) {
                    outbox = outbox.copy(
                        run = if (!online()) {
                            OfflineFlushRunState.PausedOffline
                        } else {
                            OfflineFlushRunState.Idle
                        },
                    )
                }
                return
            }
            if (seedStoredQueues) {
                WatchedRepository.seedOfflineOutbox()
                WatchProgressRepository.seedOfflineOutbox()
                LibraryRepository.seedOfflineOutbox()
            }
            val playback = playbackActive()
            val batch = synchronized(lock) {
                val planned = claimFlushBatch(outbox, playbackActive = playback)
                outbox = planned.first
                planned.second
            }
            writeSnapshot()
            for (entry in batch) {
                if (!online()) {
                    synchronized(lock) { outbox = noteOfflineDuringFlush(outbox) }
                    return
                }
                var current = entry
                var delivered = false
                while (!delivered) {
                    val ok = try {
                        transport.send(current)
                    } catch (error: CancellationException) {
                        synchronized(lock) { outbox = releaseSending(outbox, current.id) }
                        throw error
                    } catch (_: Throwable) {
                        false
                    }
                    if (!online()) {
                        synchronized(lock) { outbox = noteOfflineDuringFlush(outbox) }
                        return
                    }
                    if (ok) {
                        confirm(current.id, current.sendingTimestampEpochMs)
                        delivered = true
                    } else {
                        val failed = synchronized(lock) {
                            outbox = noteSendFailed(outbox, current.id)
                            outbox.entries.firstOrNull { it.id == current.id }
                        }
                        if (failed == null || failed.state == OfflineOutboxEntryState.HeldForNextEdge) {
                            synchronized(lock) { outbox = releaseAllSending(outbox) }
                            stopForOrder = true
                            return
                        }
                        suspendDelay(offlineFlushBackoffMs(failed.attempts))
                        if (!online()) {
                            synchronized(lock) { outbox = noteOfflineDuringFlush(outbox) }
                            return
                        }
                        val reclaimed = synchronized(lock) {
                            val result = claimOne(outbox, current.id)
                            outbox = result.first
                            result.second
                        }
                        if (reclaimed == null) {
                            delivered = true
                        } else {
                            current = reclaimed
                            writeSnapshot()
                        }
                    }
                }
            }
        } finally {
            val followUp = synchronized(lock) {
                flushRunning = false
                if (outbox.run != OfflineFlushRunState.PausedOffline) {
                    outbox = finishFlushRun(outbox)
                }
                val held = outbox.entries.any { it.state == OfflineOutboxEntryState.HeldForNextEdge }
                val pending = outbox.entries.any { it.state == OfflineOutboxEntryState.Pending }
                !stopForOrder && !held && pending && online() && accountReady() && !playbackActive()
            }
            persistAsync()
            if (followUp) drain()
        }
    }

    private fun scheduleEdge(): Boolean = synchronized(lock) {
        if (flushRunning || outbox.run == OfflineFlushRunState.Flushing) {
            false
        } else {
            debounceJob?.cancel()
            outbox = reduceOnlineEdge(outbox)
            true
        }
    }

    private fun ensurePlaybackWatch() {
        if (playbackWatchStarted) return
        playbackWatchStarted = true
        watchScope.launch {
            PlaybackQuietMode.playerOpen.collect { open ->
                if (!open) onPlaybackClosed()
            }
        }
    }
}

private fun signedInAccount(): Boolean {
    val auth = AuthRepository.state.value
    return auth is AuthState.Authenticated && !auth.isAnonymous
}
