package com.nuvio.app.features.downloads

/**
 * Snapshot of the device's current network transport, used to gate starting a download
 * when the user hasn't opted in to using mobile data for downloads.
 */
internal expect object DownloadNetworkGuard {
    /** True when the current connection is Wi-Fi (or no metered/cellular transport is active). */
    fun isOnWifi(): Boolean

    /**
     * True on a constrained or metered link that is still Wi-Fi (Android Data Saver, metered Wi-Fi).
     * Cellular is reported by [isOnWifi] instead. The iOS reachability watcher has no Low Data Mode bit.
     */
    fun isLowDataOrExpensive(): Boolean
}
