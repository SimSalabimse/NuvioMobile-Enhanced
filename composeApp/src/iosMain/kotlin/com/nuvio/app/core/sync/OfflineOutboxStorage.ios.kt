package com.nuvio.app.core.sync

import platform.Foundation.NSUserDefaults

internal actual object PlatformOfflineOutboxDisk : OfflineOutboxDisk {
    private const val documentKey = "offline_account_outbox"

    actual override fun read(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(documentKey)

    actual override fun write(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = documentKey)
    }
}
