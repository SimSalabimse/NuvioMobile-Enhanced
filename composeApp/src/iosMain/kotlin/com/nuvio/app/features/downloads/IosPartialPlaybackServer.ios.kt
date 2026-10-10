package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.socklen_tVar
import platform.posix.sockaddr_in
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.socket

private class PartialTarget(
    val fileName: String,
    var totalBytes: Long?,
    var running: Boolean,
)

@OptIn(ExperimentalForeignApi::class)
internal object IosPartialPlaybackServer {
    private val lock = NSLock()
    private val targets = mutableMapOf<String, PartialTarget>()
    private var serverFd: Int = -1
    private var boundPort: Int = 0

    fun update(downloadId: String, fileName: String, totalBytes: Long?, running: Boolean) {
        lock.lock()
        val current = targets[downloadId]
        if (current == null || current.fileName != fileName) {
            targets[downloadId] = PartialTarget(fileName, totalBytes, running)
        } else {
            current.totalBytes = totalBytes
            current.running = running
        }
        lock.unlock()
        IosPartialPlaybackFlags.setRunning(downloadId, running)
        ensureStarted()
    }

    fun url(downloadId: String): String? {
        lock.lock()
        val known = downloadId in targets
        lock.unlock()
        if (!known) return null
        ensureStarted()
        val port = boundPort
        if (port <= 0) return null
        return loopbackPartialUrl(port, downloadId)
    }

    private fun target(downloadId: String): PartialTarget? {
        lock.lock()
        val value = targets[downloadId]
        val copy = value?.let { PartialTarget(it.fileName, it.totalBytes, it.running) }
        lock.unlock()
        return copy
    }

    private fun ensureStarted() {
        if (serverFd >= 0) return
        lock.lock()
        if (serverFd >= 0) {
            lock.unlock()
            return
        }
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) {
            lock.unlock()
            return
        }
        memScoped {
            val enabled = alloc<IntVar>()
            enabled.value = 1
            val optionLength = sizeOf<IntVar>().toUInt()
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, enabled.ptr, optionLength)
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, enabled.ptr, optionLength)
            val address = alloc<sockaddr_in>()
            address.sin_len = sizeOf<sockaddr_in>().toUByte()
            address.sin_family = AF_INET.convert()
            address.sin_port = 0.toUShort()
            // 127.0.0.1 in network byte order on little-endian Apple platforms.
            address.sin_addr.s_addr = 0x0100007Fu
            val bound = bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
            if (bound != 0) {
                close(fd)
                lock.unlock()
                return
            }
            listen(fd, 16)
            val length = alloc<socklen_tVar>()
            length.value = sizeOf<sockaddr_in>().toUInt()
            getsockname(fd, address.ptr.reinterpret(), length.ptr)
            boundPort = networkOrderPort(address.sin_port)
        }
        serverFd = fd
        lock.unlock()
        dispatch_async(dispatch_get_global_queue(0L, 0uL)) {
            acceptLoop(fd)
        }
    }

    private fun acceptLoop(fd: Int) {
        while (true) {
            val client = accept(fd, null, null)
            if (client < 0) continue
            ignoreSigPipe(client)
            dispatch_async(dispatch_get_global_queue(0L, 0uL)) {
                handleClient(client)
            }
        }
    }

    private fun handleClient(client: Int) {
        try {
            val raw = readHeaders(client)
            val request = raw?.let(::parseHttpRequestHead)
            if (request == null) {
                sendText(client, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
                return
            }
            val downloadId = partialIdFromTarget(request.target)
            val target = downloadId?.let(::target)
            if (downloadId == null || target == null) {
                sendText(client, "HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
                return
            }
            val source = CatalogSource(downloadId, target.fileName)
            val head = planPartialRangeResponse(
                method = request.method,
                rangeHeader = request.rangeHeader,
                advertisedTotal = target.totalBytes,
                available = source.bytesAvailable(),
                downloadRunning = target.running,
                contentType = partialContentType(target.fileName),
            )
            sendText(client, formatHttpResponseHead(head))
            if (!head.includeBody) return
            runBlocking {
                PartialFileRangeReader(source).readBody(head.bodyStart, head.bodyEndInclusive) { chunk ->
                    sendBytes(client, chunk)
                }
            }
        } finally {
            close(client)
        }
    }

    private fun readHeaders(client: Int): String? {
        val buffer = ByteArray(8 * 1024)
        var used = 0
        while (used < buffer.size) {
            val read = buffer.usePinned { pinned ->
                recv(client, pinned.addressOf(used), (buffer.size - used).convert(), 0).toInt()
            }
            if (read <= 0) break
            used += read
            val text = buffer.decodeToString(0, used)
            if (text.contains("\r\n\r\n")) return text
        }
        return if (used == 0) null else buffer.decodeToString(0, used)
    }

    private fun sendText(client: Int, text: String) {
        sendBytes(client, text.encodeToByteArray())
    }

    private fun sendBytes(client: Int, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val sent = bytes.usePinned { pinned ->
                send(client, pinned.addressOf(offset), (bytes.size - offset).convert(), 0).toInt()
            }
            if (sent <= 0) return
            offset += sent
        }
    }

    private fun networkOrderPort(port: UShort): Int {
        val value = port.toInt() and 0xFFFF
        return ((value and 0xFF) shl 8) or (value ushr 8)
    }

    private fun ignoreSigPipe(client: Int) {
        memScoped {
            val enabled = alloc<IntVar>()
            enabled.value = 1
            setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, enabled.ptr, sizeOf<IntVar>().toUInt())
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class CatalogSource(
    private val downloadId: String,
    private val fileName: String,
) : GrowingByteSource {
    override fun advertisedTotalBytes(): Long? = null

    override fun bytesAvailable(): Long = readablePath()?.let { path -> fileSizeOrNull(path) } ?: 0L

    override fun isDownloadRunning(): Boolean = IosPartialPlaybackServerRunning.running(downloadId)

    override suspend fun awaitAvailable(minimumBytes: Long): Long {
        while (isDownloadRunning() && bytesAvailable() < minimumBytes) {
            delay(40)
        }
        return bytesAvailable()
    }

    override fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int {
        if (length <= 0 || offset < 0L) return 0
        val path = readablePath() ?: return 0
        val file = fopen(path, "rb") ?: return 0
        return try {
            if (fseek(file, offset, SEEK_SET) != 0) return 0
            destination.usePinned { pinned ->
                fread(pinned.addressOf(destinationOffset), 1uL, length.convert(), file).toInt()
            }
        } finally {
            fclose(file)
        }
    }

    private fun readablePath(): String? = resolveDownloadsBaseDirectory().withAccess { directory ->
        val partial = "$directory/$fileName.part"
        val finished = "$directory/$fileName"
        when {
            NSFileManager.defaultManager.fileExistsAtPath(partial) -> partial
            NSFileManager.defaultManager.fileExistsAtPath(finished) -> finished
            else -> null
        }
    }
}

private object IosPartialPlaybackServerRunning {
    fun running(downloadId: String): Boolean = IosPartialPlaybackFlags.isRunning(downloadId)
}

internal object IosPartialPlaybackFlags {
    private val lock = NSLock()
    private val running = mutableMapOf<String, Boolean>()

    fun setRunning(downloadId: String, value: Boolean) {
        lock.lock()
        running[downloadId] = value
        lock.unlock()
    }

    fun isRunning(downloadId: String): Boolean {
        lock.lock()
        val value = running[downloadId] == true
        lock.unlock()
        return value
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun platform.Foundation.NSData.copyBytes(): ByteArray {
    val size = length.toInt()
    if (size <= 0 || bytes == null) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}
