package com.nuvio.app.features.diagnostics

expect object DiagnosticsLogger {
    fun logColdLaunchTime(durationSeconds: Double)
    fun logNetworkError(statusCode: Int, url: String)
    fun logError(message: String)
    fun logPlaybackStall(durationSeconds: Double)
    fun isEnabled(): Boolean
}
