package com.nuvio.app.features.diagnostics

import com.nuvio.app.features.profiles.ProfilePinCrypto

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
        val half = maxBytes / 2
        if (active.encodeToByteArray().size + next.encodeToByteArray().size > half) {
            previous = active
            active = ""
        }
        active += next
        if (previous.encodeToByteArray().size + active.encodeToByteArray().size > maxBytes) {
            previous = ""
        }
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

internal fun behaviourEventLine(kind: String, fields: Map<String, String>): String {
    val body = fields.entries.joinToString(",") { (key, value) ->
        "\"${jsonToken(key)}\":\"${jsonToken(value)}\""
    }
    return "{\"kind\":\"${jsonToken(kind)}\",$body}"
}

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

internal expect object BehaviourLogFiles {
    fun write(active: String, previous: String)
    fun bearerToken(): String?
    fun installSalt(): String
}

internal expect class BehaviourLock() {
    fun <T> withLock(block: () -> T): T
}

internal object BehaviourEvents {
    private val ring = BehaviourRing()
    private val lock = BehaviourLock()
    private var pending = 0

    fun appendRaw(line: String) = lock.withLock {
        ring.append(line)
        pending += 1
        if (pending >= BEHAVIOUR_FLUSH_EVENTS) flushLocked()
    }

    fun record(kind: String, fields: Map<String, String> = emptyMap()) = lock.withLock {
        ring.append(behaviourEventLine(kind, fields))
        pending += 1
        if (pending >= BEHAVIOUR_FLUSH_EVENTS) flushLocked()
    }

    fun mark(note: String?) {
        val clean = behaviourNote(note)
        val fields = if (clean.isEmpty()) emptyMap() else mapOf("note" to clean)
        record("marker", fields)
        flush()
    }

    fun flush() = lock.withLock { flushLocked() }

    fun snapshot(): String = lock.withLock { ring.previous + ring.active }

    fun clear() = lock.withLock {
        ring.clear()
        pending = 0
        BehaviourLogFiles.write("", "")
    }

    private fun flushLocked() {
        pending = 0
        BehaviourLogFiles.write(ring.active, ring.previous)
    }
}
