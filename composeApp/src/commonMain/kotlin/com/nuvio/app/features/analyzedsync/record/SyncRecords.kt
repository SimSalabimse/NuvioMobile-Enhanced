package com.nuvio.app.features.analyzedsync.record

import com.nuvio.app.features.analyzedsync.SYNC_MAX_HLC_COUNTER
import com.nuvio.app.features.analyzedsync.SYNC_MAX_SAFE_INTEGER
import com.nuvio.app.features.analyzedsync.SYNC_SEGMENT_TYPES
import com.nuvio.app.features.analyzedsync.clock.Hlc
import com.nuvio.app.features.analyzedsync.clock.compareHlc
import com.nuvio.app.features.analyzedsync.json.JsonValue
import com.nuvio.app.features.analyzedsync.json.canonicalJson
import com.nuvio.app.features.analyzedsync.json.jBool
import com.nuvio.app.features.analyzedsync.json.jNull
import com.nuvio.app.features.analyzedsync.json.jNum
import com.nuvio.app.features.analyzedsync.json.jObj
import com.nuvio.app.features.analyzedsync.json.jStr
import com.nuvio.app.features.analyzedsync.json.objFields

private val DEVICE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val TMDB = Regex("^tmdb:[1-9][0-9]{0,9}$")
private val IMDB = Regex("^imdb:tt[0-9]{1,10}$")
private val RAW = Regex("^raw:[A-Za-z0-9._-]{1,128}$")
private val ALGORITHM = Regex("^nuvio-analysis-([1-9][0-9]{0,8})$")
private val ACCOUNT = Regex("^[0-9a-f]{64}$")
private val REMOTE_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
private val STATUS = Regex("^[A-Za-z0-9 ._-]{1,32}$")
private val MARK_UUID = Regex("^mark/[0-9a-f-]{36}$")
private val EPISODE_KEY = Regex("^(movie|s[0-9]+e[1-9][0-9]*)$")

internal sealed interface SyncRecord {
    val kind: String
    val id: String
    val deleted: Boolean
    val hlc: Hlc
    fun toWire(): JsonValue
}

internal data class TombstoneRecord(
    override val kind: String,
    override val id: String,
    override val hlc: Hlc,
) : SyncRecord {
    override val deleted: Boolean = true
    override fun toWire(): JsonValue = jObj(
        "deleted" to jBool(true),
        "hlc" to hlc.toJson(),
        "id" to jStr(id),
        "kind" to jStr(kind),
    )
}

internal data class MarkRecord(
    override val id: String,
    override val hlc: Hlc,
    val showId: String,
    val season: Long?,
    val episode: Long?,
    val segmentType: String,
    val presence: String,
    val segmentIndex: Int?,
    val startMs: Long?,
    val endMs: Long?,
    val durationMs: Long,
) : SyncRecord {
    override val kind: String = "mark"
    override val deleted: Boolean = false
    override fun toWire(): JsonValue = if (presence == "none") {
        jObj(
            "deleted" to jBool(false),
            "durationMs" to jNum(durationMs),
            "episode" to jsonLong(episode),
            "hlc" to hlc.toJson(),
            "id" to jStr(id),
            "kind" to jStr("mark"),
            "presence" to jStr("none"),
            "season" to jsonLong(season),
            "segmentType" to jStr(segmentType),
            "showId" to jStr(showId),
        )
    } else {
        jObj(
            "deleted" to jBool(false),
            "durationMs" to jNum(durationMs),
            "endMs" to jNum(endMs ?: 0),
            "episode" to jsonLong(episode),
            "hlc" to hlc.toJson(),
            "id" to jStr(id),
            "kind" to jStr("mark"),
            "presence" to jStr("range"),
            "season" to jsonLong(season),
            "segmentIndex" to jNum((segmentIndex ?: 0).toLong()),
            "segmentType" to jStr(segmentType),
            "showId" to jStr(showId),
            "startMs" to jNum(startMs ?: 0),
        )
    }
}

