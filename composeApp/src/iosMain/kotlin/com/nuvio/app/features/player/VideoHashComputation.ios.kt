package com.nuvio.app.features.player

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSURL
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

internal actual suspend fun computeVideoHash(
    url: String,
    headers: Map<String, String>,
): Pair<String, Long>? = null // Remote range hashing is not implemented on iOS.

/**
 * The deprecated NSFileHandle reads (`seekToEndOfFile`, `readDataOfLength`, `closeFile`)
 * are not in this toolchain's Foundation bindings. The same bytes are read with the
 * posix calls already used for other local files. [OpenSubtitlesHash] still does the sum.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun computeLocalVideoHash(uri: String): Pair<String, Long>? {
    val path = when {
        uri.startsWith("file:", ignoreCase = true) -> NSURL(string = uri).path
        uri.startsWith("/") -> uri
        else -> null
    } ?: return null
    val file = fopen(path, "rb") ?: return null
    return try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size < OpenSubtitlesHash.CHUNK_BYTES * 2L) return null
        val chunk = OpenSubtitlesHash.CHUNK_BYTES
        val head = readChunk(file, 0L, chunk) ?: return null
        val tail = readChunk(file, size - chunk, chunk) ?: return null
        val hash = OpenSubtitlesHash.of(size, head, tail) ?: return null
        hash to size
    } catch (_: Exception) {
        null
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readChunk(file: kotlinx.cinterop.CPointer<platform.posix.FILE>?, offset: Long, length: Int): ByteArray? {
    if (offset < 0L || fseek(file, offset, SEEK_SET) != 0) return null
    val bytes = ByteArray(length)
    val read = bytes.usePinned { pinned ->
        fread(pinned.addressOf(0), 1.convert(), length.convert(), file).toLong()
    }
    return bytes.takeIf { read == length.toLong() }
}
