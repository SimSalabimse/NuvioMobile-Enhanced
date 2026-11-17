package com.nuvio.app.features.player.skip

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object FlagSubmitLedger {
    private const val keyBase = "flag_submit_ledger"

    actual fun loadAll(): Map<String, List<String>> {
        val raw = NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(keyBase)) ?: ""
        return decodeFlagSubmitSegments(raw)
    }

    actual fun saveAll(entries: Map<String, List<String>>) {
        NSUserDefaults.standardUserDefaults.setObject(
            encodeFlagSubmitSegments(entries),
            forKey = ProfileScopedKey.of(keyBase),
        )
    }
}
