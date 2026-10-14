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
    fun cancel()
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

    actual fun cancel() {
        NuvioMediaAnalyzerRegistry.bridge?.cancel()
    }

    actual fun isCharging(): Boolean {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val state = device.batteryState
        return state == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            state == UIDeviceBatteryState.UIDeviceBatteryStateFull
    }
}
