package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bokmål codes from add-ons (nb, nob) used to miss the Norwegian preference ("no"),
 * so those tracks were not selected for a stream or a download. Nynorsk stays its own code.
 */
class NorwegianSubtitleLanguageTest {

    @Test
    fun bokmalCodesMatchTheNorwegianPreference() {
        for (code in listOf("nb", "nob", "no", "nor", "NB-NO", "Norwegian", "Norwegian Bokmål", "norsk", "bokmål")) {
            assertTrue(
                languageMatchesPreference(code, "no"),
                "$code should match Norwegian",
            )
            assertTrue(
                SubtitleLanguageMatching.matchesLanguageCode(code, "no"),
                "$code should match Norwegian in the subtitle matcher",
            )
        }
    }

    @Test
    fun nynorskStaysDistinctFromBokmal() {
        for (code in listOf("nn", "nno", "Norwegian Nynorsk", "nynorsk", "norsk nynorsk")) {
            assertEquals("nn", normalizeLanguageCode(code), code)
            assertEquals("nn", SubtitleLanguageMatching.normalizeLanguageCode(code), code)
            assertTrue(languageMatchesPreference(code, "no"), "$code should still be offered for Norwegian")
            assertTrue(SubtitleLanguageMatching.matchesLanguageCode(code, "no"), code)
            assertFalse(languageMatchesPreference("no", code), "$code must not swallow Bokmål")
            assertFalse(SubtitleLanguageMatching.matchesLanguageCode("nb", code), code)
        }
        assertFalse(languageMatchesPreference("nb", "nn"))
        assertFalse(SubtitleLanguageMatching.matchesLanguageCode("no", "nn"))
    }

    @Test
    fun downloadedAndStreamedTracksUseTheSameNorwegianMatch() {
        val samples = listOf("nb", "nob", "no", "nor", "nn", "nno", "norsk", "nynorsk")
        for (sample in samples) {
            assertEquals(
                languageMatchesPreference(sample, "no"),
                SubtitleLanguageMatching.matchesLanguageCode(sample, "no"),
                sample,
            )
        }
    }

    @Test
    fun norwegianPreferencePrefersBokmalAndStillListsNynorsk() {
        val tracks = listOf(
            subtitleTrack(index = 0, language = "nn", label = "Nynorsk"),
            subtitleTrack(index = 1, language = "nb", label = "Bokmål"),
        )
        assertEquals(
            1,
            findPreferredSubtitleTrackIndex(tracks, listOf("no"), SubtitleAutoSelectionMode.NORMAL_ONLY),
        )

        val addons = listOf(
            addonSubtitle(id = "nn", language = "nn"),
            addonSubtitle(id = "nb", language = "nb"),
        )
        assertEquals("nb", firstMatchingAddonSubtitle(addons, "no")?.id)
        val items = buildSubtitleLanguageItems(
            subtitleTracks = tracks,
            addonSubtitles = addons,
            preferredLanguage = "no",
            secondaryPreferredLanguage = null,
            showOnlyPreferredLanguages = true,
            selectedLanguageKey = SubtitleOffLanguageKey,
        )
        assertEquals(listOf(SubtitleOffLanguageKey, "no", "nn"), items.map { it.key })
    }

    private fun subtitleTrack(
        index: Int,
        language: String,
        label: String = "Track $index",
    ) = SubtitleTrack(
        index = index,
        id = "track-$index",
        label = label,
        language = language,
    )

    private fun addonSubtitle(
        id: String,
        language: String,
    ) = AddonSubtitle(
        id = id,
        url = "https://example.com/$id.srt",
        language = language,
        display = id,
        addonName = "Addon",
    )
}
