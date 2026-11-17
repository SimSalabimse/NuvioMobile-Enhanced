package com.nuvio.app.features.settings

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.automation.isolateStorageKey

internal actual object SentrySettingsPlatform {
    actual val crashReportsSupported: Boolean = true
}

internal actual object SentrySettingsStorage {
    private const val preferencesName = "nuvio_sentry_settings"
    private const val enabledKey = "enabled"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadEnabled(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(isolateStorageKey(enabledKey))) prefs.getBoolean(isolateStorageKey(enabledKey), true) else null
        }

    actual fun saveEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(isolateStorageKey(enabledKey), enabled)
            ?.apply()
    }
}
