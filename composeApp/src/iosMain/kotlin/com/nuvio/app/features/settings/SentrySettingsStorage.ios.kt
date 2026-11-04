package com.nuvio.app.features.settings

import com.nuvio.app.core.automation.isolateStorageKey
import platform.Foundation.NSUserDefaults

internal actual object SentrySettingsPlatform {
    actual val crashReportsSupported: Boolean = false
}

internal actual object SentrySettingsStorage {
    private const val enabledKey = "sentry_enabled"

    actual fun loadEnabled(): Boolean? {
        val stored = isolateStorageKey(enabledKey)
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(stored) != null) {
            defaults.boolForKey(stored)
        } else {
            null
        }
    }

    actual fun saveEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = isolateStorageKey(enabledKey))
    }
}
