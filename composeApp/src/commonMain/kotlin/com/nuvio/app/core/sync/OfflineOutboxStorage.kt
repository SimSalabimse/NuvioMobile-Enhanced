package com.nuvio.app.core.sync

internal expect object PlatformOfflineOutboxDisk : OfflineOutboxDisk {
    override fun read(): String?
    override fun write(payload: String)
}

/** Runs [block] off the main thread when called from it, and returns after [block] finishes. */
internal expect fun runOfflineOutboxDiskWrite(block: () -> Unit)
