package com.nuvio.app.features.diagnostics

import com.nuvio.app.features.profiles.ProfilePinCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val BEHAVIOUR_LOG_MAX_BYTES = 4 * 1024 * 1024
internal const val BEHAVIOUR_FLUSH_EVENTS = 64
internal const val BEHAVIOUR_UPLOAD_INTERVAL_MS = 6L * 60L * 60L * 1000L
internal const val TELEMETRY_MAX_GZIP_BYTES = 512 * 1024
internal const val TELEMETRY_PRUNE_AFTER_MS = 30L * 24L * 60L * 60L * 1000L

internal fun telemetryPruneBefore(nowMs: Long): Long = nowMs - TELEMETRY_PRUNE_AFTER_MS

/** Two chunks, together at most [maxBytes]. The older chunk is dropped when the newer one fills. */
internal class BehaviourRing(private val maxBytes: Int = BEHAVIOUR_LOG_MAX_BYTES) {
    var previous: String = ""
        private set
    var active: String = ""
        private set

    fun append(line: String) {
        val next = if (line.endsWith("\n")) line else line + "\n"
        if (next.encodeToByteArray().size > maxBytes) return
        val half = (maxBytes / 2).coerceAtLeast(1)
        if (active.encodeToByteArray().size + next.encodeToByteArray().size > half) {
            previous = active
            active = ""
        }
        active += next
        if (previous.encodeToByteArray().size + active.encodeToByteArray().size > maxBytes) {
            previous = ""
        }
    }

    /** Keep what is already on disk, then drop the oldest lines until the cap holds. */
    fun restore(previous: String, active: String) {
        this.previous = ""
        this.active = ""
        (previous + active).lineSequence().filter { it.isNotBlank() }.forEach { append(it) }
    }

    fun bytes(): Int = previous.encodeToByteArray().size + active.encodeToByteArray().size

    fun clear() {
        previous = ""
        active = ""
    }
}

/** SHA-256 of the per-install salt and the id, first 12 hex characters. The plain id is not returned. */
internal fun behaviourContentId(salt: String, id: String): String {
    val digest = ProfilePinCrypto.sha256Hex(salt + "\u0000" + id)
    return digest.take(12)
}

internal const val BEHAVIOUR_POSITION_BUCKET_MS = 30_000L
internal const val BEHAVIOUR_METRIC_MAX_BYTES = 32 * 1024

private val behaviourJson = Json { encodeDefaults = true }

/** One event. Empty fields stay valid JSON. [t] is epoch milliseconds, [seq] restarts each launch. */
internal fun behaviourEventLine(
    kind: String,
    fields: Map<String, String>,
    t: Long,
    seq: Long,
    launch: String,
): String {
    val obj = buildJsonObject {
        put("t", t)
        put("seq", seq)
        put("launch", launch.take(32))
        put("kind", kind.take(32))
        fields.forEach { (key, value) ->
            val name = jsonToken(key).take(32)
            if (name.isEmpty()) return@forEach
            val limit = if (name == "payload") BEHAVIOUR_METRIC_MAX_BYTES else 80
            put(name, value.take(limit))
        }
    }
    return behaviourJson.encodeToString(JsonObject.serializer(), obj)
}

internal fun behaviourPositionBucket(positionMs: Long): Long {
    if (positionMs < 0L) return -1L
    return positionMs / BEHAVIOUR_POSITION_BUCKET_MS
}

