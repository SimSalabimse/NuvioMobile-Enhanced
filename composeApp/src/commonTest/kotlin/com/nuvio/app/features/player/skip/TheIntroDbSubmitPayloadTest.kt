package com.nuvio.app.features.player.skip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TheIntroDbSubmitPayloadTest {
    @Test
    fun `an absent segment keeps the media length and sends zero times`() {
        val body = theIntroDbSubmitPayload(
            tmdbId = 1396,
            imdbId = "tt0903747",
            type = "tv",
            segment = "outro",
            season = 1,
            episode = 2,
            startSec = 0.0,
            endSec = 0.0,
            videoDurationMs = 2_700_000,
        )
        val json = Json.parseToJsonElement(checkNotNull(body)).jsonObject
        assertEquals(0L, json.getValue("start_ms").jsonPrimitive.long)
        assertEquals(0L, json.getValue("end_ms").jsonPrimitive.long)
        assertEquals(2_700_000L, json.getValue("video_duration_ms").jsonPrimitive.long)
        assertEquals("credits", json.getValue("segment").jsonPrimitive.content)
        assertEquals(1, json.getValue("season").jsonPrimitive.int)
        assertEquals(2, json.getValue("episode").jsonPrimitive.int)
    }

    @Test
    fun `an absent segment omits an unknown media length instead of sending zero`() {
        val body = theIntroDbSubmitPayload(
            tmdbId = 1396,
            type = "movie",
            segment = "intro",
            startSec = 0.0,
            endSec = 0.0,
            videoDurationMs = 0L,
        )
        val json = Json.parseToJsonElement(checkNotNull(body)).jsonObject
        assertEquals(0L, json.getValue("start_ms").jsonPrimitive.long)
        assertEquals(0L, json.getValue("end_ms").jsonPrimitive.long)
        assertFalse(json.containsKey("video_duration_ms"))
        assertFalse(json.containsKey("season"))
    }

    @Test
    fun `a real range still sends its times with the media length`() {
        val body = theIntroDbSubmitPayload(
            tmdbId = 1396,
            type = "tv",
            segment = "recap",
            season = 1,
            episode = 1,
            startSec = 12.0,
            endSec = 40.5,
            videoDurationMs = 2_700_000,
        )
        val json = Json.parseToJsonElement(checkNotNull(body)).jsonObject
        assertEquals(12_000L, json.getValue("start_ms").jsonPrimitive.long)
        assertEquals(40_500L, json.getValue("end_ms").jsonPrimitive.long)
        assertEquals(2_700_000L, json.getValue("video_duration_ms").jsonPrimitive.long)
    }

    @Test
    fun `only TheIntroDB can submit an absent segment`() {
        assertEquals(0.0 to 0.0, resolveIntroSubmitTimes(IntroSubmitService.THE_INTRODB, absentSegment = true, startTimeStr = "00:01:00", endTimeStr = "00:02:00"))
        assertNull(resolveIntroSubmitTimes(IntroSubmitService.INTRODB, absentSegment = true, startTimeStr = "00:00:00", endTimeStr = "00:00:00"))
        assertNull(resolveIntroSubmitTimes(IntroSubmitService.THE_INTRODB, absentSegment = false, startTimeStr = "00:00:00", endTimeStr = "00:00:00"))
        assertEquals(30.0 to 90.0, resolveIntroSubmitTimes(IntroSubmitService.INTRODB, absentSegment = false, startTimeStr = "00:00:30", endTimeStr = "00:01:30"))
    }
}
