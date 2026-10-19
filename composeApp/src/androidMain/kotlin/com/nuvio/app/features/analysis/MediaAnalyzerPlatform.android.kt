package com.nuvio.app.features.analysis

/** STUB: no Android analyzer yet (the FFmpeg decode lives in the iOS app). */
internal actual object MediaAnalyzerPlatform {
    actual val supported: Boolean = false
    actual suspend fun analyze(fileUri: String): String? = null
    actual fun cancel() = Unit
    actual fun isCharging(): Boolean = false
    actual fun progress(): Double = -1.0
    actual fun lastError(): String? = null
    actual suspend fun makeThumbnails(fileUri: String, outputPrefix: String, durationMs: Long, intervalMs: Long, tileWidth: Int): String? = null
    actual suspend fun fingerprintClip(url: String, headers: Map<String, String>, startMs: Long, endMs: Long): String? = null
}
