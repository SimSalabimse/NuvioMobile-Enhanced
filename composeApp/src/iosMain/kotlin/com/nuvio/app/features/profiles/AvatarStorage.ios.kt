package com.nuvio.app.features.profiles

import com.nuvio.app.core.automation.allowStorageWrite
import platform.Foundation.NSUserDefaults

actual object AvatarStorage {
    private const val payloadKey = "avatar_catalog_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        if (!allowStorageWrite(payloadKey)) return
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
}