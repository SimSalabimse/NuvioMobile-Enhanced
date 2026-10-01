package com.nuvio.app.features.player.skip

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object FlagSubmitLedger {
    private const val keyBase = "flag_submit_ledger"

    actual fun loadAll(): Map<String, Set<String>> {
        val raw = NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(keyBase)) ?: ""
        return decodeFlagSubmitLedger(raw)
    }

    actual fun saveAll(entries: Map<String, Set<String>>) {
        NSUserDefaults.standardUserDefaults.setObject(
            encodeFlagSubmitLedger(entries),
            forKey = ProfileScopedKey.of(keyBase),
        )
    }
}
