package com.nuvio.app.features.details

import com.nuvio.app.core.perf.PlaybackQuietMode
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

internal data class OfflineDiskHit(
    val payload: String,
    val savedAtMs: Long,
    val fresh: Boolean,
)

@Serializable
internal data class OfflineDiskFile(
    val schemaVersion: Int,
    val profileKey: String,
    val entries: List<OfflineDiskEntry> = emptyList(),
)

@Serializable
internal data class OfflineDiskEntry(
    val key: String,
    val savedAtMs: Long,
    val touchedAtMs: Long,
    val payload: String,
)

internal object OfflineDetailsWriteGate {
    private val lock = SynchronizedObject()
    private val scrolling = mutableMapOf<String, Boolean>()
    private val pending = ArrayList<() -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flushJob: Job? = null

    fun bindListScrolling(source: String, active: Boolean) {
        synchronized(lock) {
            if (active) {
                scrolling[source] = true
            } else {
                scrolling.remove(source)
            }
        }
    }

    fun writesAllowed(): Boolean {
        if (PlaybackQuietMode.playerOpen.value) return false
        return synchronized(lock) { scrolling.isEmpty() }
    }

    fun defer(block: () -> Unit) {
        synchronized(lock) { pending.add(block) }
        schedule()
    }

    fun clearForTests() {
        synchronized(lock) {
            scrolling.clear()
            pending.clear()
        }
        flushJob?.cancel()
        flushJob = null
    }

    private fun schedule() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(OfflineDetailsCacheLimits.WriteDebounceMs)
            while (!writesAllowed()) {
                delay(OfflineDetailsCacheLimits.WriteDebounceMs)
            }
            val tasks = synchronized(lock) { pending.toList().also { pending.clear() } }
            tasks.forEach { task -> runCatching { task() } }
        }
    }
}

