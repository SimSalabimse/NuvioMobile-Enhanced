package com.nuvio.app.features.diagnostics

internal actual class BehaviourLock {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}

internal actual object BehaviourLogFiles {
    actual fun write(active: String, previous: String) = Unit
    actual fun bearerToken(): String? = null
    actual fun installSalt(): String = "host"
}
