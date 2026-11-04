package com.nuvio.app.features.analysis

/**
 * The one call that hands downloads to on-device analysis.
 * Analysis v2 should replace this body and leave the caller as it is.
 *
 * Returns false when analysis cannot run, so the caller can drop a temporary file.
 */
internal fun requestAnalysisForMarkedDownloads(downloadIds: List<String>): Boolean {
    if (downloadIds.isEmpty()) return false
    if (!MediaAnalysisScheduler.acceptsWork()) return false
    MediaAnalysisScheduler.kick()
    return true
}
