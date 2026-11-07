package com.nuvio.app.features.anilist

import com.nuvio.app.core.security.ProtectedCredentialDelete
import com.nuvio.app.core.security.ProtectedCredentialRead
import com.nuvio.app.core.security.protectedCredentialDelete
import com.nuvio.app.core.security.protectedCredentialIntegrationsIsolated
import com.nuvio.app.core.security.protectedCredentialRead
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.*
import platform.Security.*

@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformAniListAuthPersistence : AniListAuthPersistence {
    actual override fun read(profileId: Int): String? {
        if (protectedCredentialIntegrationsIsolated()) return null
        return query(profileId) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            when (protectedCredentialRead(status)) {
                ProtectedCredentialRead.Absent, ProtectedCredentialRead.Unavailable -> return@memScoped null
                ProtectedCredentialRead.Present -> Unit
            }
            val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
            try {
                val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
                ByteArray(CFDataGetLength(data).toInt()) { bytes[it].toByte() }.decodeToString()
            } finally {
                CFRelease(data)
            }
        }
        }
    }

    actual override fun write(profileId: Int, value: String?) {
        if (protectedCredentialIntegrationsIsolated()) return
        query(profileId) { query ->
        if (value == null) {
            checkDelete(SecItemDelete(query))
        } else {
            val bytes = value.encodeToByteArray().toUByteArray()
            require(bytes.isNotEmpty())
            val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong())
                ?: error("Unable to encode protected credentials")
            val attributes = dictionary()
            try {
                CFDictionarySetValue(attributes, kSecValueData, data)
                val update = SecItemUpdate(query, attributes)
                if (update == errSecItemNotFound) {
                    CFDictionarySetValue(query, kSecValueData, data)
                    CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                    val added = SecItemAdd(query, null)
                    check(added == errSecSuccess) { "Unable to save protected credentials (OSStatus $added)" }
                } else {
                    check(update == errSecSuccess) { "Unable to update protected credentials (OSStatus $update)" }
                }
            } finally {
                CFRelease(attributes)
                CFRelease(data)
            }
        }
        }
    }

    actual override fun clear() {
        if (protectedCredentialIntegrationsIsolated()) return
        query(null) { checkDelete(SecItemDelete(it)) }
    }

    private fun checkDelete(status: Int) {
        when (protectedCredentialDelete(status)) {
            ProtectedCredentialDelete.Removed, ProtectedCredentialDelete.AlreadyAbsent -> Unit
            ProtectedCredentialDelete.Refused -> error("Unable to clear protected credentials (OSStatus $status)")
        }
    }

    private fun dictionary(): CFMutableDictionaryRef =
        CFDictionaryCreateMutable(null, 0L, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr) ?: error("Unable to create credential query")

    private inline fun <T> query(profileId: Int?, block: (CFMutableDictionaryRef) -> T): T {
        val service = CFStringCreateWithCString(null, "com.nuvio.media.anilist", kCFStringEncodingUTF8)
            ?: error("Unable to encode credential service")
        val account = profileId?.let { CFStringCreateWithCString(null, "profile.$it", kCFStringEncodingUTF8) }
        val query = dictionary()
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            account?.let { CFDictionarySetValue(query, kSecAttrAccount, it) }
            return block(query)
        } finally {
            CFRelease(query)
            account?.let { CFRelease(it) }
            CFRelease(service)
        }
    }
}
