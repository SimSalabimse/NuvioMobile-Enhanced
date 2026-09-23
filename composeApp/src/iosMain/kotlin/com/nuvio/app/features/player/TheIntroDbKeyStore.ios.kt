package com.nuvio.app.features.player

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object TheIntroDbKeyStore {
    private const val keyBase = "the_intro_db_api_key"

    actual fun load(): String? {
        return NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(keyBase))
    }

    actual fun save(apiKey: String) {
        NSUserDefaults.standardUserDefaults.setObject(apiKey, forKey = ProfileScopedKey.of(keyBase))
    }
}
