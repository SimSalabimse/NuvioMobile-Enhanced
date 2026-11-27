package com.nuvio.app.features.analysis

import com.nuvio.app.core.serialization.LenientJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire form of one part of a show print. Version 1 of [PRINT_ALGO].
 * Canonical JSON of one record stays within the sync plaintext cap of 8192 bytes.
 * The mobile upload loop from the open client is not on this branch. This codec is the piece it calls.
 */
internal data class ShowPrintWire(
    val id: String,
    val showId: String,
    val season: Int?,
    val episode: Int?,
    val segmentType: String,
    val markId: String,
    val part: Int,
    val parts: Int,
    val startMs: Long,
    val endMs: Long,
    val originMs: Long,
    val durationMs: Long,
    val stepMs: Int,
    val padMs: Long,
    val frames: String,
    val wallMs: Long,
    val counter: Int,
    val deviceId: String,
    val deleted: Boolean = false,
)

internal data class ShowPrintClock(
    val wallMs: Long,
    val counter: Int,
    val deviceId: String,
)

private val LIVE_PRINT_KEYS = setOf(
    "algo", "deleted", "durationMs", "endMs", "episode", "frames", "hlc", "id", "kind",
    "markId", "originMs", "padMs", "part", "parts", "season", "segmentType", "showId",
    "startMs", "stepMs", "v",
)

private val TOMBSTONE_KEYS = setOf("deleted", "hlc", "id", "kind")

internal fun syncShowId(parentMetaId: String): String? {
    val trimmed = parentMetaId.trim()
    if (trimmed.isEmpty()) return null
    val tmdb = Regex("""^tmdb:([1-9][0-9]{0,9})$""").matchEntire(trimmed)
    if (tmdb != null) return "tmdb:${tmdb.groupValues[1]}"
    val imdb = Regex("""^(?:imdb:|imdb_)?tt([0-9]{1,10})$""").matchEntire(trimmed)
    if (imdb != null) return paddedImdb(imdb.groupValues[1])
    if (Regex("""^raw:[A-Za-z0-9._-]{1,128}$""").matches(trimmed)) return trimmed
    if (Regex("""^[A-Za-z0-9._-]{1,128}$""").matches(trimmed)) return "raw:$trimmed"
    return null
}

private fun paddedImdb(digits: String): String? {
    if (digits.none { it != '0' }) return null
    val bare = digits.trimStart('0')
    val width = maxOf(7, bare.length)
    return "imdb:tt" + bare.padStart(width, '0')
}

internal fun wireSegmentType(local: String): String? = when (local) {
    "intro" -> "intro"
    "recap" -> "recap"
    "outro", "credits" -> "credits"
    "preview" -> "preview"
    else -> null
}

internal fun localSegmentType(wire: String): String? = when (wire) {
    "intro" -> "intro"
    "recap" -> "recap"
    "credits" -> "outro"
    "preview" -> "preview"
    else -> null
}

internal fun printRecordId(showId: String, segmentType: String, markId: String, part: Int): String =
    "print/$showId/$segmentType/$markId/$part"

internal fun chunkPrintFrames(frames: IntArray, partSize: Int = MAX_PRINT_FRAMES_PER_PART): List<IntArray> {
    if (frames.isEmpty() || partSize <= 0) return emptyList()
    val out = ArrayList<IntArray>((frames.size + partSize - 1) / partSize)
    var index = 0
    while (index < frames.size) {
        val end = minOf(index + partSize, frames.size)
        out += frames.copyOfRange(index, end)
        index = end
    }
    return out
}

internal fun StoredShowPrint.toWireParts(clock: ShowPrintClock): List<ShowPrintWire> {
    val showId = syncShowId(showId) ?: return emptyList()
    val segment = wireSegmentType(segmentType) ?: return emptyList()
    val frames = decodeFingerprint(this.frames)
    val chunks = chunkPrintFrames(frames)
    if (chunks.isEmpty() || chunks.size > 99) return emptyList()
    return chunks.mapIndexed { part, chunk ->
        val encoded = encodeFingerprint(chunk)
        ShowPrintWire(
            id = printRecordId(showId, segment, markId, part),
            showId = showId,
            season = season,
            episode = episode,
            segmentType = segment,
            markId = markId,
            part = part,
            parts = chunks.size,
            startMs = startMs,
            endMs = endMs,
            originMs = originMs,
            durationMs = durationMs,
            stepMs = stepMs,
            padMs = padMs,
            frames = encoded,
            wallMs = clock.wallMs,
            counter = clock.counter,
            deviceId = clock.deviceId,
        )
    }.filter { canonicalJson(it.toJsonMap()).encodeToByteArray().size <= MAX_SYNC_PLAINTEXT_BYTES }
}

