package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.automation.isolateStorageKey
import platform.Foundation.NSUserDefaults

actual object WatchProgressStorage {
    private const val payloadKey = "watch_progress_payload"

    private fun key(profileId: Int) = isolateStorageKey("${payloadKey}_$profileId")

    actual fun loadPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(key(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = key(profileId))
    }
}
