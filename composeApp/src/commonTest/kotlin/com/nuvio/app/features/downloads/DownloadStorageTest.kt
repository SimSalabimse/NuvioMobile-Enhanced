package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadStorageTest {
    private fun item(id: String, file: String, season: Int?, episode: Int?, parent: String = "tt1") = DownloadItem(
        id = id, contentType = "series", parentMetaId = parent, parentMetaType = "series", videoId = id,
        title = "Show", seasonNumber = season, episodeNumber = episode, streamTitle = "", providerName = "",
        sourceUrl = "https://x", fileName = file, status = DownloadStatus.Completed,
        createdAtEpochMs = 0L, updatedAtEpochMs = 0L,
    )

    @Test
    fun `sizes come from the file its part and subtitles and orphans are only leftover nuvio files`() {
        val a = "Show S01E01_mabc123_1.mkv"
        val b = "Show S02E01_mabc124_2.mkv"
        val items = listOf(item("a", a, 1, 1), item("b", b, 2, 1))
        val files = listOf(
            DiskFile(a, 100L),
            DiskFile("$a.subtitles", 5L),
            DiskFile("$b.part", 300L),
            DiskFile("Other S01E03_mabc125_3.mkv.part", 40L),
            DiskFile("Profile2 Movie_mabc126_4.mp4", 70L),
            DiskFile("holiday.mov", 999L),
        )
        val summary = summarizeDownloadStorage(
            items, files, setOf("Profile2 Movie_mabc126_4.mp4"), VolumeSpace(availableBytes = 1000L, totalBytes = 5000L),
        )
        assertEquals(475L, summary.nuvioBytes)
        assertEquals(405L, summary.titles.single().bytes)
        assertEquals(listOf(2, 1), summary.titles.single().seasons.map { it.seasonNumber })
        assertEquals(listOf("Other S01E03_mabc125_3.mkv.part"), summary.orphans.map { it.name })
        assertEquals(5000L - 1000L - 475L - 40L, summary.otherBytes)
    }

    @Test
    fun `stored file names are read from every profile payload`() {
        val names = storedFileNames(listOf("""{"items":[{"id":"x","fileName":"A_mabc123_1.mkv"}]}""", """{"items":[]}"""))
        assertEquals(setOf("A_mabc123_1.mkv"), names)
    }

    @Test
    fun `space check keeps a margin`() {
        val gib = 1024L * 1024L * 1024L
        val mib = 1024L * 1024L
        assertTrue(lacksSpaceFor(null, VolumeSpace(availableBytes = 400 * mib, totalBytes = 64 * gib)))
        assertTrue(lacksSpaceFor(2 * gib, VolumeSpace(availableBytes = 2 * gib, totalBytes = 64 * gib)))
        assertFalse(lacksSpaceFor(gib / 2, VolumeSpace(availableBytes = 2 * gib, totalBytes = 64 * gib)))
        assertFalse(lacksSpaceFor(10 * gib, null))
    }

    @Test
    fun `reserve is 5 percent between 500 MB and 1 GB`() {
        val mb = 1_000_000L
        val gb = 1_000_000_000L
        assertEquals(500 * mb, downloadSpaceReserve(null))
        assertEquals(500 * mb, downloadSpaceReserve(4 * gb))
        assertEquals(gb, downloadSpaceReserve(40 * gb))
    }

    @Test
    fun `a 3_5 GB episode fits in 4 GB free and unknown sizes are not blocked up front`() {
        val mb = 1_000_000L
        val gb = 1_000_000_000L
        val volume = VolumeSpace(availableBytes = 4 * gb, totalBytes = 256 * gb)
        assertNull(spaceShortfallBeforeStart(3500 * mb, 3500 * mb, volume))
        assertNull(spaceShortfallBeforeStart(null, null, volume))
        val tooBig = spaceShortfallBeforeStart(4 * gb, 4 * gb, volume)
        assertEquals(4 * gb + 500 * mb, tooBig?.neededBytes)
        assertEquals("Needs 4.5 GB, 4.0 GB free. Free up space, then tap Retry.", downloadSpaceMessage(tooBig!!))
        assertNull(spaceRunningOut(volume))
        assertEquals("Only 400.0 MB free. Free up space, then tap Retry.", downloadSpaceMessage(spaceRunningOut(VolumeSpace(400 * mb, 256 * gb))!!))
    }
}