internal fun behaviourLaunchId(): String =
    ProfilePinCrypto.secureRandomBytes(8).joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private val behaviourUrl = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>]+""")
private val behaviourPath = Regex("""[^\s"'<>]*[/\\][^\s"'<>]*""")
private val behaviourAddress = Regex("""[^\s"'<>@]+@[^\s"'<>@]+""")

/**
 * One MetricKit payload. Paths become `<path>`, URLs `<url>`, addresses `<addr>`.
 * The rest of the payload stays. Nothing over 32 KB is kept.
 */
internal fun behaviourRawLine(line: String): String? {
    if (line.encodeToByteArray().size > BEHAVIOUR_METRIC_MAX_BYTES) return null
    val one = line.replace("\n", " ").replace("\r", " ").trim()
    if (one.isEmpty()) return null
    val redacted = behaviourAddress.replace(
        behaviourPath.replace(behaviourUrl.replace(one, "<url>"), "<path>"),
        "<addr>",
    )
    return redacted.take(BEHAVIOUR_METRIC_MAX_BYTES).ifEmpty { null }
}

internal data class FeltOffMarker(
    val label: String,
    val value: String,
    val hint: String,
    val motionMillis: Int,
)

/** The marker keeps its name for VoiceOver. Reduce Motion snaps the state change. */
internal fun feltOffMarker(marked: Boolean, reduceMotion: Boolean) = FeltOffMarker(
    label = "Something felt off",
    value = if (marked) "Marked" else "Not marked",
    hint = "Saves a marker on this phone",
    motionMillis = if (reduceMotion) 0 else 160,
)

/** A note is one short line. A URL, address, or path is dropped. */
internal fun behaviourNote(raw: String?): String {
    val line = raw?.lineSequence()?.firstOrNull()?.trim().orEmpty().take(80)
    if (line.isEmpty()) return ""
    val lower = line.lowercase()
    if (lower.contains("http") || lower.contains("@") || line.contains("/") || line.contains("\\")) return ""
    return line
}

internal fun telemetryMayUpload(
    enrolled: Boolean,
    shareEnabled: Boolean,
    onWifi: Boolean,
    expensive: Boolean,
    constrained: Boolean,
    lowPowerMode: Boolean,
    lastUploadMs: Long,
    nowMs: Long,
): Boolean {
    if (!enrolled || !shareEnabled || !onWifi || expensive || constrained || lowPowerMode) return false
    return lastUploadMs <= 0L || nowMs - lastUploadMs >= BEHAVIOUR_UPLOAD_INTERVAL_MS
}

/** Local batches stay until the server answers 2xx. */
internal fun telemetryDeleteLocalBatch(statusCode: Int): Boolean = statusCode in 200..299

private fun jsonToken(value: String): String =
    value.replace("\\", "").replace("\"", "").replace("\n", " ").take(80)

internal interface BehaviourDisk {
    fun read(): Pair<String, String>
    fun write(active: String, previous: String)
    fun readSnapshot(): String
    fun writeSnapshot(text: String)
    fun deleteSnapshot()
}

internal class MemoryBehaviourDisk : BehaviourDisk {
    var previous: String = ""
    var active: String = ""
    var snapshot: String = ""

    override fun read(): Pair<String, String> = previous to active
    override fun write(active: String, previous: String) {
        this.active = active
        this.previous = previous
    }
    override fun readSnapshot(): String = snapshot
    override fun writeSnapshot(text: String) { snapshot = text }
    override fun deleteSnapshot() { snapshot = "" }
}

/**
 * One launch. The ring is loaded from [disk] first, so a later launch appends.
 * [beginUpload] rotates the current text into a snapshot and leaves the live ring for newer events.
 */
internal class BehaviourLogSession(
    private val disk: BehaviourDisk,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    val launchId: String = behaviourLaunchId(),
) {
    private val ring = BehaviourRing()
    private var seq = 0L
    private var pending = 0

    init {
        val (previous, active) = disk.read()
        ring.restore(previous, active)
    }

    fun text(): String = ring.previous + ring.active

    fun record(kind: String, fields: Map<String, String> = emptyMap()) {
        seq += 1
        ring.append(behaviourEventLine(kind, fields, nowMs(), seq, launchId))
        pending += 1
        if (pending >= BEHAVIOUR_FLUSH_EVENTS) flush()
    }

    fun mark(note: String?) {
        val clean = behaviourNote(note)
        val fields = if (clean.isEmpty()) emptyMap() else mapOf("note" to clean)
        seq += 1
        ring.append(behaviourEventLine("marker", fields, nowMs(), seq, launchId))
        flush()
    }

    fun appendPayload(kind: String, payload: String) {
        record(kind, mapOf("payload" to payload))
    }

    fun flush() {
        pending = 0
        disk.write(ring.active, ring.previous)
    }

    /** The batch already waiting, or the live ring rotated out of the way. */
    fun beginUpload(): String {
        val waiting = disk.readSnapshot()
        if (waiting.isNotBlank()) return waiting
        flush()
        val batch = text()
        if (batch.isBlank()) return ""
        disk.writeSnapshot(batch)
        ring.clear()
        pending = 0
        disk.write("", "")
        return batch
    }

    /** A 2xx drops the snapshot only. Events written after [beginUpload] stay. */
    fun completeUpload(status: Int) {
        if (status in 200..299) disk.deleteSnapshot()
    }
}

internal expect object BehaviourLogFiles {
    fun read(): Pair<String, String>
    fun write(active: String, previous: String)
    fun readSnapshot(): String
    fun writeSnapshot(text: String)
    fun deleteSnapshot()
    fun bearerToken(): String?
    fun installSalt(): String
}

internal expect class BehaviourLock() {
    fun <T> withLock(block: () -> T): T
}

/** iOS hops to a background queue. The caller only enqueues. */
internal expect fun behaviourEnqueue(block: () -> Unit)

/**
 * Runs [block] on `tv.nuvio.behaviour.append` and waits until it returns.
 * The main thread may wait. Calling this from that same queue deadlocks.
 */
internal expect fun behaviourSync(block: () -> Unit)

private object AppBehaviourDisk : BehaviourDisk {
    override fun read(): Pair<String, String> = BehaviourLogFiles.read()
    override fun write(active: String, previous: String) = BehaviourLogFiles.write(active, previous)
    override fun readSnapshot(): String = BehaviourLogFiles.readSnapshot()
    override fun writeSnapshot(text: String) = BehaviourLogFiles.writeSnapshot(text)
    override fun deleteSnapshot() = BehaviourLogFiles.deleteSnapshot()
}

internal object BehaviourEvents {
    private val lock = BehaviourLock()
    private var session = BehaviourLogSession(AppBehaviourDisk)

    /** Load the files from the previous launch, then append. Call this before the first event. */
    fun open() {
        behaviourSync {
            lock.withLock {
                session = BehaviourLogSession(AppBehaviourDisk)
            }
        }
    }

    fun appendRaw(line: String) {
        behaviourEnqueue { lock.withLock { session.appendPayload("metric", line) } }
    }

    fun record(kind: String, fields: Map<String, String> = emptyMap()) {
        behaviourEnqueue { lock.withLock { session.record(kind, fields) } }
    }

    /** Background and foreground. The line is on disk before the call returns. */
    fun recordNow(kind: String, fields: Map<String, String> = emptyMap()) {
        behaviourSync {
            lock.withLock {
                session.record(kind, fields)
                session.flush()
            }
        }
    }

    fun mark(note: String?) {
        behaviourSync { lock.withLock { session.mark(note) } }
    }

    fun flush() {
        behaviourSync { lock.withLock { session.flush() } }
    }

    fun snapshot(): String {
        var value = ""
        behaviourSync { value = lock.withLock { session.text() } }
        return value
    }

    fun beginUpload(): String {
        var value = ""
        behaviourSync { value = lock.withLock { session.beginUpload() } }
        return value
    }

    fun completeUpload(status: Int) {
        behaviourSync { lock.withLock { session.completeUpload(status) } }
    }

    fun clear() {
        behaviourSync {
            lock.withLock {
                BehaviourLogFiles.write("", "")
                BehaviourLogFiles.deleteSnapshot()
                session = BehaviourLogSession(AppBehaviourDisk, launchId = session.launchId)
            }
        }
    }
}
