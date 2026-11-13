package com.nuvio.app.features.analyzedsync.crypto

/**
 * Platform primitives. iOS AES-GCM goes through CryptoKit. PBKDF2 and HMAC stay on
 * public CommonCrypto. Android and JVM tests use JCA.
 * The passphrase, master key, and bearer token are never logged by these functions.
 */
internal expect object SyncCryptoPlatform {
    fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray?
    fun randomBytes(count: Int): ByteArray
    fun normalizeNfc(text: String): String
}
