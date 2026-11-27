package com.nuvio.app.features.analysis

import com.nuvio.app.core.automation.isolateStorageKey
import platform.Foundation.NSUserDefaults

/** Read by MediaAnalyzer.swift between packets. */
internal actual fun setAnalysisThrottleLevel(level: Int) {
    NSUserDefaults.standardUserDefaults.setInteger(
        level.toLong(),
        forKey = isolateStorageKey("NuvioAnalysisThrottleLevel"),
    )
}

/** Read by MediaAnalyzer.swift to choose the analyser thread QoS. */
internal actual fun setAnalysisPlayerSessionOpen(open: Boolean) {
    NSUserDefaults.standardUserDefaults.setBool(open, forKey = isolateStorageKey("NuvioAnalysisPlayerSessionOpen"))
}

/** Read by MediaAnalyzer.swift when it logs a pause. The id only, never a path. */
internal actual fun setAnalysisActiveJob(jobId: String) {
    NSUserDefaults.standardUserDefaults.setObject(jobId, forKey = isolateStorageKey("NuvioAnalysisActiveJob"))
}

/** Read by MediaAnalyzer.swift so a transfer never raises the analyser to userInitiated. */
internal actual fun setAnalysisTransferActive(active: Boolean) {
    NSUserDefaults.standardUserDefaults.setBool(active, forKey = isolateStorageKey("NuvioAnalysisTransferActive"))
}

/** Read by MediaAnalyzer.swift. Fair heat may run once. Serious and critical still stop the decode. */
internal actual fun setAnalysisWarmOverride(on: Boolean) {
    NSUserDefaults.standardUserDefaults.setBool(on, forKey = isolateStorageKey("NuvioAnalysisWarmOverride"))
}
