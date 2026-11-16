package com.nuvio.app.features.downloads

/**
 * iOS heat for files that are already queued.
 * Critical starts nothing. Serious still allows the one low-priority slot from [idleLaneDecision].
 * A change re-runs the download gate, including while nothing is playing, so a lower cap
 * suspends the extra tasks. Android has no thermal signal and never blocks a free slot.
 */
internal expect object DownloadThermalGate {
    fun blocksNextFile(): Boolean

    fun startObserving(onChange: () -> Unit)
}
