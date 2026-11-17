package com.nuvio.app.features.plugins

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.automation.automationStorageProfileId
import com.nuvio.app.core.automation.isolateStorageKey

internal object PluginStorage {
    private const val preferencesName = "nuvio_plugins"
    private const val pluginsStateKey = "plugins_state"
    private const val scraperCodeDirectoryName = "nuvio_plugin_scrapers"

    private var preferences: SharedPreferences? = null
    private var scraperCodeStore: PluginScraperCodeFileStore? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        scraperCodeStore = PluginScraperCodeFileStore(context.filesDir.resolve(scraperCodeDirectoryName))
    }

    fun loadState(profileId: Int): String? =
        preferences?.getString(isolateStorageKey("${pluginsStateKey}_$profileId"), null)

    fun saveState(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString(isolateStorageKey("${pluginsStateKey}_$profileId"), payload)
            ?.apply()
    }

    fun hasScraperCode(profileId: Int, scraperId: String): Boolean =
        scraperCodeStore?.contains(automationStorageProfileId(profileId), scraperId) == true

    fun loadScraperCode(profileId: Int, scraperId: String): String? =
        scraperCodeStore?.load(automationStorageProfileId(profileId), scraperId)

    fun saveScraperCode(
        profileId: Int,
        scraperId: String,
        code: String,
        overwrite: Boolean,
    ): Boolean = scraperCodeStore?.save(automationStorageProfileId(profileId), scraperId, code, overwrite) == true

    fun loadScraperSettings(scraperId: String): String? =
        preferences?.getString(isolateStorageKey("settings_${scraperId}"), null)

    fun saveScraperSettings(scraperId: String, payload: String) {
        preferences
            ?.edit()
            ?.putString(isolateStorageKey("settings_${scraperId}"), payload)
            ?.apply()
    }
}

internal fun currentPluginPlatform(): String = "android"

internal fun currentEpochMillis(): Long = System.currentTimeMillis()
