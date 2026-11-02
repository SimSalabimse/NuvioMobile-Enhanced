package com.nuvio.app.core.sync

internal expect object PlatformOfflineOutboxDisk : OfflineOutboxDisk {
    override fun read(): String?
    override fun write(payload: String)
}
