package com.nuvio.app.features.downloads

/** A typed on/off setting kept under [key] in our own settings store; [default] until first saved. */
internal class FlagSetting(private val key: String, private val default: Boolean) {
    fun load(): Boolean = DownloadsSettingsStorage.loadFlag(key) ?: default
    fun save(value: Boolean) = DownloadsSettingsStorage.saveFlag(key, value)
}

/** A typed number setting kept as text under [key]; [sanitize] runs on what was stored. */
internal class IntSetting(
    private val key: String,
    private val default: Int,
    private val sanitize: (Int) -> Int = { it },
) {
    fun load(): Int = DownloadsSettingsStorage.loadString(key)?.toIntOrNull()?.let(sanitize) ?: default
    fun save(value: Int) = DownloadsSettingsStorage.saveString(key, value.toString())
}