internal data class DetectionRecord(
    override val id: String,
    override val hlc: Hlc,
    val showId: String,
    val season: Long?,
    val episode: Long?,
    val segmentType: String,
    val segmentIndex: Int,
    val outcome: String,
    val startMs: Long?,
    val endMs: Long?,
    val durationMs: Long,
    val algorithm: String,
    val algorithmVersion: Int,
    val releaseId: String?,
) : SyncRecord {
    override val kind: String = "detection"
    override val deleted: Boolean = false
    override fun toWire(): JsonValue {
        val fields = linkedMapOf<String, JsonValue>(
            "algorithm" to jStr(algorithm),
            "deleted" to jBool(false),
            "durationMs" to jNum(durationMs),
            "episode" to jsonLong(episode),
            "hlc" to hlc.toJson(),
            "id" to jStr(id),
            "kind" to jStr("detection"),
            "outcome" to jStr(outcome),
            "season" to jsonLong(season),
            "segmentIndex" to jNum(segmentIndex.toLong()),
            "segmentType" to jStr(segmentType),
            "showId" to jStr(showId),
        )
        if (outcome == "found") {
            fields["startMs"] = jNum(startMs ?: 0)
            fields["endMs"] = jNum(endMs ?: 0)
        }
        if (releaseId != null) fields["releaseId"] = jStr(releaseId)
        return JsonValue.Obj(fields)
    }
}

internal data class SubmissionRecord(
    override val id: String,
    override val hlc: Hlc,
    val accountScope: String,
    val remoteId: String,
    val showId: String,
    val mediaType: String,
    val season: Long?,
    val episode: Long?,
    val segmentType: String,
    val status: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
) : SyncRecord {
    override val kind: String = "submission"
    override val deleted: Boolean = false
    override fun toWire(): JsonValue = jObj(
        "accountScope" to jStr(accountScope),
        "deleted" to jBool(false),
        "durationMs" to jNum(durationMs),
        "endMs" to jNum(endMs),
        "episode" to jsonLong(episode),
        "hlc" to hlc.toJson(),
        "id" to jStr(id),
        "kind" to jStr("submission"),
        "mediaType" to jStr(mediaType),
        "remoteId" to jStr(remoteId),
        "season" to jsonLong(season),
        "segmentType" to jStr(segmentType),
        "showId" to jStr(showId),
        "startMs" to jNum(startMs),
        "status" to jStr(status),
    )
}

internal data class AliasRecord(
    override val id: String,
    override val hlc: Hlc,
    val from: String,
    val to: String,
) : SyncRecord {
    override val kind: String = "alias"
    override val deleted: Boolean = false
    override fun toWire(): JsonValue = jObj(
        "deleted" to jBool(false),
        "from" to jStr(from),
        "hlc" to hlc.toJson(),
        "id" to jStr(id),
        "kind" to jStr("alias"),
        "to" to jStr(to),
    )
}

internal data class ParsedRecord(val record: SyncRecord, val wire: JsonValue)

internal fun Hlc.toJson(): JsonValue = jObj(
    "counter" to jNum(counter.toLong()),
    "deviceId" to jStr(deviceId),
    "wallMs" to jNum(wallMs),
)

internal fun episodeKey(season: Long?, episode: Long?): String? {
    if (season == null && episode == null) return "movie"
    if (season == null || episode == null) return null
    if (!safe(season) || season < 0 || !safe(episode) || episode < 1) return null
    return "s${season}e$episode"
}

internal fun isShowId(value: String): Boolean {
    if (TMDB.matches(value) || RAW.matches(value)) return true
    return IMDB.matches(value) && value.removePrefix("imdb:tt").any { it != '0' }
}

internal fun normShowId(showId: String): String {
    if (showId.startsWith("tmdb:")) return "tmdb:${showId.removePrefix("tmdb:").toLong()}"
    if (showId.startsWith("imdb:tt")) return "imdb:${showId.removePrefix("imdb:tt").toLong()}"
    return showId
}

internal fun detectionId(
    showId: String,
    episodeKey: String,
    segmentType: String,
    segmentIndex: Int,
    durationMs: Long,
    algorithmVersion: Int,
): String = "det/$showId/$episodeKey/$segmentType/$segmentIndex/$durationMs/a$algorithmVersion"

