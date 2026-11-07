package com.nuvio.app.features.analysis

import com.nuvio.app.nativebridge.NuvioDiagnosticsShareAnalysisLog
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual object AnalysisLogShare {
    actual fun share(document: AnalysisExportDocument): Boolean {
        NuvioDiagnosticsShareAnalysisLog(document.text)
        return true
    }
}
