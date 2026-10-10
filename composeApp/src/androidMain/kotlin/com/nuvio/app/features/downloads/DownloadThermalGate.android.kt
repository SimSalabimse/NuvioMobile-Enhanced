package com.nuvio.app.features.downloads

internal actual object DownloadThermalGate {
    actual fun blocksNextFile(): Boolean = false

    actual fun startObserving(onChange: () -> Unit) = Unit
}
