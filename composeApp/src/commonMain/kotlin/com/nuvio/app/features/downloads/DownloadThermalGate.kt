package com.nuvio.app.features.downloads

/**
 * Critical iOS thermal state delays only the next file.
 * Android has no equivalent gate and never blocks a free slot.
 */
internal expect object DownloadThermalGate {
    fun blocksNextFile(): Boolean

    fun startObserving(onChange: () -> Unit)
}
