package com.nuvio.app.features.downloads

import com.nuvio.app.core.automation.isolateStorageKey
import platform.Foundation.NSUserDefaults

actual object DownloadsSettingsStorage {
    private const val allowMobileDataDownloadsKey = "allow_mobile_data_downloads"
    private const val showDownloadButtonKey = "show_download_button"
    private const val deleteWhenFinishedKey = "delete_when_finished"
    private const val switchToDownloadKey = "switch_to_download_while_watching"

    private fun scoped(key: String) = isolateStorageKey(key)

    actual fun loadAllowMobileDataDownloads(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = scoped(allowMobileDataDownloadsKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveAllowMobileDataDownloads(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = scoped(allowMobileDataDownloadsKey))
    }

    actual fun loadShowDownloadButton(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = scoped(showDownloadButtonKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveShowDownloadButton(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = scoped(showDownloadButtonKey))
    }

    actual fun loadDeleteWhenFinished(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = scoped(deleteWhenFinishedKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveDeleteWhenFinished(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = scoped(deleteWhenFinishedKey))
    }

    actual fun loadSwitchToDownloadWhileWatching(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = scoped(switchToDownloadKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveSwitchToDownloadWhileWatching(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = scoped(switchToDownloadKey))
    }

    actual fun loadFlag(key: String): Boolean? {
        val stored = scoped(key)
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(stored) != null) defaults.boolForKey(stored) else null
    }

    actual fun saveFlag(key: String, enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = scoped(key))
    }

    actual fun loadString(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(scoped(key))

    actual fun saveString(key: String, value: String?) {
        val stored = scoped(key)
        if (value == null) NSUserDefaults.standardUserDefaults.removeObjectForKey(stored)
        else NSUserDefaults.standardUserDefaults.setObject(value, forKey = stored)
    }

    actual fun loadDouble(key: String): Double? {
        val stored = scoped(key)
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(stored) == null) return null
        return defaults.doubleForKey(stored)
    }

    actual fun saveDouble(key: String, value: Double?) {
        val stored = scoped(key)
        val defaults = NSUserDefaults.standardUserDefaults
        if (value == null) defaults.removeObjectForKey(stored)
        else defaults.setDouble(value, forKey = stored)
    }
}
