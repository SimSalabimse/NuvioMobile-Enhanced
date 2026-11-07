package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

internal actual suspend fun computeVideoHash(
    url: String,
    headers: Map<String, String>,
): Pair<String, Long>? {
    val result = OpenSubtitlesHasher.compute(url, headers) ?: return null
    return result.hash to result.fileSize
}

internal actual suspend fun computeLocalVideoHash(uri: String): Pair<String, Long>? =
    withContext(Dispatchers.IO) {
        val file = localVideoFile(uri) ?: return@withContext null
        if (!file.isFile) return@withContext null
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            if (size < OpenSubtitlesHash.CHUNK_BYTES * 2L) return@withContext null
            val head = ByteArray(OpenSubtitlesHash.CHUNK_BYTES)
            val tail = ByteArray(OpenSubtitlesHash.CHUNK_BYTES)
            input.readFully(head)
            input.seek(size - OpenSubtitlesHash.CHUNK_BYTES)
            input.readFully(tail)
            val hash = OpenSubtitlesHash.of(size, head, tail) ?: return@withContext null
            hash to size
        }
    }

private fun localVideoFile(uri: String): File? {
    val trimmed = uri.trim()
    if (trimmed.startsWith("file:", ignoreCase = true)) {
        val path = android.net.Uri.parse(trimmed).path ?: return null
        return File(path)
    }
    if (trimmed.startsWith("/")) return File(trimmed)
    return null
}
