package com.nuvio.app.features.analyzedsync.crypto

import com.nuvio.app.features.plugins.cryptointerop.CCCryptorGCMAddIV
import com.nuvio.app.features.plugins.cryptointerop.CCCryptorGCMAddAAD
import com.nuvio.app.features.plugins.cryptointerop.CCCryptorGCMDecrypt
import com.nuvio.app.features.plugins.cryptointerop.CCCryptorGCMEncrypt
import com.nuvio.app.features.plugins.cryptointerop.CCCryptorGCMFinal
import com.nuvio.app.features.plugins.cryptointerop.CCCryptorCreateWithMode
import com.nuvio.app.features.plugins.cryptointerop.CCCryptorRefVar
import com.nuvio.app.features.plugins.cryptointerop.CCCryptorRelease
import com.nuvio.app.features.plugins.cryptointerop.CCHmac
import com.nuvio.app.features.plugins.cryptointerop.CCKeyDerivationPBKDF
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256_DIGEST_LENGTH
import com.nuvio.app.features.plugins.cryptointerop.ccNoPadding
import com.nuvio.app.features.plugins.cryptointerop.kCCAlgorithmAES
import com.nuvio.app.features.plugins.cryptointerop.kCCDecrypt
import com.nuvio.app.features.plugins.cryptointerop.kCCEncrypt
import com.nuvio.app.features.plugins.cryptointerop.kCCHmacAlgSHA256
import com.nuvio.app.features.plugins.cryptointerop.kCCModeGCM
import com.nuvio.app.features.plugins.cryptointerop.kCCPBKDF2
import com.nuvio.app.features.plugins.cryptointerop.kCCPRFHmacAlgSHA256
import com.nuvio.app.features.plugins.cryptointerop.kCCSuccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual object SyncCryptoPlatform {
    actual fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
        val derived = ByteArray(keyLengthBytes)
        password.usePinned { pinnedPassword ->
            salt.usePinned { pinnedSalt ->
                derived.usePinned { pinnedDerived ->
                    val status = CCKeyDerivationPBKDF(
                        algorithm = kCCPBKDF2,
                        password = if (password.isEmpty()) null else pinnedPassword.addressOf(0).reinterpret<ByteVar>(),
                        passwordLen = password.size.toULong(),
                        salt = if (salt.isEmpty()) null else pinnedSalt.addressOf(0).reinterpret<UByteVar>(),
                        saltLen = salt.size.toULong(),
                        prf = kCCPRFHmacAlgSHA256,
                        rounds = iterations.toUInt(),
                        derivedKey = pinnedDerived.addressOf(0).reinterpret<UByteVar>(),
                        derivedKeyLen = keyLengthBytes.toULong(),
                    )
                    check(status == kCCSuccess) { "PBKDF2 failed" }
                }
            }
        }
        return derived
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val output = ByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
        key.usePinned { pinnedKey ->
            data.usePinned { pinnedData ->
                output.usePinned { pinnedOutput ->
                    CCHmac(
                        kCCHmacAlgSHA256,
                        if (key.isEmpty()) null else pinnedKey.addressOf(0),
                        key.size.toULong(),
                        if (data.isEmpty()) null else pinnedData.addressOf(0),
                        data.size.toULong(),
                        pinnedOutput.addressOf(0).reinterpret<UByteVar>(),
                    )
                }
            }
        }
        return output
    }

    actual fun sha256(data: ByteArray): ByteArray {
        val output = ByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
        data.usePinned { pinnedData ->
            output.usePinned { pinnedOutput ->
                CC_SHA256(
                    if (data.isEmpty()) null else pinnedData.addressOf(0),
                    data.size.toUInt(),
                    pinnedOutput.addressOf(0).reinterpret<UByteVar>(),
                )
            }
        }
        return output
    }

    actual fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        crypt(encrypt = true, key = key, nonce = nonce, aad = aad, input = plaintext)

    actual fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
        if (ciphertextAndTag.size < 16) return null
        return try {
            crypt(encrypt = false, key = key, nonce = nonce, aad = aad, input = ciphertextAndTag)
        } catch (_: Exception) {
            null
        }
    }

    actual fun randomBytes(count: Int): ByteArray {
        if (count == 0) return ByteArray(0)
        val bytes = ByteArray(count)
        bytes.usePinned { pinned ->
            val status = SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pinned.addressOf(0))
            check(status == 0) { "CSPRNG failed" }
        }
        return bytes
    }

    @OptIn(BetaInteropApi::class)
    actual fun normalizeNfc(text: String): String =
        NSString.create(string = text).precomposedStringWithCanonicalMapping

    private fun crypt(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val ciphertext = if (encrypt) input else input.copyOfRange(0, input.size - 16)
        val expectedTag = if (encrypt) null else input.copyOfRange(input.size - 16, input.size)
        var produced: ByteArray? = null
        memScoped {
            val cryptorRefVar = alloc<CCCryptorRefVar>()
            key.usePinned { pinnedKey ->
                nonce.usePinned { pinnedNonce ->
                    ciphertext.usePinned { pinnedInput ->
                        val status = CCCryptorCreateWithMode(
                            op = if (encrypt) kCCEncrypt else kCCDecrypt,
                            mode = kCCModeGCM,
                            alg = kCCAlgorithmAES,
                            padding = ccNoPadding,
                            iv = null,
                            key = pinnedKey.addressOf(0),
                            keyLength = key.size.toULong(),
                            tweak = null,
                            tweakLength = 0UL,
                            numRounds = 0,
                            options = 0U,
                            cryptorRef = cryptorRefVar.ptr,
                        )
                        check(status == kCCSuccess) { "AES-GCM init failed" }
                        val cryptor = cryptorRefVar.value ?: error("AES-GCM init failed")
                        try {
                            val ivStatus = CCCryptorGCMAddIV(
                                cryptor,
                                if (nonce.isEmpty()) null else pinnedNonce.addressOf(0),
                                nonce.size.toULong(),
                            )
                            check(ivStatus == kCCSuccess) { "AES-GCM nonce failed" }
                            if (aad.isNotEmpty()) {
                                aad.usePinned { pinnedAad ->
                                    val aadStatus = CCCryptorGCMAddAAD(
                                        cryptor,
                                        pinnedAad.addressOf(0),
                                        aad.size.toULong(),
                                    )
                                    check(aadStatus == kCCSuccess) { "AES-GCM AAD failed" }
                                }
                            }
                            val plainOrCipher = ByteArray(ciphertext.size)
                            plainOrCipher.usePinned { pinnedOut ->
                                val dataStatus = if (encrypt) {
                                    CCCryptorGCMEncrypt(
                                        cryptor,
                                        if (ciphertext.isEmpty()) null else pinnedInput.addressOf(0),
                                        ciphertext.size.toULong(),
                                        if (ciphertext.isEmpty()) null else pinnedOut.addressOf(0),
                                    )
                                } else {
                                    CCCryptorGCMDecrypt(
                                        cryptor,
                                        if (ciphertext.isEmpty()) null else pinnedInput.addressOf(0),
                                        ciphertext.size.toULong(),
                                        if (ciphertext.isEmpty()) null else pinnedOut.addressOf(0),
                                    )
                                }
                                check(dataStatus == kCCSuccess) { "AES-GCM failed" }
                            }
                            val tag = expectedTag?.copyOf() ?: ByteArray(16)
                            val tagLength = alloc<platform.posix.size_tVar>()
                            tagLength.value = 16UL
                            tag.usePinned { pinnedTag ->
                                val finalStatus = CCCryptorGCMFinal(cryptor, pinnedTag.addressOf(0), tagLength.ptr)
                                check(finalStatus == kCCSuccess) { "AES-GCM tag failed" }
                            }
                            if (expectedTag != null && !constantTimeEquals(tag, expectedTag)) error("AES-GCM tag failed")
                            produced = if (encrypt) plainOrCipher + tag else plainOrCipher
                        } finally {
                            CCCryptorRelease(cryptor)
                        }
                    }
                }
            }
        }
        return produced ?: error("AES-GCM failed")
    }
}

private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
    if (left.size != right.size) return false
    var diff = 0
    for (index in left.indices) diff = diff or (left[index].toInt() xor right[index].toInt())
    return diff == 0
}
