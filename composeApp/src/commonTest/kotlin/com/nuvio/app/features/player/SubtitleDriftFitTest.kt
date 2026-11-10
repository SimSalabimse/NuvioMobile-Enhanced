package com.nuvio.app.features.player

import com.nuvio.app.core.logging.InAppLogger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleDriftFitTest {
    @Test
    fun progressiveLatenessSpeedsCuesUpAndLandsOnTheAnchors() {
        val end = 2_400_000L
        val late = 8_000L
        val anchors = listOf(
            SubtitleAnchor(0L, 0L),
            SubtitleAnchor(end / 2, end / 2 + late / 2),
            SubtitleAnchor(end, end + late),
        )
        val fit = fitSubtitleAnchors(anchors)
        assertTrue(fit.confident)
        assertTrue(fit.subSpeed < 1.0)
        val properties = mpvPropertyValues(fit)
        assertTrue(properties.containsKey("sub-speed"))
        assertTrue(properties.containsKey("sub-delay"))
        assertEquals(fit.subSpeed, properties.getValue("sub-speed"))
        anchors.forEach { anchor ->
            val shown = shownSubtitleMs(anchor.subtitleMs, fit)
            assertTrue(abs(shown - anchor.videoMs) < 50.0, "shown=$shown video=${anchor.videoMs}")
        }
    }

    @Test
    fun aConstantOffsetSetsDelayOnceAndDoesNotInventAFrameRate() {
        val anchors = listOf(
            SubtitleAnchor(60_000L, 62_000L),
            SubtitleAnchor(600_000L, 602_000L),
            SubtitleAnchor(1_200_000L, 1_202_000L),
        )
        val fit = fitSubtitleAnchors(anchors)
        assertTrue(fit.confident)
        assertEquals(1.0, fit.subSpeed)
        assertEquals(-2_000L, fit.subDelayMs)
        val withUser = withUserDelay(fit, userDelayMs = 250L)
        assertEquals(-1_750L, withUser.subDelayMs)
        assertEquals(-1_750L, withUserDelay(fit, userDelayMs = 250L).subDelayMs)
        assertEquals(1.0, resetSubtitleTrack().subSpeed)
        assertEquals(0L, resetSubtitleTrack().subDelayMs)
    }

    @Test
    fun noisyAnchorsStillFitAndAWrongCutDoesNot() {
        val noisy = listOf(
            SubtitleAnchor(0L, 40L),
            SubtitleAnchor(900_000L, 900_080L),
            SubtitleAnchor(1_800_000L, 1_803_040L),
        )
        val fit = fitSubtitleAnchors(noisy)
        assertTrue(fit.confident)
        assertTrue(fit.subSpeed < 1.0)
        val wrongCut = listOf(
            SubtitleAnchor(0L, 0L),
            SubtitleAnchor(120_000L, 120_000L),
            SubtitleAnchor(2_000_000L, 2_040_000L),
        )
        val rejected = fitSubtitleAnchors(wrongCut)
        assertEquals(false, rejected.confident)
        assertEquals(1.0, rejected.subSpeed)
        assertEquals(0L, rejected.subDelayMs)
    }

    @Test
    fun providerComesFromMetadataAndUnknownStaysUnknown() {
        val named = subtitleSourceLabel("OpenSubtitles", "The.Mentalist.S03E04")
        assertEquals("OpenSubtitles", named.provider)
        assertEquals("The.Mentalist.S03E04", named.release)
        val unknown = subtitleSourceLabel(null, "https://example.test/file.srt")
        assertEquals("Unknown", unknown.provider)
        assertEquals(null, unknown.release)
        val blank = subtitleSourceLabel("  ", "web-release")
        assertEquals("Unknown", blank.provider)
        val text = subtitleSyncExportText(unknown, MpvSubtitleProperties(1.0, 0L, false), 0L, 0)
        assertTrue(!text.contains("http"))
        assertTrue(!text.contains("://"))
        assertTrue(text.contains("provider=Unknown"))
        val plain = subtitleSourceLabel("OpenSubtitles", "Episode.srt")
        assertEquals("Episode.srt", plain.release)
        val raw = "note https://user:pass@addon.example/a/b?api_key=secret Episode.srt"
        val redacted = redactSubtitleSyncExport(raw)
        assertTrue(redacted.contains("Episode.srt"))
        assertFalse(redacted.contains("addon.example"))
        assertFalse(redacted.contains("api_key"))
        assertFalse(redacted.contains("user:pass"))
        assertFalse(redacted.contains("https://"))
    }

    @Test
    fun clearingSessionMessagesClearsTheSyncExportToo() {
        InAppLogger.clear()
        InAppLogger.info("Analysis", "session-line")
        SubtitleSyncExportLog.record(
            "OpenSubtitles",
            "Episode.srt",
            MpvSubtitleProperties(0.99, -400L, confident = true),
            userDelayMs = 250L,
            anchorCount = 3,
        )
        assertTrue(InAppLogger.dump().contains("session-line"))
        assertTrue(SubtitleSyncExportLog.exportText().contains("OpenSubtitles"))
        clearSessionMessages()
        assertEquals("", InAppLogger.dump())
        assertFalse(SubtitleSyncExportLog.exportText().contains("OpenSubtitles"))
        assertFalse(SubtitleSyncExportLog.exportText().contains("0.99"))
    }
}
