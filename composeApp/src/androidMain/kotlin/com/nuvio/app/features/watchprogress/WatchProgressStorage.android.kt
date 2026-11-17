package com.nuvio.app.features.watchprogress

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.automation.isolateStorageKey

actual object WatchProgressStorage {
    private const val preferencesName = "nuvio_watch_progress"
    private const val payloadKey = "watch_progress_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    private fun key(profileId: Int) = isolateStorageKey("${payloadKey}_$profileId")

    actual fun loadPayload(profileId: Int): String? =
        preferences?.getString(key(profileId), null)

    actual fun savePayload(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString(key(profileId), payload)
            ?.apply()
    }
}
