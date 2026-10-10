package com.nuvio.app.features.downloads

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Serves one growing `{name}.part` over `127.0.0.1` using the shared range reader.
 * The download keeps appending from byte 0. This server only reads.
 */
internal object AndroidPartialPlaybackServer {
    private val lock = Any()
    private val targets = mutableMapOf<String, PartialTarget>()
    private var server: ServerSocket? = null
    private var boundPort: Int = 0

    fun update(
        downloadId: String,
        fileName: String,
        directory: File,
        totalBytes: Long?,
        running: Boolean,
    ) {
        if (downloadId.isBlank() || !isSafeFileName(fileName)) return
        synchronized(lock) {
            val current = targets[downloadId]
            if (
                current == null ||
                current.fileName != fileName ||
                current.directory.path != directory.path
            ) {
                targets[downloadId] = PartialTarget(fileName, directory, totalBytes, running)
            } else {
                current.totalBytes = totalBytes
                current.running = running
            }
        }
        ensureStarted()
    }

    fun url(downloadId: String): String? {
        val known = synchronized(lock) { downloadId in targets }
        if (!known) return null
        ensureStarted()
        val port = synchronized(lock) { boundPort }
        if (port <= 0) return null
        return loopbackPartialUrl(port, downloadId)
    }

    internal fun isRunning(downloadId: String): Boolean = synchronized(lock) {
        targets[downloadId]?.running == true
    }

    private fun advertisedTotal(downloadId: String): Long? = synchronized(lock) {
        targets[downloadId]?.totalBytes
    }

    private fun location(downloadId: String): Pair<File, String>? = synchronized(lock) {
        val target = targets[downloadId] ?: return null
        target.directory to target.fileName
    }

    private fun ensureStarted() {
        synchronized(lock) {
            if (server != null) return
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(PARTIAL_PLAYBACK_LOOPBACK_HOST), 0))
            boundPort = socket.localPort
            server = socket
            Thread({ acceptLoop(socket) }, "nuvio-partial-playback").apply { isDaemon = true }.start()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                continue
            }
            if (!client.inetAddress.isLoopbackAddress) {
                runCatching { client.close() }
                continue
            }
            Thread({
                try {
                    handleClient(client)
                } catch (_: IOException) {
                    // The player closed the socket while the download was still growing.
                } finally {
                    runCatching { client.close() }
                }
            }, "nuvio-partial-playback-client").apply { isDaemon = true }.start()
        }
    }

    private fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = 0
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val request = readHeaders(input)?.let(::parseHttpRequestHead)
        if (request == null) {
            writeAscii(output, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
            return
        }
        val downloadId = partialIdFromTarget(request.target)
        val located = downloadId?.let(::location)
        if (downloadId == null || located == null) {
            writeAscii(output, "HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
            return
        }
        val (directory, fileName) = located
        val source = FileGrowingSource(downloadId, directory, fileName)
        val head = planPartialRangeResponse(
            method = request.method,
            rangeHeader = request.rangeHeader,
            advertisedTotal = advertisedTotal(downloadId),
            available = source.bytesAvailable(),
            downloadRunning = isRunning(downloadId),
            contentType = partialContentType(fileName),
        )
        writeAscii(output, formatHttpResponseHead(head))
        output.flush()
        if (!head.includeBody) return
        runBlocking {
            PartialFileRangeReader(source).readBody(head.bodyStart, head.bodyEndInclusive) { chunk ->
                output.write(chunk)
                output.flush()
            }
        }
    }

    private fun readHeaders(input: InputStream): String? {
        val buffer = ByteArray(8 * 1024)
        var used = 0
        while (used < buffer.size) {
            val read = input.read(buffer, used, buffer.size - used)
            if (read < 0) break
            used += read
            val text = buffer.decodeToString(0, used)
            if (text.contains("\r\n\r\n")) return text
        }
        return if (used == 0) null else buffer.decodeToString(0, used)
    }

    private fun writeAscii(output: OutputStream, text: String) {
        output.write(text.encodeToByteArray())
    }
}

private class PartialTarget(
    val fileName: String,
    val directory: File,
    var totalBytes: Long?,
    @Volatile var running: Boolean,
)

private class FileGrowingSource(
    private val downloadId: String,
    private val directory: File,
    private val fileName: String,
) : GrowingByteSource {
    override fun advertisedTotalBytes(): Long? = null

    override fun bytesAvailable(): Long = readableFile()?.takeIf(File::isFile)?.length() ?: 0L

    override fun isDownloadRunning(): Boolean = AndroidPartialPlaybackServer.isRunning(downloadId)

    override suspend fun awaitAvailable(minimumBytes: Long): Long {
        while (isDownloadRunning() && bytesAvailable() < minimumBytes) {
            delay(40)
        }
        return bytesAvailable()
    }

    override fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int {
        if (length <= 0 || offset < 0L) return 0
        val file = readableFile() ?: return 0
        return try {
            RandomAccessFile(file, "r").use { input ->
                if (offset >= input.length()) return 0
                input.seek(offset)
                input.read(destination, destinationOffset, length).coerceAtLeast(0)
            }
        } catch (_: IOException) {
            0
        }
    }

    private fun readableFile(): File? {
        val partial = File(directory, "$fileName.part")
        val finished = File(directory, fileName)
        return when {
            partial.isFile -> partial
            finished.isFile -> finished
            else -> null
        }
    }
}

