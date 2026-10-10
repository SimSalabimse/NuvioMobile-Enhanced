package com.nuvio.app.features.downloads

import kotlinx.coroutines.yield

/**
 * Bytes of one growing download. The download itself stays sequential from byte 0.
 * This reader never asks the source to seek the transfer.
 */
internal interface GrowingByteSource {
    fun advertisedTotalBytes(): Long?
    fun bytesAvailable(): Long
    fun isDownloadRunning(): Boolean
    suspend fun awaitAvailable(minimumBytes: Long): Long
    fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int
}

internal data class HttpRequestHead(
    val method: String,
    val target: String,
    val rangeHeader: String?,
)

internal data class RangeResponseHead(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val bodyStart: Long,
    val bodyEndInclusive: Long?,
    val includeBody: Boolean,
)

internal fun parseHttpRequestHead(raw: String): HttpRequestHead? {
    val headerEnd = raw.indexOf("\r\n\r\n").let { crlf ->
        if (crlf >= 0) crlf else raw.indexOf("\n\n")
    }
    val head = if (headerEnd >= 0) raw.substring(0, headerEnd) else raw
    val lines = head.split("\r\n", "\n")
    val parts = lines.firstOrNull()?.split(' ') ?: return null
    if (parts.size < 2) return null
    var range: String? = null
    for (line in lines.drop(1)) {
        val name = line.substringBefore(':', "").trim()
        if (name.equals("Range", ignoreCase = true)) {
            range = line.substringAfter(':', "").trim()
        }
    }
    return HttpRequestHead(method = parts[0], target = parts[1], rangeHeader = range)
}

internal fun partialIdFromTarget(target: String): String? {
    val path = target.substringBefore('?')
    val prefix = "/partial/"
    if (!path.startsWith(prefix)) return null
    val id = path.removePrefix(prefix).trim()
    if (id.isEmpty() || id.contains('/') || id.contains('\\')) return null
    return id
}

/**
 * Plans one HTTP/1.1 response for a growing file.
 * When [advertisedTotal] is known the response advertises that full size.
 * A range whose first byte is not on disk returns 416 immediately, including
 * while the download is still running. A suffix range names the tail of that
 * advertised size, so it is 416 until those bytes exist. Holding it would keep
 * a Matroska or WebM open waiting on cues that live at the end of the file.
 * A body that starts inside the bytes on disk still names its range. The reader
 * waits at that frontier for the next bytes, and a paused download snaps the
 * satisfiable end back to the bytes already on disk.
 */
internal fun planPartialRangeResponse(
    method: String,
    rangeHeader: String?,
    advertisedTotal: Long?,
    available: Long,
    downloadRunning: Boolean,
    contentType: String,
): RangeResponseHead {
    val normalizedMethod = method.uppercase()
    if (normalizedMethod != "GET" && normalizedMethod != "HEAD") {
        return errorHead(405, contentType)
    }
    val total = advertisedTotal?.takeIf { it >= 0L }
    val safeAvailable = available.coerceAtLeast(0L)
    val parsed = parseByteRange(rangeHeader)
    if (parsed is ParsedByteRange.Invalid) {
        return unsatisfiable(total, contentType)
    }
    val requested = resolveRequestedRange(parsed, total) ?: return unsatisfiable(total, contentType)
    if (requested.start < 0L) return unsatisfiable(total, contentType)
    if (total != null && requested.start >= total) return unsatisfiable(total, contentType)

    // The first missing byte is not a reason to hold the socket. Cue and suffix
    // reads start at the advertised tail, far past a partial file.
    if (requested.start >= safeAvailable) return unsatisfiable(total, contentType)

    val requestedEnd = requested.endInclusive
    val end = when {
        requestedEnd == null && total != null -> total - 1L
        requestedEnd == null -> null
        downloadRunning -> requestedEnd
        else -> minOf(requestedEnd, safeAvailable - 1L)
    }
    if (end != null && end < requested.start) return unsatisfiable(total, contentType)

    val status = if (parsed == null) 200 else 206
    val headers = mutableListOf<Pair<String, String>>()
    headers += "Accept-Ranges" to "bytes"
    headers += "Content-Type" to contentType
    if (status == 206) {
        val endText = end?.toString() ?: "*"
        val totalText = total?.toString() ?: "*"
        headers += "Content-Range" to "bytes ${requested.start}-$endText/$totalText"
    }
    if (end != null) {
        headers += "Content-Length" to (end - requested.start + 1L).toString()
    } else if (status == 200 && total != null) {
        headers += "Content-Length" to total.toString()
    }
    headers += "Connection" to "close"
    return RangeResponseHead(
        status = status,
        headers = headers,
        bodyStart = requested.start,
        bodyEndInclusive = end,
        includeBody = normalizedMethod == "GET",
    )
}