internal fun ShowPrintWire.toJsonMap(): Map<String, Any?> {
    if (deleted) {
        return linkedMapOf(
            "deleted" to true,
            "hlc" to clockMap(),
            "id" to id,
            "kind" to "print",
        )
    }
    return linkedMapOf(
        "algo" to PRINT_ALGO,
        "deleted" to false,
        "durationMs" to durationMs,
        "endMs" to endMs,
        "episode" to episode,
        "frames" to frames,
        "hlc" to clockMap(),
        "id" to id,
        "kind" to "print",
        "markId" to markId,
        "originMs" to originMs,
        "padMs" to padMs,
        "part" to part,
        "parts" to parts,
        "season" to season,
        "segmentType" to segmentType,
        "showId" to showId,
        "startMs" to startMs,
        "stepMs" to stepMs,
        "v" to PRINT_FORMAT_VERSION,
    )
}

private fun ShowPrintWire.clockMap(): Map<String, Any?> = linkedMapOf(
    "counter" to counter,
    "deviceId" to deviceId,
    "wallMs" to wallMs,
)

internal fun ShowPrintWire.canonical(): String = canonicalJson(toJsonMap())

internal fun tombstonePrint(id: String, clock: ShowPrintClock): ShowPrintWire = ShowPrintWire(
    id = id,
    showId = "",
    season = null,
    episode = null,
    segmentType = "",
    markId = "",
    part = 0,
    parts = 1,
    startMs = 0L,
    endMs = 0L,
    originMs = 0L,
    durationMs = 0L,
    stepMs = PRINT_STEP_MS,
    padMs = 0L,
    frames = "",
    wallMs = clock.wallMs,
    counter = clock.counter,
    deviceId = clock.deviceId,
    deleted = true,
)

