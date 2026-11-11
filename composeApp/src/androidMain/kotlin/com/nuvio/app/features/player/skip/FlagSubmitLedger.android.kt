package com.nuvio.app.features.player.skip

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object FlagSubmitLedger {
    private const val preferencesName = "nuvio_flag_submit_ledger"
    private const val keyBase = "flag_submit_ledger"
    private var preferences: SharedPreferences? = null
    private var memory: String = ""

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadAll(): Map<String, List<String>> {
        val raw = preferences?.getString(ProfileScopedKey.of(keyBase), null) ?: memory
        return decodeFlagSubmitSegments(raw)
    }

    actual fun saveAll(entries: Map<String, List<String>>) {
        val encoded = encodeFlagSubmitSegments(entries)
        memory = encoded
        preferences?.edit()?.putString(ProfileScopedKey.of(keyBase), encoded)?.commit()
    }
}
