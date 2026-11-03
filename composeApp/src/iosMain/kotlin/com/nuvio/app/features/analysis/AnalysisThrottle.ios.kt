package com.nuvio.app.features.analysis

import platform.Foundation.NSUserDefaults

/** Read by MediaAnalyzer.swift between packets. */
internal actual fun setAnalysisThrottleLevel(level: Int) {
    NSUserDefaults.standardUserDefaults.setInteger(level.toLong(), forKey = "NuvioAnalysisThrottleLevel")
}

/** Read by MediaAnalyzer.swift to choose the analyser thread QoS. */
internal actual fun setAnalysisPlayerSessionOpen(open: Boolean) {
    NSUserDefaults.standardUserDefaults.setBool(open, forKey = "NuvioAnalysisPlayerSessionOpen")
}
