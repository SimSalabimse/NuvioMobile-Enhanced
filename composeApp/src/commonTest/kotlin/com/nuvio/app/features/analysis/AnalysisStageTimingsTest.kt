package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.serialization.LenientJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class AnalysisStageTimingsTest {
    @Test
    fun timingLineNamesEveryStageAndReachesTheDebugLog() {
        val id = "timing-test-file"
        val timings = AnalysisStageTimings(
            decodeMs = 11,
            sampleLoopMs = 22,
            fingerprintMs = 3,
            matchMs = 44,
            totalMs = 90,
        )
        assertEquals(
            "timings id=$id decodeMs=11 sampleLoopMs=22 fingerprintMs=3 matchMs=44 chaptersMs=0 chapterSource=- totalMs=90",
            timings.line(id),
        )
        InAppLogger.clear()
        logAnalysisStageTimings(id, timings)
        val logged = InAppLogger.entries.value.map { it.message }
        assertTrue(logged.any { it == timings.line(id) })
        val fromChapters = timings.copy(chaptersMs = 8, chapterSource = "chapters")
        assertEquals(
            "timings id=$id decodeMs=11 sampleLoopMs=22 fingerprintMs=3 matchMs=44 chaptersMs=8 chapterSource=chapters totalMs=90",
            fromChapters.line(id),
        )
    }

    @Test
    fun timingFieldsDoNotChangeTheStoredAnalysis() {
        val without = """
            {"durationMs":2400000,"energyStepMs":100,"energy":"AQID","integratedLufs":-23.5,
            "fingerprintStepMs":100,"head":"AAAA","tail":"BBBB","tailStartMs":1920000}
        """.trimIndent()
        val withTimings = """
            {"durationMs":2400000,"energyStepMs":100,"energy":"AQID","integratedLufs":-23.5,
            "fingerprintStepMs":100,"head":"AAAA","tail":"BBBB","tailStartMs":1920000,
            "decodeMs":1200,"sampleLoopMs":800,"fingerprintMs":40}
        """.trimIndent()
        val plain = LenientJson.decodeFromString(RawMediaAnalysis.serializer(), without)
        val timed = LenientJson.decodeFromString(RawMediaAnalysis.serializer(), withTimings)
        assertEquals(0L, plain.decodeMs)
        assertEquals(0L, plain.sampleLoopMs)
        assertEquals(0L, plain.fingerprintMs)
        assertEquals(1200L, timed.decodeMs)
        assertEquals(800L, timed.sampleLoopMs)
        assertEquals(40L, timed.fingerprintMs)
        assertEquals(0L, plain.chaptersMs)
        assertTrue(plain.chapters.isEmpty())
        val withChapters = """
            {"durationMs":2400000,"energyStepMs":100,"energy":"AQID","integratedLufs":-23.5,
            "fingerprintStepMs":100,"head":"AAAA","tail":"BBBB","tailStartMs":1920000,
            "decodeMs":1200,"sampleLoopMs":800,"fingerprintMs":40,"chaptersMs":6,
            "chapters":[{"title":"Intro","startMs":0,"endMs":90000}]}
        """.trimIndent()
        val chaptered = LenientJson.decodeFromString(RawMediaAnalysis.serializer(), withChapters)
        assertEquals(6L, chaptered.chaptersMs)
        assertEquals("Intro", chaptered.chapters.single().title)
        assertEquals(90_000L, chaptered.chapters.single().endMs)
        val storedPlain = plain.toStoredResult("show__s1e1", "episode.mkv", 5L, emptyMap())
        val storedTimed = timed.toStoredResult("show__s1e1", "episode.mkv", 5L, emptyMap())
        val storedChaptered = chaptered.toStoredResult("show__s1e1", "episode.mkv", 5L, emptyMap())
        assertEquals(storedPlain, storedTimed)
        assertEquals(storedPlain, storedChaptered)
        assertEquals(
            SeasonSegmentDetector.detect(storedPlain, emptyList()),
            SeasonSegmentDetector.detect(storedTimed, emptyList()),
        )
        assertEquals(null, storedTimed.introStartMs)
        assertEquals(null, storedTimed.introEndMs)
        assertEquals(null, storedTimed.creditsStartMs)
        assertEquals(null, storedTimed.creditsEndMs)
        assertEquals(null, storedTimed.recapStartMs)
        assertEquals(null, storedTimed.recapEndMs)
        assertEquals(2_400_000L, storedTimed.durationMs)
        assertEquals(1_920_000L, storedTimed.tailStartMs)
    }
}
