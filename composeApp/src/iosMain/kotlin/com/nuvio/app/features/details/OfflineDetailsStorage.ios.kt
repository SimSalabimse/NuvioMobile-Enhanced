package com.nuvio.app.features.details

import com.nuvio.app.core.storage.AppFileCache

internal actual object OfflineDetailsStorage {
    actual fun read(path: String): String? = AppFileCache.read(path)

    actual fun write(path: String, payload: String) {
        AppFileCache.write(path, payload)
    }
}
