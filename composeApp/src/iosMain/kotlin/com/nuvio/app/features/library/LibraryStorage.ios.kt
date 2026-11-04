package com.nuvio.app.features.library

import com.nuvio.app.core.automation.isolateStorageKey
import platform.Foundation.NSUserDefaults

actual object LibraryStorage {
    private fun payloadKey(profileId: Int) = isolateStorageKey("library_payload_$profileId")

    actual fun loadPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey(profileId))
    }
}
