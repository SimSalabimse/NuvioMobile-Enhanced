package com.nuvio.app.core.sync

import com.nuvio.app.core.automation.isolateStorageKey
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSThread
import platform.Foundation.NSUserDefaults
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_sync

internal actual object PlatformOfflineOutboxDisk : OfflineOutboxDisk {
    private const val documentKey = "offline_account_outbox"
    private const val badDocumentKey = "offline_account_outbox.bad"

    actual override fun read(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(isolateStorageKey(documentKey))

    actual override fun write(payload: String) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setObject(payload, forKey = isolateStorageKey(documentKey))
        defaults.synchronize()
    }

    override fun readBad(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(isolateStorageKey(badDocumentKey))

    override fun writeBad(payload: String) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setObject(payload, forKey = isolateStorageKey(badDocumentKey))
        defaults.synchronize()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun runOfflineOutboxDiskWrite(block: () -> Unit) {
    if (!NSThread.isMainThread) {
        block()
        return
    }
    dispatch_sync(dispatch_get_global_queue(0L, 0uL)) {
        block()
    }
}
