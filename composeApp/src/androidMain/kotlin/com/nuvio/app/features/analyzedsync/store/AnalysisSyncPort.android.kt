package com.nuvio.app.features.analyzedsync.store

/**
 * Android does not store analysis yet. This seam keeps pulled records in memory for the process
 * so the sync engine has somewhere to write. It is not the iOS episode-file adapter.
 */
internal actual fun analysisSyncPort(): AnalysisSyncPort = AndroidAnalysisSyncSeam

internal object AndroidAnalysisSyncSeam : AnalysisSyncPort by MemoryAnalysisSyncPort()