internal fun noneId(showId: String, episodeKey: String, segmentType: String): String =
    "none/$showId/$episodeKey/$segmentType"

internal fun parseRecord(value: JsonValue): ParsedRecord? {
    val fields = value.objFields() ?: return null
    val hlc = parseHlc(fields["hlc"]) ?: return null
    val kind = fields["kind"].str() ?: return null
    val id = fields["id"].str() ?: return null
    val deleted = fields["deleted"].bool() ?: return null
    if (deleted) return parseTombstone(fields, hlc, kind, id)
    return when (kind) {
        "mark" -> parseMark(fields, hlc, id)
        "detection" -> parseDetection(fields, hlc, id)
        "submission" -> parseSubmission(fields, hlc, id)
        "alias" -> parseAlias(fields, hlc, id)
        else -> null
    }
}

internal fun compareParsed(left: ParsedRecord, right: ParsedRecord): Int {
    val clock = compareHlc(left.record.hlc, right.record.hlc)
    if (clock != 0) return clock
    if (left.record.id != right.record.id) return if (left.record.id > right.record.id) 1 else -1
    val leftWire = canonicalJson(left.wire)
    val rightWire = canonicalJson(right.wire)
    if (leftWire == rightWire) return 0
    return if (leftWire > rightWire) 1 else -1
}

private fun parseTombstone(fields: Map<String, JsonValue>, hlc: Hlc, kind: String, id: String): ParsedRecord? {
    if (!keysExact(fields, listOf("deleted", "hlc", "id", "kind"))) return null
    if (kind != "mark" && kind != "detection" && kind != "submission" && kind != "alias") return null
    if (!tombstoneIdOk(kind, id)) return null
    val record = TombstoneRecord(kind, id, hlc)
    return ParsedRecord(record, record.toWire())
}

private fun parseMark(fields: Map<String, JsonValue>, hlc: Hlc, id: String): ParsedRecord? {
    val presence = fields["presence"].str() ?: return null
    if (presence != "range" && presence != "none") return null
    val located = parseLocation(fields) ?: return null
    val segmentType = fields["segmentType"].str() ?: return null
    if (!isSegment(segmentType)) return null
    val duration = fields["durationMs"].long() ?: return null
    if (duration < 0) return null
    if (presence == "none") {
        if (!keysExact(fields, listOf("deleted", "durationMs", "episode", "hlc", "id", "kind", "presence", "season", "segmentType", "showId"))) {
            return null
        }
        val expected = noneId(located.showId, located.episodeKey, segmentType)
        if (id != expected) return null
        val record = MarkRecord(
            id = expected,
            hlc = hlc,
            showId = located.showId,
            season = located.season,
            episode = located.episode,
            segmentType = segmentType,
            presence = "none",
            segmentIndex = null,
            startMs = null,
            endMs = null,
            durationMs = duration,
        )
        return ParsedRecord(record, record.toWire())
    }
    if (!keysExact(fields, listOf("deleted", "durationMs", "endMs", "episode", "hlc", "id", "kind", "presence", "season", "segmentIndex", "segmentType", "showId", "startMs"))) {
        return null
    }
    val range = parseRange(fields["startMs"], fields["endMs"]) ?: return null
    val index = fields["segmentIndex"].index() ?: return null
    if (!id.startsWith("mark/") || !UUID_V4.matches(id.removePrefix("mark/"))) return null
    val record = MarkRecord(
        id = id,
        hlc = hlc,
        showId = located.showId,
        season = located.season,
        episode = located.episode,
        segmentType = segmentType,
        presence = "range",
        segmentIndex = index,
        startMs = range.first,
        endMs = range.second,
        durationMs = duration,
    )
    return ParsedRecord(record, record.toWire())
}

