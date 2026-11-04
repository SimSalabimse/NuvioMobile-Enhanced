package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.automation.isolateStorageKey

actual object DownloadsSettingsStorage {
    private const val preferencesName = "nuvio_downloads_settings"
    private const val allowMobileDataDownloadsKey = "allow_mobile_data_downloads"
    private const val showDownloadButtonKey = "show_download_button"
    private const val deleteWhenFinishedKey = "delete_when_finished"
    private const val switchToDownloadKey = "switch_to_download_while_watching"

    private var preferences: SharedPreferences? = null

    private fun scoped(key: String) = isolateStorageKey(key)

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadAllowMobileDataDownloads(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(scoped(allowMobileDataDownloadsKey))) {
                prefs.getBoolean(scoped(allowMobileDataDownloadsKey), false)
            } else {
                null
            }
        }

    actual fun saveAllowMobileDataDownloads(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(scoped(allowMobileDataDownloadsKey), enabled)
            ?.apply()
    }

    actual fun loadShowDownloadButton(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(scoped(showDownloadButtonKey))) {
                prefs.getBoolean(scoped(showDownloadButtonKey), false)
            } else {
                null
            }
        }

    actual fun saveShowDownloadButton(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(scoped(showDownloadButtonKey), enabled)
            ?.apply()
    }

    actual fun loadDeleteWhenFinished(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(scoped(deleteWhenFinishedKey))) {
                prefs.getBoolean(scoped(deleteWhenFinishedKey), false)
            } else {
                null
            }
        }

    actual fun saveDeleteWhenFinished(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(scoped(deleteWhenFinishedKey), enabled)
            ?.apply()
    }

    actual fun loadSwitchToDownloadWhileWatching(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(scoped(switchToDownloadKey))) prefs.getBoolean(scoped(switchToDownloadKey), true) else null
        }

    actual fun saveSwitchToDownloadWhileWatching(enabled: Boolean) {
        preferences?.edit()?.putBoolean(scoped(switchToDownloadKey), enabled)?.apply()
    }

    actual fun loadFlag(key: String): Boolean? {
        val stored = scoped(key)
        return preferences?.let { prefs -> if (prefs.contains(stored)) prefs.getBoolean(stored, false) else null }
    }

    actual fun saveFlag(key: String, enabled: Boolean) {
        preferences?.edit()?.putBoolean(scoped(key), enabled)?.apply()
    }

    actual fun loadString(key: String): String? = preferences?.getString(scoped(key), null)

    actual fun saveString(key: String, value: String?) {
        val stored = scoped(key)
        preferences?.edit()?.apply { if (value == null) remove(stored) else putString(stored, value) }?.apply()
    }

    actual fun loadDouble(key: String): Double? {
        val stored = scoped(key)
        return preferences?.let { prefs ->
            if (!prefs.contains(stored)) {
                null
            } else {
                runCatching { Double.fromBits(prefs.getLong(stored, 0L)) }.getOrNull()
            }
        }
    }

    actual fun saveDouble(key: String, value: Double?) {
        val stored = scoped(key)
        preferences?.edit()?.apply {
            if (value == null) remove(stored) else putLong(stored, value.toRawBits())
        }?.apply()
    }
}
