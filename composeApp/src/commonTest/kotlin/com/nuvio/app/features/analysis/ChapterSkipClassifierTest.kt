package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChapterSkipClassifierTest {
    private val runtimeMs = 40 * 60_000L

    private fun segment(title: String, startMs: Long, endMs: Long, durationMs: Long = runtimeMs) =
        classifyChapters(listOf(FileChapter(title, startMs, endMs)), durationMs).singleOrNull()

    @Test
    fun goodEnglishTitlesMapToOneKind() {
        val cases = listOf(
            "Intro" to ChapterSkipKind.Intro,
            "  OPENING CREDITS " to ChapterSkipKind.Intro,
            "1. Intro" to ChapterSkipKind.Intro,
            "The Intro" to ChapterSkipKind.Intro,
            "Opening_Credits" to ChapterSkipKind.Intro,
            "Intro:" to ChapterSkipKind.Intro,
            "OP" to ChapterSkipKind.Intro,
            "introduction" to ChapterSkipKind.Intro,
            "Opening Theme" to ChapterSkipKind.Intro,
            "Title Sequence" to ChapterSkipKind.Intro,
            "Previously On" to ChapterSkipKind.Recap,
            "Recap" to ChapterSkipKind.Recap,
            "Last Time" to ChapterSkipKind.Recap,
            "Credits" to ChapterSkipKind.Credits,
            "End Credits" to ChapterSkipKind.Credits,
            "Outro" to ChapterSkipKind.Credits,
            "ED" to ChapterSkipKind.Credits,
            "Closing Credits" to ChapterSkipKind.Credits,
            "Next Time On" to ChapterSkipKind.Preview,
            "Preview" to ChapterSkipKind.Preview,
            "Coming Up" to ChapterSkipKind.Preview,
        )
        cases.forEach { (title, kind) ->
            val range = when (kind) {
                ChapterSkipKind.Intro -> 20_000L to 110_000L
                ChapterSkipKind.Recap -> 0L to 20_000L
                ChapterSkipKind.Credits -> 2_310_000L to runtimeMs
                ChapterSkipKind.Preview -> 2_370_000L to runtimeMs
            }
            val found = segment(title, range.first, range.second)
            assertEquals(kind, found?.kind, title)
            assertEquals(range.first, found?.startMs, title)
            assertEquals(range.second, found?.endMs, title)
        }
    }

    @Test
    fun junkAmbiguousAndNonEnglishTitlesAreIgnored() {
        val ignored = listOf(
            "Chapter 1",
            "Part 2",
            "Scene 12",
            "Cold Open",
            "Teaser",
            "Finale",
            "End",
            "Start",
            "Prologue",
            "Epilogue",
            "Theme",
            "Music",
            "Intro song",
            "The Duel",
            "Générique",
            "Ouverture",
            "Vorspann",
            "Précédemment",
            "エンディング",
            "オープニング",
            "Capitulo 1",
            "",
            "   ",
        )
        ignored.forEach { title ->
            assertNull(segment(title, 0L, 60_000L), title)
        }
    }

    @Test
    fun implausibleRangesAreRejected() {
        assertNull(segment("Intro", 0L, 0L, durationMs = 0L), "zero length")
        assertNull(segment("Intro", 30_000L, 10_000L, durationMs = 0L), "negative length")
        assertNull(segment("Intro", -1L, 30_000L), "negative start")
        assertNull(segment("Intro", 0L, MAX_INTRO_PLUS), "intro longer than 6 minutes")
        assertEquals(ChapterSkipKind.Intro, segment("Intro", 0L, 6 * 60_000L)?.kind)
        val short = 10 * 60_000L
        assertNull(segment("Intro", 5 * 60_000L, 5 * 60_000L + 30_000L, short), "intro past 40% of a short runtime")
        assertNull(segment("Credits", 0L, 60_000L), "credits at the start")
        assertNull(segment("Credits", runtimeMs * 3 / 10, runtimeMs * 3 / 10 + 60_000L), "credits in the first 40%")
        assertEquals(
            ChapterSkipKind.Credits,
            segment("Credits", short * 2 / 5, short, short)?.kind,
            "credits that start at exactly 40% and run to the end",
        )
        assertNull(segment("Credits", short * 2 / 5 - 1, short, short), "one millisecond inside the first 40%")
        assertNull(
            segment("Credits", runtimeMs - 6 * 60_000L, runtimeMs - 5 * 60_000L),
            "credits that leave more than four minutes",
        )
        assertNull(segment("Recap", 5 * 60_000L, 6 * 60_000L), "recap away from the start")
        assertNull(segment("Preview", 60_000L, 90_000L), "preview near the start")
        assertNull(segment("Credits", 2_310_000L, runtimeMs, durationMs = 0L), "credits with no runtime")
        assertEquals(ChapterSkipKind.Intro, segment("Intro", 0L, 30_000L, durationMs = 0L)?.kind)
    }

    @Test
    fun firstPlausibleChapterOfAKindWinsAndTheEndCanComeFromTheNextChapter() {
        val chapters = listOf(
            FileChapter("Intro", 0L, 30_000L),
            FileChapter("Intro", 40_000L, 70_000L),
            FileChapter("Episode", 30_000L, 2_310_000L),
            FileChapter("Credits", 2_310_000L, 0L),
        )
        val found = classifyChapters(chapters, runtimeMs)
        val intro = found.first { it.kind == ChapterSkipKind.Intro }
        assertEquals(0L, intro.startMs)
        assertEquals(30_000L, intro.endMs)
        val credits = found.first { it.kind == ChapterSkipKind.Credits }
        assertEquals(2_310_000L, credits.startMs)
        assertEquals(runtimeMs, credits.endMs)
        val buried = classifyChapters(
            listOf(
                FileChapter("Intro", 0L, 90_000L),
                FileChapter("Previously", 0L, 30_000L),
                FileChapter("Credits", 2_310_000L, runtimeMs),
                FileChapter("Preview", 2_320_000L, 2_350_000L),
            ),
            runtimeMs,
        )
        assertEquals(setOf(ChapterSkipKind.Intro, ChapterSkipKind.Credits), buried.map { it.kind }.toSet())
    }

    @Test
    fun aFullMarkerListSkipsBothPrints() {
        ChapterSkipClassifier.clear()
        try {
            val json = """
                [
                  {"title":"Previously","startMs":0,"endMs":20000},
                  {"title":"Intro","startMs":20000,"endMs":110000},
                  {"title":"Episode","startMs":110000,"endMs":2310000},
                  {"title":"Credits","startMs":2310000,"endMs":2400000}
                ]
            """.trimIndent()
            assertEquals("head,tail", ChapterSkipClassifier.fingerprintSkip(json, runtimeMs))
            assertEquals("", ChapterSkipClassifier.fingerprintSkip("[]", runtimeMs))
            assertEquals("", ChapterSkipClassifier.fingerprintSkip("not-json", runtimeMs))
        } finally {
            ChapterSkipClassifier.clear()
        }
    }

    private companion object {
        const val MAX_INTRO_PLUS = 6 * 60_000L + 1
    }
}
