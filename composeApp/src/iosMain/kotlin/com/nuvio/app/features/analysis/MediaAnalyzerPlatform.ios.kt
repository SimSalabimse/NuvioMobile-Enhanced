package com.nuvio.app.features.analysis

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import kotlin.coroutines.resume

/** Implemented in Swift (MediaAnalyzer.swift) and registered at launch. */
interface NuvioMediaAnalyzerBridge {
    /** Decodes [fileUri] off the main thread; [onDone] gets the JSON result or null. */
    fun analyze(fileUri: String, onDone: (String?) -> Unit)
    /** Thumbnail sheets every [intervalMs] to `<outputPrefix><n>.jpg`; [onDone] gets their layout JSON. */
    fun makeThumbnails(fileUri: String, outputPrefix: String, durationMs: Long, intervalMs: Long, tileWidth: Int, onDone: (String?) -> Unit)
    /** Fingerprints [startMs]..[endMs] of the stream at [url] in memory; [onDone] gets the JSON or null. */
    fun fingerprintClip(url: String, headers: Map<String, String>, startMs: Long, endMs: Long, onDone: (String?) -> Unit)
    fun cancel()
    /** 0..1 for the running call, negative when unknown. */
    fun currentProgress(): Double
    /** Short reason the last call returned null. */
    fun lastFailure(): String?
}

object NuvioMediaAnalyzerRegistry {
    internal var bridge: NuvioMediaAnalyzerBridge? = null

    fun register(analyzer: NuvioMediaAnalyzerBridge) {
        bridge = analyzer
    }
}

internal actual object MediaAnalyzerPlatform {
    actual val supported: Boolean
        get() = NuvioMediaAnalyzerRegistry.bridge != null

    actual suspend fun analyze(fileUri: String): String? {
        val bridge = NuvioMediaAnalyzerRegistry.bridge ?: return null
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { bridge.cancel() }
            bridge.analyze(fileUri) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    actual suspend fun makeThumbnails(fileUri: String, outputPrefix: String, durationMs: Long, intervalMs: Long, tileWidth: Int): String? {
        val bridge = NuvioMediaAnalyzerRegistry.bridge ?: return null
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { bridge.cancel() }
            bridge.makeThumbnails(fileUri, outputPrefix, durationMs, intervalMs, tileWidth) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    actual suspend fun fingerprintClip(url: String, headers: Map<String, String>, startMs: Long, endMs: Long): String? {
        val bridge = NuvioMediaAnalyzerRegistry.bridge ?: return null
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { bridge.cancel() }
            bridge.fingerprintClip(url, headers, startMs, endMs) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    actual fun cancel() {
        NuvioMediaAnalyzerRegistry.bridge?.cancel()
    }

    actual fun progress(): Double = NuvioMediaAnalyzerRegistry.bridge?.currentProgress() ?: -1.0

    actual fun lastError(): String? = NuvioMediaAnalyzerRegistry.bridge?.lastFailure()

    actual fun isCharging(): Boolean {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val state = device.batteryState
        return state == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            state == UIDeviceBatteryState.UIDeviceBatteryStateFull
    }
}