internal fun formatHttpResponseHead(head: RangeResponseHead): String = buildString {
    val reason = when (head.status) {
        200 -> "OK"
        206 -> "Partial Content"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        else -> "Error"
    }
    append("HTTP/1.1 ")
    append(head.status)
    append(' ')
    append(reason)
    append("\r\n")
    head.headers.forEach { (name, value) ->
        append(name)
        append(": ")
        append(value)
        append("\r\n")
    }
    append("\r\n")
}

internal class PartialFileRangeReader(
    private val source: GrowingByteSource,
) {
    suspend fun readBody(
        start: Long,
        endInclusive: Long?,
        onChunk: (ByteArray) -> Unit,
    ) {
        var offset = start.coerceAtLeast(0L)
        while (true) {
            if (endInclusive != null && offset > endInclusive) return
            val available = source.bytesAvailable()
            if (offset >= available) {
                if (!source.isDownloadRunning()) return
                val afterWait = source.awaitAvailable(offset + 1L)
                if (afterWait <= offset) {
                    yield()
                    if (!source.isDownloadRunning() && source.bytesAvailable() <= offset) return
                }
                continue
            }
            val remaining = if (endInclusive == null) {
                available - offset
            } else {
                minOf(available - offset, endInclusive - offset + 1L)
            }
            if (remaining <= 0L) return
            val want = remaining.coerceAtMost(READ_CHUNK_BYTES).toInt()
            val buffer = ByteArray(want)
            val read = source.readAt(offset, buffer, 0, want)
            if (read <= 0) {
                if (!source.isDownloadRunning()) return
                source.awaitAvailable(offset + 1L)
                continue
            }
            onChunk(if (read == buffer.size) buffer else buffer.copyOf(read))
            offset += read
        }
    }

    private companion object {
        const val READ_CHUNK_BYTES = 32L * 1024L
    }
}

private sealed interface ParsedByteRange {
    data object Invalid : ParsedByteRange
    data class Bounded(val start: Long, val endInclusive: Long) : ParsedByteRange
    data class Open(val start: Long) : ParsedByteRange
    data class Suffix(val suffixLength: Long) : ParsedByteRange
}

private data class ResolvedRange(val start: Long, val endInclusive: Long?)

private fun parseByteRange(header: String?): ParsedByteRange? {
    if (header.isNullOrBlank()) return null
    val value = header.trim()
    if (!value.startsWith("bytes=", ignoreCase = true)) return ParsedByteRange.Invalid
    val spec = value.substringAfter('=').substringBefore(',').trim()
    val dash = spec.indexOf('-')
    if (dash < 0) return ParsedByteRange.Invalid
    val startText = spec.substring(0, dash).trim()
    val endText = spec.substring(dash + 1).trim()
    if (startText.isEmpty()) {
        val suffix = endText.toLongOrNull() ?: return ParsedByteRange.Invalid
        if (suffix <= 0L) return ParsedByteRange.Invalid
        return ParsedByteRange.Suffix(suffix)
    }
    val start = startText.toLongOrNull() ?: return ParsedByteRange.Invalid
    if (start < 0L) return ParsedByteRange.Invalid
    if (endText.isEmpty()) return ParsedByteRange.Open(start)
    val end = endText.toLongOrNull() ?: return ParsedByteRange.Invalid
    if (end < start) return ParsedByteRange.Invalid
    return ParsedByteRange.Bounded(start, end)
}

private fun resolveRequestedRange(
    parsed: ParsedByteRange?,
    total: Long?,
): ResolvedRange? = when (parsed) {
    null -> ResolvedRange(start = 0L, endInclusive = total?.let { it - 1L })
    ParsedByteRange.Invalid -> null
    is ParsedByteRange.Bounded -> ResolvedRange(parsed.start, parsed.endInclusive)
    is ParsedByteRange.Open -> ResolvedRange(parsed.start, null)
    is ParsedByteRange.Suffix -> {
        val size = total ?: return null
        if (parsed.suffixLength >= size) {
            ResolvedRange(0L, size - 1L)
        } else {
            ResolvedRange(size - parsed.suffixLength, size - 1L)
        }
    }
}

private fun unsatisfiable(total: Long?, contentType: String): RangeResponseHead {
    val headers = mutableListOf(
        "Accept-Ranges" to "bytes",
        "Content-Type" to contentType,
        "Content-Length" to "0",
        "Connection" to "close",
    )
    if (total != null) {
        headers += "Content-Range" to "bytes */$total"
    }
    return RangeResponseHead(
        status = 416,
        headers = headers,
        bodyStart = 0L,
        bodyEndInclusive = null,
        includeBody = false,
    )
}

private fun errorHead(
    status: Int,
    contentType: String,
): RangeResponseHead = RangeResponseHead(
    status = status,
    headers = listOf(
        "Accept-Ranges" to "bytes",
        "Content-Type" to contentType,
        "Content-Length" to "0",
        "Connection" to "close",
    ),
    bodyStart = 0L,
    bodyEndInclusive = null,
    includeBody = false,
)
