package com.nuvio.app.features.player

internal actual object TheIntroDbKeyStore {
    actual fun load(): String? = null
    actual fun save(apiKey: String) = Unit
}
