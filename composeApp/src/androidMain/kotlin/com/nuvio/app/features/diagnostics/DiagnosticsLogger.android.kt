package com.nuvio.app.features.diagnostics

actual object DiagnosticsLogger {
    actual fun logColdLaunchTime(durationSeconds: Double) {
        // No-op on Android
    }
    
    actual fun logNetworkError(statusCode: Int, url: String) {
        // No-op on Android
    }
    
    actual fun logError(message: String) {
        // No-op on Android
    }
    
    actual fun logPlaybackStall(durationSeconds: Double) {
        // No-op on Android
    }
    
    actual fun isEnabled(): Boolean {
        return false
    }
}
