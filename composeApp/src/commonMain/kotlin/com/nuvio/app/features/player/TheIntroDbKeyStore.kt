package com.nuvio.app.features.player

internal expect object TheIntroDbKeyStore {
    fun load(): String?
    fun save(apiKey: String)
}
