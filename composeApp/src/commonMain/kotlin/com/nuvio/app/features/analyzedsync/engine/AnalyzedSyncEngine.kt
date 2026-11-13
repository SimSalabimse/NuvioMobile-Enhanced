package com.nuvio.app.features.analyzedsync.engine

import com.nuvio.app.features.analyzedsync.SYNC_BATCH_LIMIT
import com.nuvio.app.features.analyzedsync.SYNC_FORMAT_VERSION
import com.nuvio.app.features.analyzedsync.SYNC_PULL_LIMIT
import com.nuvio.app.features.analyzedsync.clock.DeviceClock
import com.nuvio.app.features.analyzedsync.clock.receive
import com.nuvio.app.features.analyzedsync.clock.tick
import com.nuvio.app.features.analyzedsync.crypto.RoutingAad
import com.nuvio.app.features.analyzedsync.crypto.SyncCryptoError
import com.nuvio.app.features.analyzedsync.crypto.associatedData
import com.nuvio.app.features.analyzedsync.crypto.decodeBase64
import com.nuvio.app.features.analyzedsync.crypto.decryptBlob
import com.nuvio.app.features.analyzedsync.crypto.encodeBase64
import com.nuvio.app.features.analyzedsync.crypto.encodeBase64Url
import com.nuvio.app.features.analyzedsync.crypto.encryptBlob
import com.nuvio.app.features.analyzedsync.crypto.randomNonce
import com.nuvio.app.features.analyzedsync.crypto.randomUuidV4
import com.nuvio.app.features.analyzedsync.cursor.PullCursorPage
import com.nuvio.app.features.analyzedsync.cursor.advancePullCursor
import com.nuvio.app.features.analyzedsync.json.canonicalJson
import com.nuvio.app.features.analyzedsync.json.toSyncJson
import com.nuvio.app.features.analyzedsync.merge.keepLatest
import com.nuvio.app.features.analyzedsync.merge.mergeRecords
import com.nuvio.app.features.analyzedsync.plan.SyncMode
import com.nuvio.app.features.analyzedsync.plan.recordsToUpload
import com.nuvio.app.features.analyzedsync.plan.syncMode
import com.nuvio.app.features.analyzedsync.record.AliasRecord
import com.nuvio.app.features.analyzedsync.record.DetectionRecord
import com.nuvio.app.features.analyzedsync.record.MarkRecord
import com.nuvio.app.features.analyzedsync.record.SubmissionRecord
import com.nuvio.app.features.analyzedsync.record.SyncRecord
import com.nuvio.app.features.analyzedsync.record.TombstoneRecord
import com.nuvio.app.features.analyzedsync.record.parseRecord
import com.nuvio.app.features.analyzedsync.store.AnalysisSyncPort
import com.nuvio.app.features.analyzedsync.store.LocalRecord
import com.nuvio.app.features.analyzedsync.transport.BlobEnvelope
import com.nuvio.app.features.analyzedsync.transport.PulledBlob
import com.nuvio.app.features.analyzedsync.transport.SyncTransport
import com.nuvio.app.features.analyzedsync.transport.TransportResponse
import com.nuvio.app.features.analyzedsync.transport.honestSyncStatus
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

internal data class SyncConfig(
    val baseUrl: String,
    val spaceId: String,
    val deviceId: String,
    val encKey: ByteArray,
    val authToken: ByteArray,
    val syncMarks: Boolean = true,
    val syncDetections: Boolean = true,
    val syncSubmissions: Boolean = true,
) {
    override fun toString(): String = "SyncConfig(spaceId=$spaceId, deviceId=$deviceId)"
}

internal data class SyncSessionState(
    val pullCursor: Long = 0,
    val lastSuccessfulSyncMs: Long? = null,
    val clockWallMs: Long = 0,
    val clockCounter: Int = 0,
    val seenDevices: Map<String, Long> = emptyMap(),
    val episodeCount: Int = 0,
)

internal data class SyncRun(val state: SyncSessionState, val error: String?)

