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

    /** Plaintext sync records only. Implementations must refuse a body that carries key material. */
    fun writeBackup(json: String): Boolean = false

    /** Runs [block] without another writer changing the analysis files in the middle. */
    fun <T> exclusive(block: () -> T): T = block()
}

internal expect fun analysisSyncPort(): AnalysisSyncPort
