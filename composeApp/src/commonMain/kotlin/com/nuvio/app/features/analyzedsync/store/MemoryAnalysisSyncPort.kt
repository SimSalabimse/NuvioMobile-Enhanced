package com.nuvio.app.features.analyzedsync.store

import com.nuvio.app.features.analyzedsync.record.SyncRecord

/** Holds records in memory. Android uses this until it stores analysis. Tests use their own instance. */
internal class MemoryAnalysisSyncPort : AnalysisSyncPort {
    private val records = mutableListOf<SyncRecord>()

    override fun readLocal(): List<LocalRecord> = records.map { LocalRecord(it, needsStamp = false) }

    override fun replaceRecords(records: List<SyncRecord>, nowMs: Long) {
        this.records.clear()
        this.records += records
    }
}
