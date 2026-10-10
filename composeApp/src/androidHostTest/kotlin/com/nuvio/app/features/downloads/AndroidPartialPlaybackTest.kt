package com.nuvio.app.features.downloads

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AndroidPartialPlaybackTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun growingPartFeedsTheRangeReaderWithoutRewritingThePrefix() {
        val directory = temporary.newFolder()
        val partial = File(directory, "movie.mkv.part")
        val prefix = ByteArray(64) { (it + 1).toByte() }
        partial.writeBytes(prefix)
        val downloadId = "grow1"
        AndroidPartialPlaybackServer.update(
            downloadId = downloadId,
            fileName = "movie.mkv",
            directory = directory,
            totalBytes = 1_000L,
            running = true,
        )
        val url = AndroidPartialPlaybackServer.url(downloadId)
        assertNotNull(url)
        assertEquals("127.0.0.1", URI(url).host)

        val socket = Socket("127.0.0.1", URI(url).port)
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 3_000
            val output = socket.getOutputStream()
            output.write(
                "GET /partial/$downloadId HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=100-109\r\n\r\n"
                    .encodeToByteArray(),
            )
            output.flush()
            val input = socket.getInputStream()
            val headers = readHeaders(input)
            assertTrue(headers.startsWith("HTTP/1.1 206"))
            assertTrue(headers.contains("Content-Range: bytes 100-109/1000"))
            assertTrue(headers.contains("Content-Length: 10"))

            socket.soTimeout = 400
            val early = runCatching { input.read() }
            assertTrue(early.exceptionOrNull() is SocketTimeoutException)
            assertEquals(prefix.size.toLong(), partial.length())
            assertTrue(prefix.contentEquals(partial.readBytes()))

            val extra = ByteArray(50) { (100 + it).toByte() }
            FileOutputStream(partial, true).use { it.write(extra) }
            socket.soTimeout = 3_000
            val body = ByteArray(10)
            readFully(input, body)
            val file = partial.readBytes()
            assertTrue(prefix.contentEquals(file.copyOfRange(0, prefix.size)))
            assertTrue(extra.contentEquals(file.copyOfRange(prefix.size, file.size)))
            assertTrue(file.copyOfRange(100, 110).contentEquals(body))
        } finally {
            AndroidPartialPlaybackServer.update(downloadId, "movie.mkv", directory, 1_000L, running = false)
            socket.close()
        }
    }

    @Test
    fun rangePastAPausedPartDoesNotWriteTheFile() {
        val directory = temporary.newFolder()
        val partial = File(directory, "movie.mp4.part")
        val original = ByteArray(64) { it.toByte() }
        partial.writeBytes(original)
        val downloadId = "paused1"
        AndroidPartialPlaybackServer.update(downloadId, "movie.mp4", directory, 2_000L, running = false)
        val url = checkNotNull(AndroidPartialPlaybackServer.url(downloadId))
        val socket = Socket("127.0.0.1", URI(url).port)
        try {
            socket.soTimeout = 3_000
            socket.getOutputStream().apply {
                write(
                    "GET /partial/$downloadId HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=500-900\r\n\r\n"
                        .encodeToByteArray(),
                )
                flush()
            }
            val headers = readHeaders(socket.getInputStream())
            assertTrue(headers.startsWith("HTTP/1.1 416"))
            assertTrue(original.contentEquals(partial.readBytes()))
        } finally {
            socket.close()
        }
    }

    @Test
    fun watchedPartStaysUntilPlaybackClosesThenRenames() {
        val directory = temporary.newFolder()
        val partial = File(directory, "movie.mkv.part")
        val destination = File(directory, "movie.mkv")
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        partial.writeBytes(bytes)
        val downloadId = "lease1"
        PartialPlaybackLease.acquire(downloadId)
        try {
            val settled = settleAndroidPartialFile(
                downloadId = downloadId,
                partialFile = partial,
                destination = destination,
                exportTreeUri = null,
            )
            assertNotNull(settled)
            assertTrue(settled.deferred)
            assertTrue(partial.isFile)
            assertFalse(destination.exists())
            assertTrue(bytes.contentEquals(partial.readBytes()))
            assertNull(finishAndroidPartialFile("nobody"))
        } finally {
            PartialPlaybackLease.release(downloadId)
        }

        val finished = finishAndroidPartialFile(downloadId)
        assertNotNull(finished)
        assertEquals(destination, finished.file)
        assertFalse(partial.exists())
        assertTrue(bytes.contentEquals(destination.readBytes()))
        assertNull(finishAndroidPartialFile(downloadId))
    }

    @Test
    fun unwatchedPartRenamesAsSoonAsTheTransferFinishes() {
        val directory = temporary.newFolder()
        val partial = File(directory, "done.mkv.part")
        val destination = File(directory, "done.mkv")
        partial.writeBytes(byteArrayOf(1, 2, 3))
        val settled = settleAndroidPartialFile("free1", partial, destination, null)
        assertNotNull(settled)
        assertFalse(settled.deferred)
        assertTrue(destination.isFile)
        assertFalse(partial.exists())
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(destination.readBytes()))
    }

    @Test
    fun prefixOnDiskFeedsTheEarlyPlayDecision() {
        val directory = temporary.newFolder()
        writePrefix(directory, "show.mkv.part", ebmlHeader())
        writePrefix(directory, "show.webm.part", ebmlHeader() + "webm".encodeToByteArray())
        writePrefix(directory, "show.ts.part", byteArrayOf(0x47, 0, 0, 0))
        writePrefix(directory, "fast.mp4.part", iso(box("ftyp", "isom0000".encodeToByteArray()), box("moov", ByteArray(0))))
        writePrefix(directory, "tail.mp4.part", iso(box("ftyp", "isom0000".encodeToByteArray()), box("mdat", ByteArray(4))))

        assertTrue(decision(directory, "show.mkv").playable)
        assertTrue(decision(directory, "show.webm").playable)
        assertTrue(decision(directory, "show.ts").playable)
        assertTrue(decision(directory, "fast.mp4").playable)
        val tail = decision(directory, "tail.mp4")
        assertFalse(tail.playable)
        assertTrue(tail.playsWhenDownloadFinishes)
        assertEquals(PLAYS_WHEN_DOWNLOAD_FINISHES, "Plays when the download finishes.")

        val finished = earlyPlayDecision(
            bytesOnDisk = EARLY_PLAY_BYTES,
            fileName = "tail.mp4",
            prefix = readDownloadPrefix(directory, "tail.mp4", 64),
            downloadComplete = true,
        )
        assertTrue(finished.playable)
        assertFalse(finished.playsWhenDownloadFinishes)
    }

    private fun decision(directory: File, fileName: String): EarlyPlayDecision {
        val prefix = readDownloadPrefix(directory, fileName, PARTIAL_CLASSIFY_PREFIX_BYTES)
        assertTrue(prefix.isNotEmpty())
        return earlyPlayDecision(
            bytesOnDisk = EARLY_PLAY_BYTES,
            fileName = fileName,
            prefix = prefix,
            downloadComplete = false,
        )
    }

    private fun writePrefix(directory: File, name: String, bytes: ByteArray) {
        File(directory, name).writeBytes(bytes)
    }

    private fun readHeaders(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) error("socket closed before the response headers")
            buffer.write(next)
            val text = buffer.toByteArray().decodeToString()
            val marker = text.indexOf("\r\n\r\n")
            if (marker >= 0) return text.substring(0, marker)
            if (buffer.size() > 8 * 1024) error("response header is too large")
        }
    }

    private fun readFully(input: InputStream, destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val read = input.read(destination, offset, destination.size - offset)
            if (read < 0) error("response ended after $offset of ${destination.size} bytes")
            offset += read
        }
    }

    private fun ebmlHeader(): ByteArray = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val out = ByteArray(size)
        out[0] = (size ushr 24).toByte()
        out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte()
        out[3] = size.toByte()
        type.encodeToByteArray().copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun iso(vararg boxes: ByteArray): ByteArray {
        val out = ByteArray(boxes.sumOf { it.size })
        var offset = 0
        boxes.forEach { part ->
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}
