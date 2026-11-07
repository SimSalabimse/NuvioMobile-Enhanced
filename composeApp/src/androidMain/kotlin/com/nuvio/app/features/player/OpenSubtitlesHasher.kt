package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Calculates the OpenSubtitles file hash for a video stream.
 *
 * Algorithm: `hash = fileSize + sum(first 64 KB as longs LE) + sum(last 64 KB as longs LE)`
 *
 * @see <a href="https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes">
 *     OpenSubtitles hash algorithm</a>
 */
internal object OpenSubtitlesHasher {

    private const val CHUNK_SIZE = 65536L // 64 KB
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    data class Result(val hash: String, val fileSize: Long)

    suspend fun compute(
        url: String,
        headers: Map<String, String>,
    ): Result? = withContext(Dispatchers.IO) {
        try {
            val fileSize = getContentLength(url, headers) ?: return@withContext null
            if (fileSize < CHUNK_SIZE * 2) return@withContext null

            val head = readChunk(url, headers, offset = 0, length = CHUNK_SIZE)
                ?: return@withContext null
            val tail = readChunk(url, headers, offset = fileSize - CHUNK_SIZE, length = CHUNK_SIZE)
                ?: return@withContext null
            val hash = OpenSubtitlesHash.of(fileSize, head, tail) ?: return@withContext null

            Result(
                hash = hash,
                fileSize = fileSize,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getContentLength(url: String, headers: Map<String, String>): Long? {
        val connection = PlayerPlaybackNetworking.openConnection(
            url = url,
            headers = headers,
            method = "HEAD",
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            val acceptRanges = connection.getHeaderField("Accept-Ranges")
            if (acceptRanges.equals("none", ignoreCase = true)) return null
            val len = connection.getHeaderField("Content-Length")?.toLongOrNull()
                ?: connection.contentLengthLong.takeIf { it >= 0L }
            if (len != null && len > 0) len else null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads exactly [length] bytes from a range request and returns the LE-long sum,
     * or `null` when the server did not honour the range, the read was short, or an
     * I/O error occurred.
     */
    private fun readChunk(
        url: String,
        headers: Map<String, String>,
        offset: Long,
        length: Long,
    ): ByteArray? {
        val connection = PlayerPlaybackNetworking.openConnection(
            url = url,
            headers = headers,
            method = "GET",
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            range = "bytes=$offset-${offset + length - 1}",
        )
        return try {
            val code = connection.responseCode
            if (code != 206) return null
            val stream: InputStream = connection.inputStream ?: return null
            val buf = ByteArray(length.toInt())
            var consumed = 0
            while (consumed < buf.size) {
                val n = stream.read(buf, consumed, buf.size - consumed)
                if (n < 0) return null
                consumed += n
            }
            buf
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