internal fun readDownloadPrefix(directory: File, fileName: String, maxBytes: Int): ByteArray {
    if (maxBytes <= 0 || !isSafeFileName(fileName)) return ByteArray(0)
    val partial = File(directory, "$fileName.part")
    val finished = File(directory, fileName)
    val file = when {
        partial.isFile -> partial
        finished.isFile -> finished
        else -> return ByteArray(0)
    }
    return try {
        RandomAccessFile(file, "r").use { input ->
            val want = minOf(maxBytes.toLong(), input.length()).toInt()
            if (want <= 0) return ByteArray(0)
            val buffer = ByteArray(want)
            var offset = 0
            while (offset < want) {
                val read = input.read(buffer, offset, want - offset)
                if (read < 0) break
                offset += read
            }
            if (offset == buffer.size) buffer else buffer.copyOf(offset)
        }
    } catch (_: IOException) {
        ByteArray(0)
    }
}

internal data class SettledPartialFile(
    val file: File,
    val deferred: Boolean,
)

/**
 * Renames `.part` to the finished name when nothing is watching.
 * A held playback lease leaves the same path in place until [finishAndroidPartialFile].
 */
internal fun settleAndroidPartialFile(
    downloadId: String,
    partialFile: File,
    destination: File,
    exportTreeUri: String?,
): SettledPartialFile? {
    if (destination.isFile) {
        AndroidDeferredPartialRename.discard(downloadId)
        return SettledPartialFile(destination, deferred = false)
    }
    if (!partialFile.isFile) return null
    AndroidDeferredPartialRename.remember(
        downloadId = downloadId,
        fileName = destination.name,
        directory = destination.parentFile ?: partialFile.parentFile ?: return null,
        exportTreeUri = exportTreeUri,
    )
    if (PartialPlaybackLease.isHeld(downloadId)) {
        return SettledPartialFile(partialFile, deferred = true)
    }
    val finished = finishAndroidPartialFile(downloadId)
    if (finished != null) return SettledPartialFile(finished.file, deferred = false)
    if (destination.isFile) return SettledPartialFile(destination, deferred = false)
    return null
}

internal data class FinishedPartialFile(
    val file: File,
    val exportTreeUri: String?,
)

internal fun finishAndroidPartialFile(downloadId: String): FinishedPartialFile? =
    AndroidDeferredPartialRename.takeAndRename(downloadId)

internal object AndroidDeferredPartialRename {
    private val lock = Any()
    private val pending = mutableMapOf<String, PendingRename>()

    fun remember(
        downloadId: String,
        fileName: String,
        directory: File,
        exportTreeUri: String?,
    ) {
        synchronized(lock) {
            pending[downloadId] = PendingRename(fileName, directory, exportTreeUri)
        }
    }

    fun discard(downloadId: String) {
        synchronized(lock) {
            pending.remove(downloadId)
        }
    }

    fun clearFileName(fileName: String) {
        synchronized(lock) {
            pending.entries.removeAll { it.value.fileName == fileName }
        }
    }

    fun takeAndRename(downloadId: String): FinishedPartialFile? = synchronized(lock) {
        val item = pending.remove(downloadId) ?: return null
        val destination = File(item.directory, item.fileName)
        val part = File(item.directory, "${item.fileName}.part")
        if (part.isFile) {
            if (destination.exists() && !destination.delete()) {
                pending[downloadId] = item
                return null
            }
            if (!part.renameTo(destination)) {
                pending[downloadId] = item
                return null
            }
        } else if (!destination.isFile) {
            pending[downloadId] = item
            return null
        }
        FinishedPartialFile(destination, item.exportTreeUri)
    }

    /** After a process death the in-memory lease is gone, so a leftover `.part` can take its final name. */
    fun recoverIfUnwatched(downloadId: String, directory: File, fileName: String): Boolean = synchronized(lock) {
        if (PartialPlaybackLease.isHeld(downloadId) || downloadId in pending) return false
        if (!isSafeFileName(fileName)) return false
        val destination = File(directory, fileName)
        val part = File(directory, "$fileName.part")
        if (destination.isFile || !part.isFile) return false
        part.renameTo(destination)
    }
}

private data class PendingRename(
    val fileName: String,
    val directory: File,
    val exportTreeUri: String?,
)

private fun isSafeFileName(fileName: String): Boolean =
    fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains('\\')
