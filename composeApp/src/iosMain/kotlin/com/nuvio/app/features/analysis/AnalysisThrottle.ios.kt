package com.nuvio.app.features.analysis

import platform.Foundation.NSUserDefaults

/** Read by MediaAnalyzer.swift between packets. */
internal actual fun setAnalysisThrottleLevel(level: Int) {
    NSUserDefaults.standardUserDefaults.setInteger(level.toLong(), forKey = "NuvioAnalysisThrottleLevel")
}
