package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Downloads used to request subtitles from the stream hint only. That hint often has
 * no OpenSubtitles hash, so the saved sidecar can be a different release from the file.
 */
class DownloadedSubtitleIdentityTest {

    @Test
    fun streamHintWithoutAHashDoesNotIdentifyTheFile() {
        val hinted = SubtitleRequestExtras(filename = "episode.mkv", videoSize = 10L, videoHash = null)
        assertNull(hinted.videoHash)
        assertTrue(hinted.toPathSegment()?.contains("videoHash=") != true)
    }

    @Test
    fun downloadedFileHashReplacesADifferentStreamHint() {
        val extras = subtitleExtrasForDownloadedFile(
            filename = "episode.mkv",
            hintedHash = "aaaaaaaaaaaaaaaa",
            hintedSize = 10L,
            fileHash = "bbbbbbbbbbbbbbbb",
            fileSize = 200_000L,
        )
        assertEquals("bbbbbbbbbbbbbbbb", extras?.videoHash)
        assertEquals(200_000L, extras?.videoSize)
        assertEquals("episode.mkv", extras?.filename)
        assertTrue(extras?.toPathSegment().orEmpty().contains("videoHash=bbbbbbbbbbbbbbbb"))
    }

    @Test
    fun missingFileHashKeepsTheStreamHint() {
        val extras = subtitleExtrasForDownloadedFile(
            filename = "episode.mkv",
            hintedHash = "aaaaaaaaaaaaaaaa",
            hintedSize = 10L,
            fileHash = null,
            fileSize = null,
        )
        assertEquals("aaaaaaaaaaaaaaaa", extras?.videoHash)
        assertEquals(10L, extras?.videoSize)
    }

    @Test
    fun emptyFileHashIsTheSizeInHex() {
        val chunk = ByteArray(OpenSubtitlesHash.CHUNK_BYTES)
        assertEquals("0000000000020000", OpenSubtitlesHash.of(131_072L, chunk, chunk))
    }

    @Test
    fun firstChunkLongIsAddedToTheSize() {
        val head = ByteArray(OpenSubtitlesHash.CHUNK_BYTES)
        head[0] = 1
        val tail = ByteArray(OpenSubtitlesHash.CHUNK_BYTES)
        assertEquals("0000000000020001", OpenSubtitlesHash.of(131_072L, head, tail))
        assertNull(OpenSubtitlesHash.of(100L, head, tail))
    }
}
