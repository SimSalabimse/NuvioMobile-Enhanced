package com.nuvio.app.features.analyzedsync.crypto

import com.nuvio.app.features.plugins.cryptointerop.CCHmac
import com.nuvio.app.features.plugins.cryptointerop.CCKeyDerivationPBKDF
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256_DIGEST_LENGTH
import com.nuvio.app.features.plugins.cryptointerop.kCCHmacAlgSHA256
import com.nuvio.app.features.plugins.cryptointerop.kCCPBKDF2
import com.nuvio.app.features.plugins.cryptointerop.kCCPRFHmacAlgSHA256
import com.nuvio.app.features.plugins.cryptointerop.kCCSuccess
import com.nuvio.app.nativebridge.NuvioSyncAesGcmOpen
import com.nuvio.app.nativebridge.NuvioSyncAesGcmSeal
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
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
        aesGcm(seal = true, key = key, nonce = nonce, aad = aad, input = plaintext, outputSize = plaintext.size + 16)
            ?: error("AES-GCM failed")

    actual fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
        if (ciphertextAndTag.size < 16) return null
        return aesGcm(
            seal = false,
            key = key,
            nonce = nonce,
            aad = aad,
            input = ciphertextAndTag,
            outputSize = ciphertextAndTag.size - 16,
        )
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

    /**
     * CryptoKit lives in the app, same as the Diagnostics `@_cdecl` bridge.
     * The static framework leaves [NuvioSyncAesGcmSeal] and [NuvioSyncAesGcmOpen] undefined
     * until the app links them. Output is ciphertext || tag, never the CryptoKit combined box.
     */
    private fun aesGcm(
        seal: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
        outputSize: Int,
    ): ByteArray? {
        if (outputSize < 0) return null
        val out = ByteArray(outputSize)
        var produced: ByteArray? = null
        memScoped {
            val written = alloc<IntVar>()
            written.value = -1
            key.usePinned { pinnedKey ->
                nonce.usePinned { pinnedNonce ->
                    aad.usePinned { pinnedAad ->
                        input.usePinned { pinnedInput ->
                            out.usePinned { pinnedOut ->
                                val status = if (seal) {
                                    NuvioSyncAesGcmSeal(
                                        pinnedKey.bytePtr(key),
                                        key.size,
                                        pinnedNonce.bytePtr(nonce),
                                        nonce.size,
                                        pinnedAad.bytePtr(aad),
                                        aad.size,
                                        pinnedInput.bytePtr(input),
                                        input.size,
                                        pinnedOut.bytePtr(out),
                                        out.size,
                                        written.ptr,
                                    )
                                } else {
                                    NuvioSyncAesGcmOpen(
                                        pinnedKey.bytePtr(key),
                                        key.size,
                                        pinnedNonce.bytePtr(nonce),
                                        nonce.size,
                                        pinnedAad.bytePtr(aad),
                                        aad.size,
                                        pinnedInput.bytePtr(input),
                                        input.size,
                                        pinnedOut.bytePtr(out),
                                        out.size,
                                        written.ptr,
                                    )
                                }
                                if (status == 0 && written.value == outputSize) produced = out
                            }
                        }
                    }
                }
            }
        }
        return produced
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun kotlinx.cinterop.Pinned<ByteArray>.bytePtr(bytes: ByteArray): CPointer<UByteVar>? =
    if (bytes.isEmpty()) null else addressOf(0).reinterpret()
