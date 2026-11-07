package com.nuvio.app.features.player

/**
 * OpenSubtitles hash: file size plus the little-endian uint64 sum of the first 64 KB
 * and the last 64 KB. The sum wraps like an unsigned 64-bit add.
 */
internal object OpenSubtitlesHash {
    const val CHUNK_BYTES = 65_536

    fun of(fileSize: Long, head: ByteArray, tail: ByteArray): String? {
        if (fileSize < CHUNK_BYTES * 2L) return null
        if (head.size < CHUNK_BYTES || tail.size < CHUNK_BYTES) return null
        var sum = fileSize
        sum += sumChunk(head)
        sum += sumChunk(tail)
        return sum.toULong().toString(16).padStart(16, '0')
    }

    private fun sumChunk(chunk: ByteArray): Long {
        var sum = 0L
        var index = 0
        while (index + 8 <= CHUNK_BYTES) {
            var value = 0L
            for (byteIndex in 0 until 8) {
                value = value or ((chunk[index + byteIndex].toLong() and 0xFF) shl (byteIndex * 8))
            }
            sum += value
            index += 8
        }
        return sum
    }
}
