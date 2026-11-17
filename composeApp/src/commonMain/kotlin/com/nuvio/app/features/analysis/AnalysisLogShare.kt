package com.nuvio.app.features.analysis

/**
 * User-initiated share of an already redacted analysis log.
 * Implementations present the platform share sheet and do not upload.
 */
internal expect object AnalysisLogShare {
    fun share(document: AnalysisExportDocument): Boolean
}
