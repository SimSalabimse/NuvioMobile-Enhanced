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

/**
 * One MetricKit payload line. A URL, address, path, or file name is dropped whole.
 * The ring stores the returned line, or nothing when this is null.
 */
internal fun behaviourRawLine(line: String): String? {
    val one = line.replace("\n", " ").replace("\r", " ").trim().take(32_000)
    val lower = one.lowercase()
    if (
        one.isEmpty() ||
        lower.contains("http") ||
        one.contains("@") ||
        one.contains("://") ||
        one.contains("/") ||
        one.contains("\\")
    ) {
        return null
    }
    return one
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

internal expect object BehaviourLogFiles {
    fun write(active: String, previous: String)
    fun bearerToken(): String?
    fun installSalt(): String
}

internal expect class BehaviourLock() {
    fun <T> withLock(block: () -> T): T
}

/** iOS hops to a background queue. The caller only enqueues. */
internal expect fun behaviourEnqueue(block: () -> Unit)

/** Waits for queued appends. Callers must not be the main thread on iOS. */
internal expect fun behaviourSync(block: () -> Unit)

internal object BehaviourEvents {
    private val ring = BehaviourRing()
    private val lock = BehaviourLock()
    private var pending = 0

    fun appendRaw(line: String) {
        behaviourEnqueue {
            lock.withLock {
                ring.append(line)
                pending += 1
                if (pending >= BEHAVIOUR_FLUSH_EVENTS) flushLocked()
            }
        }
    }

    fun record(kind: String, fields: Map<String, String> = emptyMap()) {
        behaviourEnqueue {
            lock.withLock {
                ring.append(behaviourEventLine(kind, fields))
                pending += 1
                if (pending >= BEHAVIOUR_FLUSH_EVENTS) flushLocked()
            }
        }
    }

    fun mark(note: String?) {
        val clean = behaviourNote(note)
        val fields = if (clean.isEmpty()) emptyMap() else mapOf("note" to clean)
        behaviourEnqueue {
            lock.withLock {
                ring.append(behaviourEventLine("marker", fields))
                flushLocked()
            }
        }
    }

    fun flush() {
        behaviourSync { lock.withLock { flushLocked() } }
    }

    fun snapshot(): String {
        var value = ""
        behaviourSync { value = lock.withLock { ring.previous + ring.active } }
        return value
    }

    fun clear() {
        behaviourSync {
            lock.withLock {
                ring.clear()
                pending = 0
                BehaviourLogFiles.write("", "")
            }
        }
    }

    private fun flushLocked() {
        pending = 0
        BehaviourLogFiles.write(ring.active, ring.previous)
    }
}
