package com.nuvio.app.features.player.skip

internal actual object FlagSubmitLedger {
    private var raw: String = ""

    actual fun loadAll(): Map<String, Set<String>> = decodeFlagSubmitLedger(raw)

    actual fun saveAll(entries: Map<String, Set<String>>) {
        raw = encodeFlagSubmitLedger(entries)
    }
}