internal class AnalyzedSyncEngine(
    private val transport: SyncTransport,
    private val port: AnalysisSyncPort,
    private val random: (Int) -> ByteArray,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val onClock: (Long, Int) -> Unit = { _, _ -> },
    private val stillCurrent: () -> Boolean = { true },
) {
    suspend fun sync(config: SyncConfig, state: SyncSessionState, nowMs: Long): SyncRun {
        val mode = syncMode(state.lastSuccessfulSyncMs, nowMs)
        var cursor = if (mode == SyncMode.FullResync) 0L else state.pullCursor
        val remote = mutableListOf<SyncRecord>()
        val seen = state.seenDevices.toMutableMap()
        var clock = DeviceClock(state.clockWallMs, state.clockCounter)
        var pages = 0
        while (pages < 250) {
            pages += 1
            val page = when (val response = rateLimited { transport.pull(config.baseUrl, config.spaceId, bearer(config.authToken), cursor, SYNC_PULL_LIMIT) }) {
                is TransportResponse.Ok -> response.value
                is TransportResponse.Failed -> return SyncRun(state, honestSyncStatus(response))
            }
            val advanced = try {
                advancePullCursor(cursor, PullCursorPage(page.cursor, page.blobs.map { it.seq }))
            } catch (_: Exception) {
                return SyncRun(state, "The server sent a page this phone could not follow.")
            }
            for (blob in page.blobs) {
                seen[blob.deviceId] = maxOf(seen[blob.deviceId] ?: 0L, blob.receivedAtMs)
                val opened = openBlob(config, blob) ?: continue
                if (!kindEnabled(config, opened.kind)) continue
                clock = receive(clock, opened.hlc, nowMs)
                remote += opened
            }
            cursor = advanced
            if (!page.hasMore) break
        }
        if (!stillCurrent()) return SyncRun(state, null)
        var applied = false
        var failure: String? = null
        var uploadable = emptyList<SyncRecord>()
        var episodeCount = 0
        port.exclusive {
            if (!stillCurrent()) return@exclusive
            val local = port.readLocal()
            val active = local.filter { kindEnabled(config, it.record.kind) }
            val held = local.filter { !kindEnabled(config, it.record.kind) }.map { it.record }
            val stamped = try {
                stampLocal(active, clock, nowMs, config.deviceId, random)
            } catch (_: Exception) {
                failure = "This phone is writing faster than its clock. Wait a moment and sync again."
                return@exclusive
            }
            clock = stamped.second
            uploadable = recordsToUpload(stamped.first, state.lastSuccessfulSyncMs, nowMs)
            val mergedInputs = if (mode == SyncMode.FullResync) remote + uploadable else remote + stamped.first
            val kept = keepLatest(mergedInputs, nowMs)
            if (!stillCurrent()) return@exclusive
            onClock(clock.wallMs, clock.counter)
            port.replaceRecords(kept + held, nowMs)
            episodeCount = mergeRecords(kept.map { it.toWire() }, nowMs).shows.sumOf { it.episodes.size }
            applied = true
        }
        if (!applied) {
            if (!stillCurrent()) return SyncRun(state, null)
            return SyncRun(state, failure ?: "Sync failed.")
        }
        val remoteCanonical = remote.map { canonicalJson(it.toWire()) }.toSet()
        val toPush = uploadable.filter { canonicalJson(it.toWire()) !in remoteCanonical }
        for (batch in toPush.chunked(SYNC_BATCH_LIMIT)) {
            val pushed = pushRecords(config, batch)
            if (pushed is TransportResponse.Failed) {
                return SyncRun(
                    state.copy(pullCursor = cursor, clockWallMs = clock.wallMs, clockCounter = clock.counter, seenDevices = seen),
                    honestSyncStatus(pushed),
                )
            }
        }
        return SyncRun(
            SyncSessionState(
                pullCursor = cursor,
                lastSuccessfulSyncMs = nowMs,
                clockWallMs = clock.wallMs,
                clockCounter = clock.counter,
                seenDevices = seen,
                episodeCount = episodeCount,
            ),
            error = null,
        )
    }

    private suspend fun pushRecords(config: SyncConfig, records: List<SyncRecord>): TransportResponse<Unit> {
        var blobs = records.map { seal(config, it, random) }
        var attempt = rateLimited { transport.push(config.baseUrl, config.spaceId, bearer(config.authToken), config.deviceId, blobs) }
        if (attempt is TransportResponse.Failed && attempt.code == "conflict") {
            blobs = records.map { seal(config, it, random) }
            attempt = rateLimited { transport.push(config.baseUrl, config.spaceId, bearer(config.authToken), config.deviceId, blobs) }
        }
        return when (attempt) {
            is TransportResponse.Ok -> TransportResponse.Ok(Unit)
            is TransportResponse.Failed -> attempt
        }
    }

    private suspend fun <T> rateLimited(block: suspend () -> TransportResponse<T>): TransportResponse<T> {
        var attempt = block()
        var tries = 0
        while (attempt is TransportResponse.Failed && attempt.code == "rate_limited" && tries < 3) {
            val seconds = (attempt.retryAfterSeconds ?: 1).coerceIn(1, 600)
            sleep(seconds * 1000L)
            tries += 1
            attempt = block()
        }
        return attempt
    }
}

