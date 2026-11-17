package com.nuvio.app.features.watched

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.automation.isolateStorageKey

actual object WatchedStorage {
    private const val preferencesName = "nuvio_watched"
    private fun payloadKey(profileId: Int) = isolateStorageKey("watched_payload_$profileId")

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(profileId: Int): String? =
        preferences?.getString(payloadKey(profileId), null)

    actual fun savePayload(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString(payloadKey(profileId), payload)
            ?.apply()
    }
}