private fun parseDetection(fields: Map<String, JsonValue>, hlc: Hlc, id: String): ParsedRecord? {
    val outcome = fields["outcome"].str() ?: return null
    if (outcome != "found" && outcome != "not_found") return null
    val located = parseLocation(fields) ?: return null
    val algorithm = fields["algorithm"].str() ?: return null
    val version = algorithmVersion(algorithm) ?: return null
    val segmentType = fields["segmentType"].str() ?: return null
    if (!isSegment(segmentType)) return null
    val index = fields["segmentIndex"].index() ?: return null
    val duration = fields["durationMs"].long() ?: return null
    if (duration < 0) return null
    val release = parseRelease(fields)
    if (release.bad) return null
    val required = mutableListOf("algorithm", "deleted", "durationMs", "episode", "hlc", "id", "kind", "outcome", "season", "segmentIndex", "segmentType", "showId")
    if (release.value != null) required += "releaseId"
    if (outcome == "found") required += listOf("startMs", "endMs")
    if (!keysExact(fields, required)) return null
    var startMs: Long? = null
    var endMs: Long? = null
    if (outcome == "found") {
        val range = parseRange(fields["startMs"], fields["endMs"]) ?: return null
        startMs = range.first
        endMs = range.second
    }
    val expected = detectionId(located.showId, located.episodeKey, segmentType, index, duration, version)
    if (id != expected) return null
    val record = DetectionRecord(
        id = expected,
        hlc = hlc,
        showId = located.showId,
        season = located.season,
        episode = located.episode,
        segmentType = segmentType,
        segmentIndex = index,
        outcome = outcome,
        startMs = startMs,
        endMs = endMs,
        durationMs = duration,
        algorithm = algorithm,
        algorithmVersion = version,
        releaseId = release.value,
    )
    return ParsedRecord(record, record.toWire())
}

private fun parseSubmission(fields: Map<String, JsonValue>, hlc: Hlc, id: String): ParsedRecord? {
    if (!keysExact(fields, listOf("accountScope", "deleted", "durationMs", "endMs", "episode", "hlc", "id", "kind", "mediaType", "remoteId", "season", "segmentType", "showId", "startMs", "status"))) {
        return null
    }
    val located = parseLocation(fields) ?: return null
    val segmentType = fields["segmentType"].str() ?: return null
    if (!isSegment(segmentType)) return null
    val account = fields["accountScope"].str() ?: return null
    if (!ACCOUNT.matches(account)) return null
    val remote = fields["remoteId"].str() ?: return null
    if (!REMOTE_ID.matches(remote)) return null
    val status = fields["status"].str() ?: return null
    if (!STATUS.matches(status)) return null
    val mediaType = fields["mediaType"].str() ?: return null
    if (mediaType != "tv" && mediaType != "movie") return null
    if (mediaType == "movie" && located.episodeKey != "movie") return null
    if (mediaType == "tv" && located.episodeKey == "movie") return null
    val start = fields["startMs"].long() ?: return null
    val end = fields["endMs"].long() ?: return null
    val duration = fields["durationMs"].long() ?: return null
    if (start < 0 || duration < 0 || end < start) return null
    val expected = "submission/$account/$remote"
    if (id != expected) return null
    val record = SubmissionRecord(
        id = expected,
        hlc = hlc,
        accountScope = account,
        remoteId = remote,
        showId = located.showId,
        mediaType = mediaType,
        season = located.season,
        episode = located.episode,
        segmentType = segmentType,
        status = status,
        startMs = start,
        endMs = end,
        durationMs = duration,
    )
    return ParsedRecord(record, record.toWire())
}

private fun parseAlias(fields: Map<String, JsonValue>, hlc: Hlc, id: String): ParsedRecord? {
    if (!keysExact(fields, listOf("deleted", "from", "hlc", "id", "kind", "to"))) return null
    val from = fields["from"].str() ?: return null
    val to = fields["to"].str() ?: return null
    if (!isShowId(from) || !isShowId(to) || from == to) return null
    val expected = "alias/$from"
    if (id != expected) return null
    val record = AliasRecord(expected, hlc, from, to)
    return ParsedRecord(record, record.toWire())
}

private data class Located(val showId: String, val season: Long?, val episode: Long?, val episodeKey: String)

