package com.nuvio.app.features.diagnostics

import com.nuvio.app.nativebridge.NuvioDiagnosticsLogColdLaunchTime
import com.nuvio.app.nativebridge.NuvioDiagnosticsLogError
import com.nuvio.app.nativebridge.NuvioDiagnosticsLogNetworkError
import com.nuvio.app.nativebridge.NuvioDiagnosticsLogPlaybackStall
import com.nuvio.app.nativebridge.NuvioDiagnosticsOpenSettings
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
actual object DiagnosticsLogger {
    actual fun logColdLaunchTime(durationSeconds: Double) {
        NuvioDiagnosticsLogColdLaunchTime(durationSeconds)
    }

    actual fun logNetworkError(statusCode: Int, url: String) {
        NuvioDiagnosticsLogNetworkError(statusCode, url)
    }

    actual fun logError(message: String) {
        NuvioDiagnosticsLogError(message)
    }

    actual fun logPlaybackStall(durationSeconds: Double) {
        NuvioDiagnosticsLogPlaybackStall(durationSeconds)
    }

    actual fun isEnabled(): Boolean = true
}

@OptIn(ExperimentalForeignApi::class)
object DiagnosticsNavigation {
    fun openDiagnosticsSettings() {
        NuvioDiagnosticsOpenSettings()
    }
}
