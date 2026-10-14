package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences

actual object DownloadsSettingsStorage {
    private const val preferencesName = "nuvio_downloads_settings"
    private const val allowMobileDataDownloadsKey = "allow_mobile_data_downloads"
    private const val showDownloadButtonKey = "show_download_button"
    private const val deleteWhenFinishedKey = "delete_when_finished"
    private const val switchToDownloadKey = "switch_to_download_while_watching"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadAllowMobileDataDownloads(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(allowMobileDataDownloadsKey)) {
                prefs.getBoolean(allowMobileDataDownloadsKey, false)
            } else {
                null
            }
        }

    actual fun saveAllowMobileDataDownloads(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(allowMobileDataDownloadsKey, enabled)
            ?.apply()
    }

    actual fun loadShowDownloadButton(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(showDownloadButtonKey)) {
                prefs.getBoolean(showDownloadButtonKey, false)
            } else {
                null
            }
        }

    actual fun saveShowDownloadButton(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(showDownloadButtonKey, enabled)
            ?.apply()
    }

    actual fun loadDeleteWhenFinished(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(deleteWhenFinishedKey)) {
                prefs.getBoolean(deleteWhenFinishedKey, false)
            } else {
                null
            }
        }

    actual fun saveDeleteWhenFinished(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(deleteWhenFinishedKey, enabled)
            ?.apply()
    }

    actual fun loadSwitchToDownloadWhileWatching(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(switchToDownloadKey)) prefs.getBoolean(switchToDownloadKey, true) else null
        }

    actual fun saveSwitchToDownloadWhileWatching(enabled: Boolean) {
        preferences?.edit()?.putBoolean(switchToDownloadKey, enabled)?.apply()
    }

    actual fun loadFlag(key: String): Boolean? =
        preferences?.let { prefs -> if (prefs.contains(key)) prefs.getBoolean(key, false) else null }

    actual fun saveFlag(key: String, enabled: Boolean) {
        preferences?.edit()?.putBoolean(key, enabled)?.apply()
    }
}