private fun parseLocation(fields: Map<String, JsonValue>): Located? {
    val showId = fields["showId"].str() ?: return null
    if (!isShowId(showId)) return null
    val seasonValue = fields["season"] ?: return null
    val episodeValue = fields["episode"] ?: return null
    if (seasonValue is JsonValue.Null && episodeValue is JsonValue.Null) {
        return Located(showId, null, null, "movie")
    }
    val season = seasonValue.long() ?: return null
    val episode = episodeValue.long() ?: return null
    val key = episodeKey(season, episode) ?: return null
    if (key == "movie") return null
    return Located(showId, season, episode, key)
}

private fun parseRange(start: JsonValue?, end: JsonValue?): Pair<Long, Long>? {
    val startMs = start.long() ?: return null
    val endMs = end.long() ?: return null
    if (startMs < 0 || endMs <= startMs) return null
    return startMs to endMs
}

private data class ReleaseParse(val value: String?, val bad: Boolean)

private fun parseRelease(fields: Map<String, JsonValue>): ReleaseParse {
    if (!fields.containsKey("releaseId")) return ReleaseParse(null, false)
    val value = fields["releaseId"].str() ?: return ReleaseParse(null, true)
    if (!isShowId(value)) return ReleaseParse(null, true)
    return ReleaseParse(value, false)
}

private fun parseHlc(value: JsonValue?): Hlc? {
    val fields = value?.objFields() ?: return null
    if (!keysExact(fields, listOf("counter", "deviceId", "wallMs"))) return null
    val wall = fields["wallMs"].long() ?: return null
    if (wall < 0) return null
    val counter = fields["counter"].long() ?: return null
    if (counter < 0 || counter > SYNC_MAX_HLC_COUNTER) return null
    val deviceId = fields["deviceId"].str() ?: return null
    if (!DEVICE_ID.matches(deviceId)) return null
    return Hlc(wall, counter.toInt(), deviceId)
}

private fun algorithmVersion(algorithm: String): Int? {
    val match = ALGORITHM.matchEntire(algorithm) ?: return null
    return match.groupValues[1].toIntOrNull()
}

private fun tombstoneIdOk(kind: String, id: String): Boolean = when (kind) {
    "mark" -> (MARK_UUID.matches(id) && UUID_V4.matches(id.removePrefix("mark/"))) || isNoneId(id)
    "alias" -> id.startsWith("alias/") && isShowId(id.removePrefix("alias/"))
    "submission" -> {
        val parts = id.split("/")
        parts.size == 3 && parts[0] == "submission" && ACCOUNT.matches(parts[1]) && REMOTE_ID.matches(parts[2])
    }
    else -> {
        val parts = id.split("/")
        parts.size == 7 && parts[0] == "det" && isShowId(parts[1]) && EPISODE_KEY.matches(parts[2]) &&
            isSegment(parts[3]) && Regex("^[0-9]{1,2}$").matches(parts[4]) && parts[4].toInt() <= 99 &&
            Regex("^[0-9]+$").matches(parts[5]) && Regex("^a[1-9][0-9]{0,8}$").matches(parts[6])
    }
}

private fun isNoneId(id: String): Boolean {
    val parts = id.split("/")
    if (parts.size != 4 || parts[0] != "none") return false
    return isShowId(parts[1]) && EPISODE_KEY.matches(parts[2]) && isSegment(parts[3])
}

private fun isSegment(value: String?): Boolean = value != null && value in SYNC_SEGMENT_TYPES

private fun keysExact(fields: Map<String, JsonValue>, expected: List<String>): Boolean {
    if (fields.size != expected.size) return false
    val allowed = expected.toSet()
    return fields.keys.all { it in allowed }
}

private fun jsonLong(value: Long?): JsonValue = if (value == null) jNull() else jNum(value)

private fun JsonValue?.str(): String? = (this as? JsonValue.Str)?.value

private fun JsonValue?.bool(): Boolean? = (this as? JsonValue.Bool)?.value

private fun JsonValue?.long(): Long? = (this as? JsonValue.Num)?.value?.takeIf { safe(it) }

private fun JsonValue?.index(): Int? {
    val value = long() ?: return null
    if (value !in 0..99) return null
    return value.toInt()
}

private fun safe(value: Long): Boolean = value in -SYNC_MAX_SAFE_INTEGER..SYNC_MAX_SAFE_INTEGER
