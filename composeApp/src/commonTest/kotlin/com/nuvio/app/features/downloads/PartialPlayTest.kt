package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartialPlayTest {
    @Test
    fun matroskaWebmAndMpegTsStartOnceEightMebibytesAreOnDisk() {
        val under = EARLY_PLAY_BYTES - 1L
        assertFalse(decision(under, "movie.mkv", ebml("matroska")).playable)
        assertTrue(decision(EARLY_PLAY_BYTES, "movie.mkv", ebml("matroska")).playable)
        assertTrue(decision(EARLY_PLAY_BYTES, "clip.webm", ebml("webm")).playable)
        assertEquals(PartialContainerKind.WebM, classifyPartialContainer("clip.webm", ebml("webm")))
        assertTrue(decision(EARLY_PLAY_BYTES, "show.ts", mpegTsPackets(3)).playable)
        assertFalse(decision(EARLY_PLAY_BYTES, "show.ts", mpegTsPackets(3)).playsWhenDownloadFinishes)
    }

    @Test
    fun faststartMp4AndMovStartOnlyWhenMoovPrecedesMdat() {
        val faststart = iso(box("ftyp", "isom".encodeToByteArray() + ByteArray(4)), box("moov", ByteArray(16)), box("mdat", ByteArray(32)))
        val decision = decision(EARLY_PLAY_BYTES, "movie.mp4", faststart)
        assertTrue(decision.playable)
        assertFalse(decision.playsWhenDownloadFinishes)
        assertEquals(PartialContainerKind.Mov, classifyPartialContainer("clip.mov", faststart))
        assertTrue(decision(EARLY_PLAY_BYTES, "clip.mov", faststart).playable)
        assertFalse(decision(EARLY_PLAY_BYTES - 1L, "movie.mp4", faststart).playable)
    }

    @Test
    fun nonFaststartMp4WaitsUntilTheFileIsComplete() {
        val tailMoov = iso(box("ftyp", "mp42".encodeToByteArray()), box("mdat", ByteArray(64)))
        val partial = decision(EARLY_PLAY_BYTES, "movie.mp4", tailMoov)
        assertFalse(partial.playable)
        assertTrue(partial.playsWhenDownloadFinishes)
        assertEquals(PLAYS_WHEN_DOWNLOAD_FINISHES, "Plays when the download finishes.")

        val finished = earlyPlayDecision(
            bytesOnDisk = EARLY_PLAY_BYTES,
            fileName = "movie.mp4",
            prefix = tailMoov,
            downloadComplete = true,
        )
        assertTrue(finished.playable)
        assertFalse(finished.playsWhenDownloadFinishes)
    }

    @Test
    fun moovHeaderBeforeALaterMdatIsFaststart() {
        val prefix = iso(box("ftyp", "isom".encodeToByteArray()), box("moov", ByteArray(64)))
        assertEquals(PrefixStart.CanStart, partialPrefixStart(PartialContainerKind.Mp4, prefix))
        assertFalse(decision(1024L, "movie.m4v", prefix).playable)
    }

    private fun decision(bytesOnDisk: Long, fileName: String, prefix: ByteArray): EarlyPlayDecision =
        earlyPlayDecision(bytesOnDisk, fileName, prefix, downloadComplete = false)
}

class PartialPlaybackContractTest {
    @Test
    fun playbackUrlStaysOnLoopbackAndKeepsOneWriter() {
        val url = loopbackPartialUrl(port = 43111, downloadId = "abc_1")
        assertEquals("http://127.0.0.1:43111/partial/abc_1", url)
        assertEquals("abc_1", partialPlaybackDownloadId(url))
        assertEquals(null, partialPlaybackDownloadId("file:///tmp/movie.mkv.part"))
        assertEquals(null, partialPlaybackDownloadId("http://10.0.0.2:43111/partial/abc_1"))
        assertEquals(null, partialPlaybackDownloadId("http://127.0.0.1:43111/other/abc_1"))

        val gate = SingleFileWriterGate()
        assertTrue(gate.acquire(DownloadWriterKind.Foreground))
        assertFalse(gate.acquire(DownloadWriterKind.Background))
        assertEquals(DownloadWriterKind.Foreground, gate.owner)
        gate.release(DownloadWriterKind.Foreground)
        assertTrue(gate.acquire(DownloadWriterKind.Background))
        gate.release(DownloadWriterKind.Background)
        assertEquals(null, gate.owner)
    }
}
