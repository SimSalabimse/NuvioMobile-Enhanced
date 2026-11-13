package com.nuvio.app.features.analyzedsync.store

import com.nuvio.app.features.analyzedsync.record.SyncRecord

/** A local record that still needs a hybrid-clock stamp before it can be merged or uploaded. */
internal data class LocalRecord(val record: SyncRecord, val needsStamp: Boolean)

/**
 * The analysis store the sync engine reads and writes.
 * iOS implements this over the existing per-episode JSON. Android keeps an in-memory seam
 * until it has an analysis store of its own.
 */
internal interface AnalysisSyncPort {
    fun readLocal(): List<LocalRecord>
    fun replaceRecords(records: List<SyncRecord>, nowMs: Long)
}

internal expect fun analysisSyncPort(): AnalysisSyncPort
