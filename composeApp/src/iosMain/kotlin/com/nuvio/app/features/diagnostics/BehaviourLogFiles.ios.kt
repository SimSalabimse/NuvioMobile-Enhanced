@file:OptIn(ExperimentalForeignApi::class)

package com.nuvio.app.features.diagnostics

import com.nuvio.app.features.profiles.ProfilePinCrypto
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLock
import platform.Foundation.NSUserDefaults
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync

@OptIn(ExperimentalForeignApi::class)
private val behaviourQueue = dispatch_queue_create("tv.nuvio.behaviour.append", null)

@OptIn(ExperimentalForeignApi::class)
internal actual fun behaviourEnqueue(block: () -> Unit) {
    dispatch_async(behaviourQueue) { block() }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun behaviourSync(block: () -> Unit) {
    dispatch_sync(behaviourQueue) { block() }
}

internal actual class BehaviourLock {
    private val lock = NSLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

fun nuvioRecordBehaviour(kind: String) {
    BehaviourEvents.record(kind)
}

fun nuvioBehaviourSnapshot(): String = BehaviourEvents.snapshot()

fun nuvioBehaviourClear() {
    BehaviourEvents.clear()
}

fun nuvioBehaviourFlush() {
    BehaviourEvents.flush()
}

fun nuvioAppendRaw(line: String) {
    val one = behaviourRawLine(line)
    if (one == null) {
        BehaviourEvents.record("metrickit-redacted")
        return
    }
    BehaviourEvents.appendRaw(one)
}

interface NuvioBehaviourLogBridge {
    fun write(active: String, previous: String)
}

object NuvioBehaviourLogRegistry {
    var bridge: NuvioBehaviourLogBridge? = null

    fun register(log: NuvioBehaviourLogBridge) {
        bridge = log
    }
}

internal actual object BehaviourLogFiles {
    actual fun write(active: String, previous: String) {
        NuvioBehaviourLogRegistry.bridge?.write(active, previous)
    }

    actual fun bearerToken(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("NuvioSyncDeviceToken")
            ?.trim()
            ?.takeIf { it.length == 43 }

    actual fun installSalt(): String {
        val defaults = NSUserDefaults.standardUserDefaults
        val existing = defaults.stringForKey("NuvioBehaviourSalt")
        if (!existing.isNullOrBlank()) return existing
        val salt = ProfilePinCrypto.secureRandomBytes(16).joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        defaults.setObject(salt, forKey = "NuvioBehaviourSalt")
        return salt
    }
}
