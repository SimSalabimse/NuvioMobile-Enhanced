package com.nuvio.app.features.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class LocalVideoHashTest {

    @Test
    fun localFileHashMatchesTheOpenSubtitlesSum() = runBlocking {
        val file = File.createTempFile("nuvio-hash", ".bin")
        file.deleteOnExit()
        val bytes = ByteArray(OpenSubtitlesHash.CHUNK_BYTES * 2)
        bytes[0] = 1
        file.writeBytes(bytes)
        val hashed = computeLocalVideoHash(file.absolutePath)
        assertEquals("0000000000020001", hashed?.first)
        assertEquals(bytes.size.toLong(), hashed?.second)
    }

    @Test
    fun fileUnder128KbIsNotHashed() = runBlocking {
        val file = File.createTempFile("nuvio-hash-small", ".bin")
        file.deleteOnExit()
        file.writeBytes(ByteArray(100))
        assertNull(computeLocalVideoHash(file.absolutePath))
    }
}
