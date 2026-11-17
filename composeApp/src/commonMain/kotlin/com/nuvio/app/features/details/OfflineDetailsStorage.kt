package com.nuvio.app.features.details

internal interface OfflineDiskStorage {
    fun read(path: String): String?
    fun write(path: String, payload: String)
}

internal expect object OfflineDetailsStorage {
    fun read(path: String): String?
    fun write(path: String, payload: String)
}

internal object OfflineDetailsFileStorage : OfflineDiskStorage {
    override fun read(path: String): String? = OfflineDetailsStorage.read(path)

    override fun write(path: String, payload: String) {
        OfflineDetailsStorage.write(path, payload)
    }
}
