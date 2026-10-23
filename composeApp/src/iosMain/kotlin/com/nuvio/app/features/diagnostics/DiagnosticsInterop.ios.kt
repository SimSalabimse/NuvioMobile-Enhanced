package com.nuvio.app.features.diagnostics

actual object DiagnosticsLogger {
    actual fun logColdLaunchTime(durationSeconds: Double) {
        // Called from Swift via DiagnosticsBridge
    }
    
    actual fun logNetworkError(statusCode: Int, url: String) {
        // Called from Swift via DiagnosticsBridge
    }
    
    actual fun logError(message: String) {
        // Called from Swift via DiagnosticsBridge
    }
    
    actual fun logPlaybackStall(durationSeconds: Double) {
        // Called from Swift via DiagnosticsBridge
    }
    
    actual fun isEnabled(): Boolean {
        return true
    }
}

// Navigation is handled via Swift UIKit directly
// When IosDiagnosticsSettingsRow is tapped, it will call
// DiagnosticsBridge.openDiagnosticsSettings() from Swift
object DiagnosticsNavigation {
    fun openDiagnosticsSettings() {
        // This will be wired through MainViewController
    }
}
