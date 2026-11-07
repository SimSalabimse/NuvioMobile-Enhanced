package com.nuvio.app.features.player

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSURL
import platform.Foundation.fileHandleForReadingAtPath
import platform.posix.memcpy

internal actual suspend fun computeVideoHash(
    url: String,
    headers: Map<String, String>,
): Pair<String, Long>? = null // Remote range hashing is not implemented on iOS.

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun computeLocalVideoHash(uri: String): Pair<String, Long>? {
    val path = when {
        uri.startsWith("file:", ignoreCase = true) -> NSURL(string = uri).path
        uri.startsWith("/") -> uri
        else -> null
    } ?: return null
    val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
    return try {
        val size = handle.seekToEndOfFile().toLong()
        if (size < OpenSubtitlesHash.CHUNK_BYTES * 2L) return null
        handle.seekToFileOffset(0u)
        val head = handle.readDataOfLength(OpenSubtitlesHash.CHUNK_BYTES.toULong()).toByteArray()
        handle.seekToFileOffset((size - OpenSubtitlesHash.CHUNK_BYTES).toULong())
        val tail = handle.readDataOfLength(OpenSubtitlesHash.CHUNK_BYTES.toULong()).toByteArray()
        val hash = OpenSubtitlesHash.of(size, head, tail) ?: return null
        hash to size
    } catch (_: Exception) {
        null
    } finally {
        handle.closeFile()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return result
}
