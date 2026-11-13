package com.nuvio.app.features.analyzedsync.plan

import com.nuvio.app.features.analyzedsync.SYNC_MAX_SAFE_INTEGER
import com.nuvio.app.features.analyzedsync.SYNC_TOMBSTONE_KEEP_MS
import com.nuvio.app.features.analyzedsync.json.JsonValue
import com.nuvio.app.features.analyzedsync.merge.MergedState
import com.nuvio.app.features.analyzedsync.merge.mergeRecords
import com.nuvio.app.features.analyzedsync.record.SyncRecord

internal enum class SyncMode { Incremental, FullResync }

internal fun syncMode(lastSuccessfulSyncMs: Long?, nowMs: Long): SyncMode {
    if (lastSuccessfulSyncMs == null) return SyncMode.Incremental
    if (lastSuccessfulSyncMs !in 0..SYNC_MAX_SAFE_INTEGER || nowMs !in 0..SYNC_MAX_SAFE_INTEGER) {
        throw IllegalArgumentException("bad sync clock")
    }
    if (nowMs - lastSuccessfulSyncMs >= SYNC_TOMBSTONE_KEEP_MS) return SyncMode.FullResync
    return SyncMode.Incremental
}

internal fun <T : SyncRecord> recordsToUpload(local: List<T>, lastSuccessfulSyncMs: Long?, nowMs: Long): List<T> {
    if (syncMode(lastSuccessfulSyncMs, nowMs) == SyncMode.Incremental) return local
    return local.filter { it.hlc.wallMs > nowMs - SYNC_TOMBSTONE_KEEP_MS }
}

/** Remote records are the store. Local records older than the keep window are not merged back in. */
internal fun adoptFullResync(remoteRecords: List<JsonValue>, freshLocal: List<JsonValue>, nowMs: Long): MergedState =
    mergeRecords(remoteRecords + freshLocal, nowMs)
