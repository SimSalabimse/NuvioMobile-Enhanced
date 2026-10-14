package com.nuvio.app.features.downloads

import platform.Foundation.NSUserDefaults

actual object DownloadsSettingsStorage {
    private const val allowMobileDataDownloadsKey = "allow_mobile_data_downloads"
    private const val showDownloadButtonKey = "show_download_button"
    private const val deleteWhenFinishedKey = "delete_when_finished"
    private const val switchToDownloadKey = "switch_to_download_while_watching"

    actual fun loadAllowMobileDataDownloads(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(allowMobileDataDownloadsKey) != null) {
            defaults.boolForKey(allowMobileDataDownloadsKey)
        } else {
            null
        }
    }

    actual fun saveAllowMobileDataDownloads(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = allowMobileDataDownloadsKey)
    }

    actual fun loadShowDownloadButton(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(showDownloadButtonKey) != null) {
            defaults.boolForKey(showDownloadButtonKey)
        } else {
            null
        }
    }

    actual fun saveShowDownloadButton(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = showDownloadButtonKey)
    }

    actual fun loadDeleteWhenFinished(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(deleteWhenFinishedKey) != null) {
            defaults.boolForKey(deleteWhenFinishedKey)
        } else {
            null
        }
    }

    actual fun saveDeleteWhenFinished(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = deleteWhenFinishedKey)
    }

    actual fun loadSwitchToDownloadWhileWatching(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(switchToDownloadKey) != null) {
            defaults.boolForKey(switchToDownloadKey)
        } else {
            null
        }
    }

    actual fun saveSwitchToDownloadWhileWatching(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = switchToDownloadKey)
    }

    actual fun loadFlag(key: String): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }

    actual fun saveFlag(key: String, enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = key)
    }

    actual fun loadString(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(key)

    actual fun saveString(key: String, value: String?) {
        if (value == null) NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
        else NSUserDefaults.standardUserDefaults.setObject(value, forKey = key)
    }
}
