package com.nuvio.app.features.analyzedsync.crypto

import com.nuvio.app.features.analyzedsync.SYNC_KEY_CHECK_BYTES
import com.nuvio.app.features.analyzedsync.SYNC_SALT_BYTES

internal data class ParsedJoinCode(val spaceId: String, val salt: ByteArray, val keyCheck: ByteArray)

private val SPACE_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

/** `nuvio-sync-v1:{spaceId}:{salt base64url}:{keyCheck base64url}`. */
internal fun parseJoinCode(code: String): ParsedJoinCode? {
    val parts = code.trim().split(':')
    if (parts.size != 4 || parts[0] != "nuvio-sync-v1") return null
    if (!SPACE_ID.matches(parts[1])) return null
    val salt = decodeBase64Url(parts[2]) ?: return null
    val check = decodeBase64Url(parts[3]) ?: return null
    if (salt.size != SYNC_SALT_BYTES || check.size != SYNC_KEY_CHECK_BYTES) return null
    return ParsedJoinCode(parts[1], salt, check)
}
