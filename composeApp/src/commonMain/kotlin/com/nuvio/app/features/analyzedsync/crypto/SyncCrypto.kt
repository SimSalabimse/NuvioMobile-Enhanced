package com.nuvio.app.features.analyzedsync.crypto

import com.nuvio.app.features.analyzedsync.SYNC_KEY_BYTES
import com.nuvio.app.features.analyzedsync.SYNC_KEY_CHECK_BYTES
import com.nuvio.app.features.analyzedsync.SYNC_KDF_ITERATIONS
import com.nuvio.app.features.analyzedsync.SYNC_MAX_PLAINTEXT_BYTES
import com.nuvio.app.features.analyzedsync.SYNC_NONCE_BYTES
import com.nuvio.app.features.analyzedsync.SYNC_SALT_BYTES

internal class SyncCryptoError(val code: String) : Exception(code)

internal data class DerivedKeys(
    val master: ByteArray,
    val encKey: ByteArray,
    val authToken: ByteArray,
    val keyCheck: ByteArray,
) {
    override fun toString(): String = "DerivedKeys(redacted)"
}

internal data class RoutingAad(
    val formatVersion: Int,
    val spaceId: String,
    val blobId: String,
    val deviceId: String,
    val kind: String,
)

internal fun deriveKeys(passphrase: String, salt: ByteArray, iterations: Int = SYNC_KDF_ITERATIONS): DerivedKeys {
    if (salt.size != SYNC_SALT_BYTES) throw SyncCryptoError("bad_input")
    val normalized = SyncCryptoPlatform.normalizeNfc(passphrase)
    val master = SyncCryptoPlatform.pbkdf2HmacSha256(normalized.encodeToByteArray(), salt, iterations, SYNC_KEY_BYTES)
    val encKey = hmacBlock(master, "nuvio-sync-v1 enc")
    val authToken = hmacBlock(master, "nuvio-sync-v1 auth")
    val check = hmacBlock(master, "nuvio-sync-v1 check")
    return DerivedKeys(master, encKey, authToken, check.copyOf(SYNC_KEY_CHECK_BYTES))
}

internal fun associatedDataText(routing: RoutingAad): String =
    "nuvio-sync/${routing.formatVersion}\n${routing.spaceId}\n${routing.blobId}\n${routing.deviceId}\n${routing.kind}\n"

internal fun associatedData(routing: RoutingAad): ByteArray = associatedDataText(routing).encodeToByteArray()

/** 12 bytes from the platform CSPRNG. A counter is not a nonce. */
internal fun randomNonce(fill: (ByteArray) -> Unit = { bytes ->
    val drawn = SyncCryptoPlatform.randomBytes(bytes.size)
    drawn.copyInto(bytes)
}): ByteArray {
    val nonce = ByteArray(SYNC_NONCE_BYTES)
    fill(nonce)
    if (nonce.size != SYNC_NONCE_BYTES) throw SyncCryptoError("bad_input")
    return nonce
}

internal fun encryptBlob(encKey: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
    if (encKey.size != SYNC_KEY_BYTES || nonce.size != SYNC_NONCE_BYTES) throw SyncCryptoError("bad_input")
    if (plaintext.size > SYNC_MAX_PLAINTEXT_BYTES) throw SyncCryptoError("bad_input")
    return SyncCryptoPlatform.aesGcmEncrypt(encKey, nonce, aad, plaintext)
}

internal fun decryptBlob(encKey: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
    if (encKey.size != SYNC_KEY_BYTES || nonce.size != SYNC_NONCE_BYTES || ciphertext.size < 16) {
        throw SyncCryptoError("bad_input")
    }
    return SyncCryptoPlatform.aesGcmDecrypt(encKey, nonce, aad, ciphertext) ?: throw SyncCryptoError("corrupt_ciphertext")
}

internal fun keyCheckMatches(actual: ByteArray, expected: ByteArray): Boolean = constantTimeEquals(actual, expected)

internal fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
    if (left.size != right.size) return false
    var diff = 0
    for (index in left.indices) diff = diff or (left[index].toInt() xor right[index].toInt())
    return diff == 0
}

internal fun sha256(bytes: ByteArray): ByteArray = SyncCryptoPlatform.sha256(bytes)

internal fun joinCode(spaceId: String, salt: ByteArray, keyCheck: ByteArray): String =
    "nuvio-sync-v1:$spaceId:${encodeBase64Url(salt)}:${encodeBase64Url(keyCheck)}"

private fun hmacBlock(master: ByteArray, label: String): ByteArray {
    val prefix = label.encodeToByteArray()
    val data = ByteArray(prefix.size + 1)
    prefix.copyInto(data)
    data[prefix.size] = 0x01
    return SyncCryptoPlatform.hmacSha256(master, data)
}

internal fun encodeBase64(bytes: ByteArray): String {
    val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var index = 0
    while (index < bytes.size) {
        val b0 = bytes[index].toInt() and 0xFF
        val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
        val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
        val triple = (b0 shl 16) or (b1 shl 8) or b2
        out.append(table[(triple shr 18) and 63])
        out.append(table[(triple shr 12) and 63])
        if (index + 1 < bytes.size) out.append(table[(triple shr 6) and 63]) else out.append('=')
        if (index + 2 < bytes.size) out.append(table[triple and 63]) else out.append('=')
        index += 3
    }
    return out.toString()
}

internal fun encodeBase64Url(bytes: ByteArray): String =
    encodeBase64(bytes).replace('+', '-').replace('/', '_').trimEnd('=')

/** Standard base64. Non-canonical padding and extra characters are rejected. */
internal fun decodeBase64(value: String): ByteArray? {
    if (value.isEmpty() || value.length % 4 != 0) return null
    if (value.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '+' && it != '/' && it != '=' }) return null
    val padding = when {
        value.endsWith("==") -> 2
        value.endsWith("=") -> 1
        else -> 0
    }
    if (value.dropLast(padding).contains('=')) return null
    val out = ArrayList<Byte>(value.length / 4 * 3)
    var index = 0
    while (index < value.length) {
        val chunk = value.substring(index, index + 4)
        val values = chunk.map { char ->
            when (char) {
                in 'A'..'Z' -> char - 'A'
                in 'a'..'z' -> char - 'a' + 26
                in '0'..'9' -> char - '0' + 52
                '+' -> 62
                '/' -> 63
                '=' -> -1
                else -> return null
            }
        }
        if (values[0] < 0 || values[1] < 0) return null
        out += ((values[0] shl 2) or (values[1] shr 4)).toByte()
        if (values[2] >= 0) out += (((values[1] and 15) shl 4) or (values[2] shr 2)).toByte()
        else if (chunk[2] != '=' || chunk[3] != '=') return null
        if (values[3] >= 0) out += (((values[2] and 3) shl 6) or values[3]).toByte()
        else if (chunk[3] != '=') return null
        index += 4
    }
    return out.toByteArray()
}

internal fun decodeBase64Url(value: String): ByteArray? {
    if (value.any { it == '=' || it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' }) return null
    val padded = value.replace('-', '+').replace('_', '/') + "=".repeat((4 - value.length % 4) % 4)
    return decodeBase64(padded)
}

internal fun encodeHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (byte in bytes) append((byte.toInt() and 0xFF).toString(16).padStart(2, '0'))
}

internal fun decodeHex(value: String): ByteArray? {
    if (value.length % 2 != 0 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun randomUuidV4(random: (Int) -> ByteArray = SyncCryptoPlatform::randomBytes): String {
    val bytes = random(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = encodeHex(bytes)
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
