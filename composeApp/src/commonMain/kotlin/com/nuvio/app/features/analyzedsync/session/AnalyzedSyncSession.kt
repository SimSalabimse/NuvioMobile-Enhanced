package com.nuvio.app.features.analyzedsync.session

import com.nuvio.app.features.analyzedsync.crypto.decodeBase64
import com.nuvio.app.features.analyzedsync.crypto.joinCode
import com.nuvio.app.features.analyzedsync.engine.SyncConfig
import com.nuvio.app.features.analyzedsync.engine.SyncSessionState
import com.nuvio.app.features.analyzedsync.json.JsonValue
import com.nuvio.app.features.analyzedsync.json.canonicalJson
import com.nuvio.app.features.analyzedsync.json.jBool
import com.nuvio.app.features.analyzedsync.json.jNull
import com.nuvio.app.features.analyzedsync.json.jNum
import com.nuvio.app.features.analyzedsync.json.jObj
import com.nuvio.app.features.analyzedsync.json.jStr
import com.nuvio.app.features.analyzedsync.json.objFields
import com.nuvio.app.features.analyzedsync.json.toSyncJson
import kotlinx.serialization.json.Json

internal const val ANALYZED_SYNC_DEFAULT_URL = "https://nuvio-sync.tullebukktil1000.workers.dev"

/** Metadata for one phone. The passphrase, encryption key, and bearer token are not in here. */
internal data class AnalyzedSyncSession(
    val baseUrl: String,
    val spaceId: String,
    val saltBase64: String,
    val keyCheckBase64: String,
    val deviceId: String,
    val pullCursor: Long = 0,
    val lastSuccessfulSyncMs: Long? = null,
    val clockWallMs: Long = 0,
    val clockCounter: Int = 0,
    val syncMarks: Boolean = true,
    val syncDetections: Boolean = true,
    val syncSubmissions: Boolean = true,
    val seenDevices: Map<String, Long> = emptyMap(),
    val episodeCount: Int = 0,
    val statusText: String = "Not synced yet",
) {
    override fun toString(): String = "AnalyzedSyncSession(spaceId=$spaceId, deviceId=$deviceId)"

    fun toConfig(secret: AnalyzedSyncSecret): SyncConfig = SyncConfig(
        baseUrl = baseUrl,
        spaceId = spaceId,
        deviceId = deviceId,
        encKey = secret.encKey,
        authToken = secret.authToken,
        syncMarks = syncMarks,
        syncDetections = syncDetections,
        syncSubmissions = syncSubmissions,
    )

    fun toState(): SyncSessionState = SyncSessionState(
        pullCursor = pullCursor,
        lastSuccessfulSyncMs = lastSuccessfulSyncMs,
        clockWallMs = clockWallMs,
        clockCounter = clockCounter,
        seenDevices = seenDevices,
        episodeCount = episodeCount,
    )

    fun joinCodeText(): String? {
        val salt = decodeBase64(saltBase64) ?: return null
        val check = decodeBase64(keyCheckBase64) ?: return null
        return joinCode(spaceId, salt, check)
    }
}

internal fun encodeSession(session: AnalyzedSyncSession): String {
    val devices = JsonValue.Obj(session.seenDevices.mapValues { (_, seen) -> jNum(seen) })
    return canonicalJson(
        jObj(
            "baseUrl" to jStr(session.baseUrl),
            "clockCounter" to jNum(session.clockCounter.toLong()),
            "clockWallMs" to jNum(session.clockWallMs),
            "deviceId" to jStr(session.deviceId),
            "episodeCount" to jNum(session.episodeCount.toLong()),
            "keyCheckBase64" to jStr(session.keyCheckBase64),
            "lastSuccessfulSyncMs" to (session.lastSuccessfulSyncMs?.let(::jNum) ?: jNull()),
            "pullCursor" to jNum(session.pullCursor),
            "saltBase64" to jStr(session.saltBase64),
            "seenDevices" to devices,
            "spaceId" to jStr(session.spaceId),
            "statusText" to jStr(session.statusText),
            "syncDetections" to jBool(session.syncDetections),
            "syncMarks" to jBool(session.syncMarks),
            "syncSubmissions" to jBool(session.syncSubmissions),
        ),
    )
}

internal fun decodeSession(text: String): AnalyzedSyncSession? {
    val fields = runCatching { Json.parseToJsonElement(text).toSyncJson() }.getOrNull()?.objFields() ?: return null
    val seen = fields["seenDevices"]?.objFields()?.mapNotNull { (id, value) ->
        val at = (value as? JsonValue.Num)?.value ?: return@mapNotNull null
        id to at
    }?.toMap() ?: emptyMap()
    return AnalyzedSyncSession(
        baseUrl = fields["baseUrl"].text() ?: return null,
        spaceId = fields["spaceId"].text() ?: return null,
        saltBase64 = fields["saltBase64"].text() ?: return null,
        keyCheckBase64 = fields["keyCheckBase64"].text() ?: return null,
        deviceId = fields["deviceId"].text() ?: return null,
        pullCursor = (fields["pullCursor"] as? JsonValue.Num)?.value ?: 0,
        lastSuccessfulSyncMs = (fields["lastSuccessfulSyncMs"] as? JsonValue.Num)?.value,
        clockWallMs = (fields["clockWallMs"] as? JsonValue.Num)?.value ?: 0,
        clockCounter = (fields["clockCounter"] as? JsonValue.Num)?.value?.toInt() ?: 0,
        syncMarks = (fields["syncMarks"] as? JsonValue.Bool)?.value ?: true,
        syncDetections = (fields["syncDetections"] as? JsonValue.Bool)?.value ?: true,
        syncSubmissions = (fields["syncSubmissions"] as? JsonValue.Bool)?.value ?: true,
        seenDevices = seen,
        episodeCount = (fields["episodeCount"] as? JsonValue.Num)?.value?.toInt() ?: 0,
        statusText = fields["statusText"].text() ?: "Not synced yet",
    )
}

private fun JsonValue?.text(): String? = (this as? JsonValue.Str)?.value

internal fun normalizeSyncUrl(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return null
    if (trimmed.length !in 12..200 || ' ' in trimmed) return null
    return trimmed
}

internal fun syncStatusLine(lastSyncMs: Long?, nowMs: Long, episodes: Int): String {
    if (lastSyncMs == null) return "Not synced yet"
    val delta = (nowMs - lastSyncMs).coerceAtLeast(0)
    val whenText = when {
        delta < 60_000L -> "just now"
        delta < 3_600_000L -> "${delta / 60_000L} min ago"
        delta < 86_400_000L -> "${delta / 3_600_000L} h ago"
        else -> "${delta / 86_400_000L} d ago"
    }
    return "Synced $whenText · $episodes episodes"
}