internal class ProfileOfflineDiskCache(
    private val namespace: String,
    private val schemaVersion: Int,
    private val maxEntries: Int,
    private val maxBytes: Int,
    private val ttlMs: Long,
    private val storage: OfflineDiskStorage,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val writeGate: () -> Boolean = { true },
    private val writesImmediate: Boolean = false,
    private val debounceMs: Long = OfflineDetailsCacheLimits.WriteDebounceMs,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()
    private val profiles = mutableMapOf<String, ProfileBucket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flushJob: Job? = null

    fun read(profileKey: String, key: String): OfflineDiskHit? {
        loadProfile(profileKey)
        val touched = synchronized(lock) {
            val bucket = profiles[profileKey] ?: return@synchronized null
            val existing = bucket.entries.remove(key) ?: return@synchronized null
            val updated = existing.copy(touchedAtMs = clock())
            bucket.entries[updated.key] = updated
            bucket.dirty = true
            updated
        } ?: return null
        scheduleWrite()
        return OfflineDiskHit(
            payload = touched.payload,
            savedAtMs = touched.savedAtMs,
            fresh = offlineStampIsFresh(touched.savedAtMs, clock(), ttlMs),
        )
    }

    fun upsert(profileKey: String, key: String, payload: String, savedAtMs: Long) {
        loadProfile(profileKey)
        synchronized(lock) {
            val bucket = profiles.getOrPut(profileKey) { ProfileBucket(loaded = true) }
            bucket.entries.remove(key)
            bucket.entries[key] = DiskEntry(
                key = key,
                payload = payload,
                savedAtMs = savedAtMs,
                touchedAtMs = clock(),
            )
            evict(bucket.entries)
            bucket.dirty = true
        }
        scheduleWrite()
    }

    fun replaceAll(profileKey: String, rows: List<OfflineDiskEntry>) {
        synchronized(lock) {
            val bucket = profiles.getOrPut(profileKey) { ProfileBucket() }
            bucket.loaded = true
            bucket.entries.clear()
            for (row in rows) {
                bucket.entries[row.key] = DiskEntry(
                    key = row.key,
                    payload = row.payload,
                    savedAtMs = row.savedAtMs,
                    touchedAtMs = row.touchedAtMs,
                )
            }
            evict(bucket.entries)
            bucket.dirty = true
        }
        scheduleWrite()
    }

    fun readAll(profileKey: String): List<OfflineDiskEntry> {
        loadProfile(profileKey)
        return synchronized(lock) {
            val bucket = profiles[profileKey] ?: return@synchronized emptyList()
            bucket.entries.values.map { entry ->
                OfflineDiskEntry(
                    key = entry.key,
                    savedAtMs = entry.savedAtMs,
                    touchedAtMs = entry.touchedAtMs,
                    payload = entry.payload,
                )
            }
        }
    }

    fun keys(profileKey: String): List<String> {
        loadProfile(profileKey)
        return synchronized(lock) { profiles[profileKey]?.entries?.keys?.toList().orEmpty() }
    }

    fun flushForTests() {
        if (writeGate()) flushDirty()
    }

    private fun scheduleWrite() {
        if (writesImmediate) {
            if (writeGate()) flushDirty()
            return
        }
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            do {
                delay(debounceMs)
                while (!writeGate()) delay(debounceMs)
                flushDirty()
            } while (synchronized(lock) { profiles.values.any { bucket -> bucket.dirty } })
        }
    }

    private fun flushDirty() {
        val snapshots = synchronized(lock) {
            profiles.mapNotNull { (profileKey, bucket) ->
                if (!bucket.dirty) return@mapNotNull null
                bucket.dirty = false
                profileKey to bucket.entries.values.toList()
            }
        }
        snapshots.forEach { (profileKey, entries) ->
            val text = encode(profileKey, entries)
            val wrote = runCatching { storage.write(pathFor(profileKey), text) }.isSuccess
            if (!wrote) {
                synchronized(lock) { profiles[profileKey]?.dirty = true }
            }
        }
    }

    private fun loadProfile(profileKey: String) {
        val needsLoad = synchronized(lock) {
            val bucket = profiles[profileKey]
            bucket == null || !bucket.loaded
        }
        if (!needsLoad) return
        val raw = runCatching { storage.read(pathFor(profileKey)) }.getOrNull()
        val decoded = decode(raw, profileKey)
        synchronized(lock) {
            val bucket = profiles.getOrPut(profileKey) { ProfileBucket() }
            if (bucket.loaded) return@synchronized
            if (decoded != null) {
                for (entry in decoded) {
                    if (entry.key !in bucket.entries) {
                        bucket.entries[entry.key] = entry
                    }
                }
                evict(bucket.entries)
            }
            bucket.loaded = true
        }
    }

    private fun decode(raw: String?, profileKey: String): List<DiskEntry>? {
        if (raw.isNullOrBlank()) return emptyList()
        val file = runCatching { json.decodeFromString(OfflineDiskFile.serializer(), raw) }.getOrNull()
            ?: return null
        if (file.schemaVersion != schemaVersion) return null
        if (file.profileKey != profileKey) return null
        return file.entries.map { entry ->
            DiskEntry(
                key = entry.key,
                payload = entry.payload,
                savedAtMs = entry.savedAtMs,
                touchedAtMs = entry.touchedAtMs,
            )
        }
    }

    private fun encode(profileKey: String, entries: List<DiskEntry>): String {
        val file = OfflineDiskFile(
            schemaVersion = schemaVersion,
            profileKey = profileKey,
            entries = entries.map { entry ->
                OfflineDiskEntry(
                    key = entry.key,
                    savedAtMs = entry.savedAtMs,
                    touchedAtMs = entry.touchedAtMs,
                    payload = entry.payload,
                )
            },
        )
        return json.encodeToString(OfflineDiskFile.serializer(), file)
    }

    private fun evict(entries: LinkedHashMap<String, DiskEntry>) {
        while (entries.isNotEmpty() && (entries.size > maxEntries || byteSize(entries) > maxBytes)) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
    }

    private fun byteSize(entries: Map<String, DiskEntry>): Int =
        entries.values.sumOf { entry -> entry.payload.length }

    internal fun pathFor(profileKey: String): String {
        val hash = profileKey.hashCode().toUInt().toString(16)
        return "offline/$namespace/${profileKey.length}_$hash.json"
    }

    private class ProfileBucket(
        var loaded: Boolean = false,
    ) {
        val entries = LinkedHashMap<String, DiskEntry>()
        var dirty = false
    }

    private data class DiskEntry(
        val key: String,
        val payload: String,
        val savedAtMs: Long,
        val touchedAtMs: Long,
    )
}

internal fun productionOfflineDiskCache(
    namespace: String,
    maxEntries: Int,
    maxBytes: Int,
    ttlMs: Long,
): ProfileOfflineDiskCache = ProfileOfflineDiskCache(
    namespace = namespace,
    schemaVersion = OfflineDetailsCacheLimits.SchemaVersion,
    maxEntries = maxEntries,
    maxBytes = maxBytes,
    ttlMs = ttlMs,
    storage = OfflineDetailsFileStorage,
    writeGate = { OfflineDetailsWriteGate.writesAllowed() },
)
