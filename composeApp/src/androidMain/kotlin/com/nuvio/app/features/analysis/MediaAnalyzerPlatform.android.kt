package com.nuvio.app.features.analysis

/** STUB: no Android analyzer yet (the FFmpeg decode lives in the iOS app). */
internal actual object MediaAnalyzerPlatform {
    actual val supported: Boolean = false
    actual suspend fun analyze(fileUri: String): String? = null
    actual fun cancel() = Unit
    actual fun isCharging(): Boolean = false
}