internal fun bearer(authToken: ByteArray): String = encodeBase64Url(authToken)

private fun kindEnabled(config: SyncConfig, kind: String): Boolean = when (kind) {
    "mark" -> config.syncMarks
    "detection" -> config.syncDetections
    "submission" -> config.syncSubmissions
    "alias" -> true
    else -> false
}

private fun stampLocal(
    records: List<LocalRecord>,
    clock: DeviceClock,
    nowMs: Long,
    deviceId: String,
    random: (Int) -> ByteArray,
): Pair<List<SyncRecord>, DeviceClock> {
    var current = clock
    val stamped = mutableListOf<SyncRecord>()
    for (local in records) {
        if (!local.needsStamp) {
            stamped += local.record
            current = receive(current, local.record.hlc, nowMs)
            continue
        }
        val hlc = tick(current, nowMs, deviceId)
        current = DeviceClock(hlc.wallMs, hlc.counter)
        val rewritten = when (val record = local.record) {
            is MarkRecord -> {
                val id = if (record.presence == "range" && !record.id.startsWith("mark/")) {
                    "mark/${randomUuidV4(random)}"
                } else {
                    record.id
                }
                record.copy(id = id, hlc = hlc)
            }
            is DetectionRecord -> record.copy(hlc = hlc)
            is SubmissionRecord -> record.copy(hlc = hlc)
            is AliasRecord -> record.copy(hlc = hlc)
            is TombstoneRecord -> record.copy(hlc = hlc)
        }
        if (parseRecord(rewritten.toWire()) != null) stamped += rewritten
    }
    return stamped to current
}

private fun seal(config: SyncConfig, record: SyncRecord, random: (Int) -> ByteArray): BlobEnvelope {
    val plain = canonicalJson(record.toWire()).encodeToByteArray()
    val id = randomUuidV4(random)
    val nonce = randomNonce { bytes -> random(bytes.size).copyInto(bytes) }
    val cipher = encryptBlob(
        config.encKey,
        nonce,
        associatedData(RoutingAad(SYNC_FORMAT_VERSION, config.spaceId, id, config.deviceId, record.kind)),
        plain,
    )
    return BlobEnvelope(id, SYNC_FORMAT_VERSION, record.kind, encodeBase64(nonce), encodeBase64(cipher))
}

private fun openBlob(config: SyncConfig, blob: PulledBlob): SyncRecord? {
    if (blob.v != SYNC_FORMAT_VERSION) return null
    val nonce = decodeBase64(blob.nonce) ?: return null
    val cipher = decodeBase64(blob.ciphertext) ?: return null
    val plain = try {
        decryptBlob(
            config.encKey,
            nonce,
            associatedData(RoutingAad(blob.v, config.spaceId, blob.id, blob.deviceId, blob.kind)),
            cipher,
        )
    } catch (_: SyncCryptoError) {
        null
    } ?: return null
    val value = runCatching { Json.parseToJsonElement(plain.decodeToString()).toSyncJson() }.getOrNull() ?: return null
    val parsed = parseRecord(value) ?: return null
    if (parsed.record.kind != blob.kind) return null
    return parsed.record
}
