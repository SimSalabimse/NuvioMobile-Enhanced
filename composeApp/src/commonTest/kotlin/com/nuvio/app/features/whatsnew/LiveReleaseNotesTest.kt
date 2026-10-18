package com.nuvio.app.features.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveReleaseNotesTest {
    @Test
    fun parsesGeneratedBodyAndCleansText() {
        val body = """
            ### New
            - Skip prompt after tapping \*Skip\*.
            - Uses [TheIntroDB](https://theintrodb.org) data.

            ### Fixed
            - Tab bar didn\'t respond
              in collapsed mode.

            Unsigned sideload IPA 0.5.10.4 (169). Sign with SideStore.
        """.trimIndent()
        val r = LiveReleaseNotes.parseRelease("0.5.10.4", body, "2026-10-16T10:00:00Z")!!
        assertEquals(listOf("Skip prompt after tapping *Skip*.", "Uses TheIntroDB data."), r.new)
        assertEquals(listOf("Tab bar didn't respond in collapsed mode."), r.fixed)
        assertEquals("2026-10-16", r.date)
    }

    @Test
    fun legacyColonHeadingsAndFooterOnly() {
        val r = LiveReleaseNotes.parseRelease("0.5.9.1", "Intro line\n\nNew since 0.5.9 (152):\n- Fixed HTTP 400.", null)!!
        assertEquals(listOf("Fixed HTTP 400."), r.new)
        assertNull(LiveReleaseNotes.parseRelease("x", "Unsigned sideload IPA only.", null))
    }

    @Test
    fun versionFromTag() {
        assertEquals("0.5.10.3", LiveReleaseNotes.versionFromTag("sideload-0.5.10.3-7e3c0e04"))
    }

    @Test
    fun liveOverridesBundledAndSortsNewestFirst() {
        val merged = ReleaseNotes.merge(
            live = listOf(BundledRelease("0.5.10.3", null, new = listOf("live"))),
            bundled = listOf(BundledRelease("0.5.10.3", "2026-10-14", new = listOf("b")), BundledRelease("0.5.10.4", "d")),
        )
        assertEquals(listOf("0.5.10.4", "0.5.10.3"), merged.map { it.version })
        assertEquals(listOf("live"), merged[1].new)
        assertEquals("2026-10-14", merged[1].date)
    }

    @Test
    fun perBuildEntriesKeepTheirNotesAndSortByBuild() {
        val merged = ReleaseNotes.merge(
            live = listOf(BundledRelease("0.6.1.1", null, new = listOf("live"))),
            bundled = listOf(
                BundledRelease("0.6.1.1", "d", new = listOf("180"), build = 180),
                BundledRelease("0.6.1.1", "d", new = listOf("181"), build = 181),
                BundledRelease("0.5.10.4", "d", build = 172),
            ),
        )
        assertEquals(listOf("0.6.1.1(181)", "0.6.1.1(180)", "0.5.10.4(172)"), merged.map { it.key })
        assertEquals("0.6.1.1 (181)", merged[0].label)
    }

    @Test
    fun buildsSinceTheLastOneSeenAreUnseen() {
        fun build(version: String, build: Int?) = BundledRelease(version, null, build = build)
        val current = "0.6.1.1(181)"
        assertEquals(true, ReleaseNotes.isUnseen(build("0.6.1.1", 181), "0.6.1.1(179)", current))
        assertEquals(true, ReleaseNotes.isUnseen(build("0.6.1.1", 180), "0.6.1.1(179)", current))
        assertEquals(false, ReleaseNotes.isUnseen(build("0.6.1.1", 179), "0.6.1.1(179)", current))
        assertEquals(true, ReleaseNotes.isUnseen(build("0.6.1.1", 173), "0.5.10.4(172)", current))
        assertEquals(false, ReleaseNotes.isUnseen(build("0.5.10.3", null), "0.5.10.4(172)", current))
        assertEquals(false, ReleaseNotes.isUnseen(build("0.6.1.1", 182), "0.6.1.1(179)", current))
        assertEquals(false, ReleaseNotes.isUnseen(build("0.6.1.1", 181), null, current))
    }
}
