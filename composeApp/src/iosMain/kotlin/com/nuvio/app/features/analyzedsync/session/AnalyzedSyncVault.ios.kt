package com.nuvio.app.features.analyzedsync.session

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSUserDefaults
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.CoreFoundation.kCFBooleanTrue

private const val SERVICE = "com.nuvio.media.analyzed-sync"

@OptIn(ExperimentalForeignApi::class)
internal actual object AnalyzedSyncVault {
    internal actual fun initialize(host: Any?) = Unit

    internal actual fun readSecret(profileId: Int): AnalyzedSyncSecret? =
        runCatching { decodeSecret(readKeychain("secret-$profileId") ?: return null) }.getOrNull()

    internal actual fun writeSecret(profileId: Int, secret: AnalyzedSyncSecret): Boolean =
        runCatching { writeKeychain("secret-$profileId", encodeSecret(secret)) }.isSuccess

    internal actual fun clearSecret(profileId: Int) {
        runCatching { writeKeychain("secret-$profileId", null) }
    }

    internal actual fun readSession(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(sessionKey(profileId))

    internal actual fun writeSession(profileId: Int, json: String) {
        NSUserDefaults.standardUserDefaults.setObject(json, forKey = sessionKey(profileId))
    }

    internal actual fun clearSession(profileId: Int) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(sessionKey(profileId))
    }

    private fun sessionKey(profileId: Int): String = "analyzed_sync_session_$profileId"

    private fun readKeychain(account: String): String? = keychainQuery(account) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            if (status != errSecSuccess) return@memScoped null
            val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
            try {
                val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
                ByteArray(CFDataGetLength(data).toInt()) { bytes[it].toByte() }.decodeToString()
            } finally {
                CFRelease(data)
            }
        }
    }

    private fun writeKeychain(account: String, value: String?) = keychainQuery(account) { query ->
        if (value == null) {
            val status = SecItemDelete(query)
            check(status == errSecSuccess || status == errSecItemNotFound)
            return@keychainQuery
        }
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.toLong())
        } ?: error("keychain")
        val attributes = dictionary()
        try {
            CFDictionarySetValue(attributes, kSecValueData, data)
            CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            val update = SecItemUpdate(query, attributes)
            if (update == errSecItemNotFound) {
                CFDictionarySetValue(query, kSecValueData, data)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                check(SecItemAdd(query, null) == errSecSuccess)
            } else {
                check(update == errSecSuccess)
            }
        } finally {
            CFRelease(attributes)
            CFRelease(data)
        }
    }

    private fun dictionary(): CFMutableDictionaryRef =
        CFDictionaryCreateMutable(null, 0L, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: error("keychain")

    private inline fun <T> keychainQuery(accountName: String, block: (CFMutableDictionaryRef) -> T): T {
        val service = CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8) ?: error("keychain")
        val account = CFStringCreateWithCString(null, accountName, kCFStringEncodingUTF8) ?: error("keychain")
        val query = dictionary()
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(account)
            CFRelease(service)
        }
    }
}
