package com.nuvio.app.core.auth

import com.nuvio.app.core.automation.allowStorageWrite
import platform.Foundation.NSUserDefaults

actual object AuthStorage {
    private const val KEY_ANONYMOUS_USER_ID = "anonymous_user_id"

    actual fun loadAnonymousUserId(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(KEY_ANONYMOUS_USER_ID)

    actual fun saveAnonymousUserId(userId: String) {
        if (!allowStorageWrite(KEY_ANONYMOUS_USER_ID)) return
        NSUserDefaults.standardUserDefaults.setObject(userId, forKey = KEY_ANONYMOUS_USER_ID)
    }

    actual fun clearAnonymousUserId() {
        if (!allowStorageWrite(KEY_ANONYMOUS_USER_ID)) return
        NSUserDefaults.standardUserDefaults.removeObjectForKey(KEY_ANONYMOUS_USER_ID)
    }
}
