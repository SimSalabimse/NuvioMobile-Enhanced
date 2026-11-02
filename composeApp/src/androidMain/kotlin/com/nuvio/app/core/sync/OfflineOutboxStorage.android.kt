package com.nuvio.app.core.sync

import android.content.Context
import android.content.SharedPreferences

internal actual object PlatformOfflineOutboxDisk : OfflineOutboxDisk {
    private const val preferencesName = "nuvio_offline_outbox"
    private const val documentKey = "offline_account_outbox"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual override fun read(): String? =
        preferences?.getString(documentKey, null)

    actual override fun write(payload: String) {
        preferences
            ?.edit()
            ?.putString(documentKey, payload)
            ?.apply()
    }
}
