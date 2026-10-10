package com.nuvio.app.features.downloads

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.NSProcessInfoThermalStateDidChangeNotification
import platform.Foundation.thermalState

internal actual object DownloadThermalGate {
    private var observer: Any? = null

    actual fun blocksNextFile(): Boolean =
        NSProcessInfo.processInfo.thermalState == NSProcessInfoThermalState.NSProcessInfoThermalStateCritical

    actual fun startObserving(onChange: () -> Unit) {
        if (observer != null) return
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = NSProcessInfoThermalStateDidChangeNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            onChange()
        }
    }
}
