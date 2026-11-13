package com.nuvio.app.features.analyzedsync.session

import com.nuvio.app.features.analyzedsync.crypto.decodeBase64
import com.nuvio.app.features.analyzedsync.crypto.encodeBase64

/** Passphrase and derived keys. Never log this, and never persist the enroll secret here. */
internal class AnalyzedSyncSecret(
    val passphrase: String,
    val encKey: ByteArray,
    val authToken: ByteArray,
) {
    override fun toString(): String = "AnalyzedSyncSecret(redacted)"
}

internal fun encodeSecret(secret: AnalyzedSyncSecret): String =
    encodeBase64(secret.passphrase.encodeToByteArray()) + "\n" +
        encodeBase64(secret.encKey) + "\n" +
        encodeBase64(secret.authToken)

internal fun decodeSecret(text: String): AnalyzedSyncSecret? {
    val lines = text.split('\n')
    if (lines.size != 3) return null
    val passphrase = decodeBase64(lines[0])?.decodeToString() ?: return null
    val encKey = decodeBase64(lines[1]) ?: return null
    val authToken = decodeBase64(lines[2]) ?: return null
    if (encKey.size != 32 || authToken.size != 32) return null
    return AnalyzedSyncSecret(passphrase, encKey, authToken)
}

/**
 * Passphrase and derived keys live in platform secure storage. Session metadata
 * (space id, salt, key check, cursor) does not include those values.
 * [initialize] must run before use on Android. It is a no-op until then, so host tests
 * never touch the Android Keystore.
 */
internal expect object AnalyzedSyncVault {
    internal fun initialize(host: Any?)
    internal fun readSecret(profileId: Int): AnalyzedSyncSecret?
    internal fun writeSecret(profileId: Int, secret: AnalyzedSyncSecret): Boolean
    internal fun clearSecret(profileId: Int)
    internal fun readSession(profileId: Int): String?
    internal fun writeSession(profileId: Int, json: String)
    internal fun clearSession(profileId: Int)
}
