package com.nuvio.app.features.analyzedsync.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual object AnalyzedSyncVault {
    private const val PREFERENCES_NAME = "nuvio_analyzed_sync"
    private const val KEY_ALIAS = "nuvio_analyzed_sync_key_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private var preferences: SharedPreferences? = null

    internal actual fun initialize(host: Any?) {
        val context = host as? Context ?: return
        synchronized(this) {
            preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        }
    }

    internal actual fun readSecret(profileId: Int): AnalyzedSyncSecret? {
        val stored = preferences?.getString(secretKey(profileId), null) ?: return null
        val plain = decrypt(stored) ?: return null
        return decodeSecret(plain)
    }

    internal actual fun writeSecret(profileId: Int, secret: AnalyzedSyncSecret): Boolean {
        val values = preferences ?: return false
        val encoded = encrypt(encodeSecret(secret)) ?: return false
        return values.edit().putString(secretKey(profileId), encoded).commit()
    }

    internal actual fun clearSecret(profileId: Int) {
        preferences?.edit()?.remove(secretKey(profileId))?.commit()
    }

    internal actual fun readSession(profileId: Int): String? = preferences?.getString(sessionKey(profileId), null)

    internal actual fun writeSession(profileId: Int, json: String) {
        preferences?.edit()?.putString(sessionKey(profileId), json)?.commit()
    }

    internal actual fun clearSession(profileId: Int) {
        preferences?.edit()?.remove(sessionKey(profileId))?.commit()
    }

    private fun encrypt(plain: String): String? {
        val key = getOrCreateKey() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv.size != GCM_IV_BYTES) return@runCatching null
            val ciphertext = cipher.doFinal(plain.encodeToByteArray())
            val envelope = ByteArray(1 + iv.size + ciphertext.size)
            envelope[0] = 1
            iv.copyInto(envelope, destinationOffset = 1)
            ciphertext.copyInto(envelope, destinationOffset = 1 + iv.size)
            Base64.encodeToString(envelope, Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun decrypt(encoded: String): String? {
        val key = getExistingKey() ?: return null
        return try {
            val envelope = Base64.decode(encoded, Base64.DEFAULT)
            if (envelope.size <= 1 + GCM_IV_BYTES || envelope[0].toInt() != 1) return null
            val iv = envelope.copyOfRange(1, 1 + GCM_IV_BYTES)
            val ciphertext = envelope.copyOfRange(1 + GCM_IV_BYTES, envelope.size)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).decodeToString()
        } catch (_: Exception) {
            null
        }
    }

    private fun getExistingKey(): SecretKey? = runCatching {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey? {
        getExistingKey()?.let { return it }
        return runCatching {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }.getOrNull()
    }

    private fun secretKey(profileId: Int): String = "secret_$profileId"
    private fun sessionKey(profileId: Int): String = "session_$profileId"
}
