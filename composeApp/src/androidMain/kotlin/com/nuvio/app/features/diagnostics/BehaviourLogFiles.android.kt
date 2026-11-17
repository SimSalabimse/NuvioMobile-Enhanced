package com.nuvio.app.features.diagnostics

internal actual fun behaviourEnqueue(block: () -> Unit) = block()

internal actual fun behaviourSync(block: () -> Unit) = block()

internal actual class BehaviourLock {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}

internal actual object BehaviourLogFiles {
    private var previous: String = ""
    private var active: String = ""
    private var snapshot: String = ""

    actual fun read(): Pair<String, String> = previous to active
    actual fun write(active: String, previous: String) {
        this.active = active
        this.previous = previous
    }
    actual fun readSnapshot(): String = snapshot
    actual fun writeSnapshot(text: String) { snapshot = text }
    actual fun deleteSnapshot() { snapshot = "" }
    actual fun bearerToken(): String? = null
    actual fun installSalt(): String = "host"
}
