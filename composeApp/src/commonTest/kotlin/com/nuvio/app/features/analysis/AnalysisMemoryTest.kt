package com.nuvio.app.features.analysis

import com.nuvio.app.core.ui.heroDecodePixels
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisMemoryTest {
    @Test
    fun buffersReleasedWhenGatePausesOrFinishes() {
        val buffers = AnalysisWorkingBuffers()
        buffers.holdPcm(80L * 1024 * 1024)
        buffers.holdFingerprint(8L * 1024 * 1024)
        assertEquals(ANALYSIS_PCM_BYTE_CAP, buffers.pcmBytes)
        assertEquals(ANALYSIS_FINGERPRINT_BYTE_CAP, buffers.fingerprintBytes)
        buffers.applyGate(paused = false)
        assertEquals(ANALYSIS_PCM_BYTE_CAP + ANALYSIS_FINGERPRINT_BYTE_CAP, buffers.retainedBytes)
        buffers.applyGate(paused = true)
        assertEquals(0L, buffers.retainedBytes)
        buffers.holdPcm(4_096)
        buffers.holdFingerprint(128)
        buffers.release()
        assertEquals(0L, buffers.retainedBytes)
    }

    @Test
    fun localFileUsesSmallDemuxerCache() {
        val fileUrl = demuxerCacheOptions("file:///var/mobile/Containers/Data/movie.mkv")
        assertEquals("no", fileUrl.cache)
        assertEquals(8L * 1024 * 1024, fileUrl.maxBytes)
        assertEquals(2L * 1024 * 1024, fileUrl.maxBackBytes)
        val path = demuxerCacheOptions("/private/var/mobile/movie.mkv")
        assertEquals("no", path.cache)
        assertEquals(8L * 1024 * 1024, path.maxBytes)
        val stream = demuxerCacheOptions("https://cdn.example/title.mkv")
        assertEquals("auto", stream.cache)
        assertEquals(64L * 1024 * 1024, stream.maxBytes)
        assertEquals(32L * 1024 * 1024, stream.maxBackBytes)
    }

    @Test
    fun memoryLineReportsFootprintMegabytes() {
        assertEquals(
            "memory event=playback-start footprintMb=853",
            memoryFootprintLine("playback-start", 853L * 1024 * 1024 + 400),
        )
        assertEquals(
            "memory event=warning footprintMb=-1",
            memoryFootprintLine("warning", null),
        )
    }

    @Test
    fun heroDecodeCapsTheLongEdge() {
        assertEquals(1280 to 720, heroDecodePixels(3840, 2160))
        assertEquals(800 to 450, heroDecodePixels(800, 450))
    }
}
