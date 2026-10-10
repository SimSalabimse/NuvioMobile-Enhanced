package com.nuvio.app.features.downloads

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class PartialFileRangeReaderTest {
    @Test
    fun responseAdvertisesTheFullSizeForARangeInsideTheFile() {
        val head = planPartialRangeResponse(
            method = "GET",
            rangeHeader = "bytes=90-149",
            advertisedTotal = 1_000L,
            available = 100L,
            downloadRunning = true,
            contentType = "video/x-matroska",
        )
        assertEquals(206, head.status)
        assertEquals("60", header(head, "Content-Length"))
        assertEquals("bytes 90-149/1000", header(head, "Content-Range"))
        assertEquals("bytes", header(head, "Accept-Ranges"))
        assertTrue(formatHttpResponseHead(head).startsWith("HTTP/1.1 206 Partial Content\r\n"))
    }

    @Test
    fun fullReadAdvertisesTheKnownSizeInsteadOfTheBytesOnDisk() {
        val head = planPartialRangeResponse(
            method = "GET",
            rangeHeader = null,
            advertisedTotal = 5_000L,
            available = 100L,
            downloadRunning = true,
            contentType = "video/mp4",
        )
        assertEquals(200, head.status)
        assertEquals("5000", header(head, "Content-Length"))
        assertEquals(0L, head.bodyStart)
        assertEquals(4_999L, head.bodyEndInclusive)
    }

    @Test
    fun rangePastTheGrowingPrefixReturns416WithoutWaiting() {
        val total = 4_700_000_000L
        val available = 549L * 1_000_000L
        val suffix = planPartialRangeResponse(
            method = "GET",
            rangeHeader = "bytes=-1048576",
            advertisedTotal = total,
            available = available,
            downloadRunning = true,
            contentType = "video/x-matroska",
        )
        assertEquals(416, suffix.status)
        assertFalse(suffix.includeBody)
        assertEquals("0", header(suffix, "Content-Length"))
        assertEquals("bytes */$total", header(suffix, "Content-Range"))
        assertEquals(0L, suffix.bodyStart)

        val start = available + 1_000_000L
        val bounded = planPartialRangeResponse(
            method = "GET",
            rangeHeader = "bytes=$start-${start + 4095}",
            advertisedTotal = total,
            available = available,
            downloadRunning = true,
            contentType = "video/x-matroska",
        )
        assertEquals(416, bounded.status)
        assertFalse(bounded.includeBody)
        assertEquals("0", header(bounded, "Content-Length"))
        assertEquals("bytes */$total", header(bounded, "Content-Range"))
    }

    @Test
    fun readPastThePausedPrefixSnapsBackInsteadOfWaiting() {
        val head = planPartialRangeResponse(
            method = "GET",
            rangeHeader = "bytes=500-900",
            advertisedTotal = 2_000L,
            available = 120L,
            downloadRunning = false,
            contentType = "video/mp4",
        )
        assertEquals(416, head.status)
        assertEquals("bytes */2000", header(head, "Content-Range"))
        assertFalse(head.includeBody)
    }

    @Test
    fun openRangeWhileRunningStillNamesTheFullFile() {
        val request = parseHttpRequestHead(
            "GET /partial/abc_1 HTTP/1.1\r\nHost: 127.0.0.1:9\r\nRange: bytes=10-\r\n\r\n",
        )
        assertEquals("abc_1", request?.target?.let(::partialIdFromTarget))
        val head = planPartialRangeResponse(
            method = request!!.method,
            rangeHeader = request.rangeHeader,
            advertisedTotal = 80L,
            available = 20L,
            downloadRunning = true,
            contentType = "video/mp2t",
        )
        assertEquals("bytes 10-79/80", header(head, "Content-Range"))
        assertEquals("70", header(head, "Content-Length"))
    }

    @Test
    fun readerWaitsForBytesPastThePrefixAndDoesNotMoveTheDownload() = runTest {
        val source = FakeGrowingSource(advertisedTotal = 1_000L)
        source.append(ByteArray(100) { it.toByte() })
        val reader = PartialFileRangeReader(source)
        val collected = ArrayList<Byte>()
        val job = launch {
            reader.readBody(start = 90L, endInclusive = 109L) { chunk ->
                collected.addAll(chunk.toList())
            }
        }
        yield()
        assertEquals(10, collected.size)
        assertFalse(job.isCompleted)
        assertEquals(100, source.writeCursor)
        assertEquals(listOf(90L), source.readOffsets)

        source.append(ByteArray(20) { (100 + it).toByte() })
        job.join()

        assertEquals((90..109).map { it.toByte() }, collected)
        assertEquals(120, source.writeCursor)
        assertEquals(listOf(90L, 100L), source.readOffsets)
    }

    private fun header(head: RangeResponseHead, name: String): String? =
        head.headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

private class FakeGrowingSource(
    private val advertisedTotal: Long?,
) : GrowingByteSource {
    private val data = ArrayList<Byte>()
    private val waiters = ArrayList<Continuation<Unit>>()
    var running: Boolean = true
    var writeCursor: Int = 0
        private set
    val readOffsets = ArrayList<Long>()

    fun append(bytes: ByteArray) {
        bytes.forEach { data.add(it) }
        writeCursor = data.size
        val pending = waiters.toList()
        waiters.clear()
        pending.forEach { waiter -> waiter.resume(Unit) }
    }

    override fun advertisedTotalBytes(): Long? = advertisedTotal

    override fun bytesAvailable(): Long = data.size.toLong()

    override fun isDownloadRunning(): Boolean = running

    override suspend fun awaitAvailable(minimumBytes: Long): Long {
        if (bytesAvailable() >= minimumBytes || !running) return bytesAvailable()
        suspendCoroutine { continuation -> waiters.add(continuation) }
        return bytesAvailable()
    }

    override fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int {
        readOffsets += offset
        val start = offset.toInt()
        if (start >= data.size || length <= 0) return 0
        val count = minOf(length, data.size - start)
        for (index in 0 until count) {
            destination[destinationOffset + index] = data[start + index]
        }
        return count
    }
}

internal fun ebml(doctype: String): ByteArray =
    byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + doctype.encodeToByteArray()

internal fun mpegTsPackets(count: Int): ByteArray {
    val packet = ByteArray(188)
    packet[0] = 0x47
    return ByteArray(count * 188) { index -> packet[index % 188] }
}

internal fun box(type: String, payload: ByteArray): ByteArray {
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

internal fun iso(vararg boxes: ByteArray): ByteArray {
    val size = boxes.sumOf { it.size }
    val out = ByteArray(size)
    var offset = 0
    boxes.forEach { part ->
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}
