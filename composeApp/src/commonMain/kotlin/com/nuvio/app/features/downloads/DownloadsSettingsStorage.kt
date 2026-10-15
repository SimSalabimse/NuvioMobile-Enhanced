package com.nuvio.app.features.downloads

internal expect object DownloadsSettingsStorage {
    fun loadAllowMobileDataDownloads(): Boolean?
    fun saveAllowMobileDataDownloads(enabled: Boolean)
    fun loadShowDownloadButton(): Boolean?
    fun saveShowDownloadButton(enabled: Boolean)
    fun loadDeleteWhenFinished(): Boolean?
    fun saveDeleteWhenFinished(enabled: Boolean)
    fun loadSwitchToDownloadWhileWatching(): Boolean?
    fun saveSwitchToDownloadWhileWatching(enabled: Boolean)
    fun loadFlag(key: String): Boolean?
    fun saveFlag(key: String, enabled: Boolean)
    fun loadString(key: String): String?
    fun saveString(key: String, value: String?)
}