internal fun parseShowPrintWire(text: String): ShowPrintWire? {
    val root = runCatching { LenientJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    return parseShowPrintObject(root)
}

internal fun parseShowPrintObject(root: JsonObject): ShowPrintWire? {
    val deleted = root.boolean("deleted") ?: return null
    val kind = root.string("kind") ?: return null
    val id = root.string("id") ?: return null
    if (kind != "print") return null
    val hlc = root["hlc"]?.jsonObject ?: return null
    if (hlc.keys != setOf("counter", "deviceId", "wallMs")) return null
    val wallMs = hlc.long("wallMs") ?: return null
    val counter = hlc.int("counter") ?: return null
    val deviceId = hlc.string("deviceId") ?: return null
    if (wallMs < 0L || counter !in 0..1_000_000 || !DEVICE_ID.matches(deviceId)) return null
    if (deleted) {
        if (root.keys != TOMBSTONE_KEYS) return null
        if (!PRINT_ID.matches(id)) return null
        return tombstonePrint(id, ShowPrintClock(wallMs, counter, deviceId))
    }
    if (root.keys != LIVE_PRINT_KEYS) return null
    if (root.int("v") != PRINT_FORMAT_VERSION) return null
    if (root.string("algo") != PRINT_ALGO) return null
    val showId = root.string("showId") ?: return null
    val segmentType = root.string("segmentType") ?: return null
    val markId = root.string("markId") ?: return null
    val part = root.int("part") ?: return null
    val parts = root.int("parts") ?: return null
    val startMs = root.long("startMs") ?: return null
    val endMs = root.long("endMs") ?: return null
    val originMs = root.long("originMs") ?: return null
    val durationMs = root.long("durationMs") ?: return null
    val stepMs = root.int("stepMs") ?: return null
    val padMs = root.long("padMs") ?: return null
    val frames = root.string("frames") ?: return null
    val season = when (val parsed = root.optionalInt("season")) {
        OptionalInt.Missing -> return null
        OptionalInt.Null -> null
        is OptionalInt.Value -> parsed.n
    }
    val episode = when (val parsed = root.optionalInt("episode")) {
        OptionalInt.Missing -> return null
        OptionalInt.Null -> null
        is OptionalInt.Value -> parsed.n
    }
    if (syncShowId(showId) != showId) return null
    if (wireSegmentType(localSegmentType(segmentType) ?: return null) != segmentType) return null
    if (!MARK_ID.matches(markId) || !markId.startsWith(segmentType)) return null
    if (parts !in 1..99 || part !in 0 until parts) return null
    if (stepMs != PRINT_STEP_MS || padMs !in 0L..10_000L) return null
    if (startMs < 0L || endMs <= startMs || originMs < 0L || durationMs < 0L) return null
    if (season == null && episode != null) return null
    if (season != null && (episode == null || season < 0 || episode < 1)) return null
    val decoded = decodeFingerprint(frames)
    if (decoded.isEmpty() || decoded.size > MAX_PRINT_FRAMES_PER_PART) return null
    if (encodeFingerprint(decoded) != frames) return null
    val expected = printRecordId(showId, segmentType, markId, part)
    if (id != expected) return null
    return ShowPrintWire(
        id = id,
        showId = showId,
        season = season,
        episode = episode,
        segmentType = segmentType,
        markId = markId,
        part = part,
        parts = parts,
        startMs = startMs,
        endMs = endMs,
        originMs = originMs,
        durationMs = durationMs,
        stepMs = stepMs,
        padMs = padMs,
        frames = frames,
        wallMs = wallMs,
        counter = counter,
        deviceId = deviceId,
    )
}

/** Greater clock wins. A winning tombstone drops the id. Equal clocks keep the greater canonical JSON. */
internal fun mergeShowPrintWires(records: List<ShowPrintWire>): List<ShowPrintWire> {
    val winners = linkedMapOf<String, ShowPrintWire>()
    for (record in records) {
        val current = winners[record.id]
        if (current == null || printClockBeats(record, current)) winners[record.id] = record
    }
    return winners.values.filter { !it.deleted }.sortedBy { it.id }
}

private fun printClockBeats(next: ShowPrintWire, current: ShowPrintWire): Boolean {
    if (next.wallMs != current.wallMs) return next.wallMs > current.wallMs
    if (next.counter != current.counter) return next.counter > current.counter
    if (next.deviceId != current.deviceId) return next.deviceId > current.deviceId
    return next.canonical() > current.canonical()
}

internal fun canonicalJson(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> if (value) "true" else "false"
    is Int -> value.toString()
    is Long -> value.toString()
    is String -> jsonString(value)
    is List<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
    is Map<*, *> -> {
        val keys = value.keys.filterIsInstance<String>().sorted()
        keys.joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
            jsonString(key) + ":" + canonicalJson(value[key])
        }
    }
    else -> error("canonical JSON allows only integers, strings, booleans, null, arrays, and objects")
}

private fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (ch.code < 0x20) out.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private sealed interface OptionalInt {
    data object Missing : OptionalInt
    data object Null : OptionalInt
    data class Value(val n: Int) : OptionalInt
}

private fun JsonObject.optionalInt(key: String): OptionalInt {
    val node = this[key] ?: return OptionalInt.Missing
    if (node is JsonNull) return OptionalInt.Null
    val primitive = node as? JsonPrimitive ?: return OptionalInt.Missing
    val number = primitive.intOrNull ?: return OptionalInt.Missing
    return OptionalInt.Value(number)
}

private val DEVICE_ID = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$""")
private val MARK_ID = Regex("""^(intro|recap|credits|preview)(-[1-9][0-9]?)?$""")
private val PRINT_ID = Regex(
    """^print/(tmdb:[1-9][0-9]{0,9}|imdb:tt[0-9]{7,10}|raw:[A-Za-z0-9._-]{1,128})/(intro|recap|credits|preview)/(intro|recap|credits|preview)(-[1-9][0-9]?)?/[0-9]{1,2}$""",
)
