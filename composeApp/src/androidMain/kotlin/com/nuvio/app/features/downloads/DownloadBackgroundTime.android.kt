package com.nuvio.app.features.downloads

/** Android downloads run in a worker, so a link refresh needs no extra time from the system. */
internal actual object DownloadBackgroundTime {
    actual fun begin(name: String): Any? = null
    actual fun end(token: Any?) = Unit
    actual fun isAppActive(): Boolean = true
}
