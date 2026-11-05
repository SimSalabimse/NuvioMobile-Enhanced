package com.nuvio.app.core.uifixture

import com.nuvio.app.features.settings.SettingsPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiFixturesTest {
    @Test
    fun gateStaysDisabledAndDoesNotReadWhenTheBinaryIsNotDebug() {
        var reads = 0
        val decision = decideUiFixture(isDebugBinary = false) {
            reads += 1
            "home-online"
        }
        assertNull(decision.fixture)
        assertEquals("disabled", decision.status)
        assertEquals(0, reads)
    }

    @Test
    fun gateParsesAKnownNameOnlyForADebugBinary() {
        val decision = decideUiFixture(isDebugBinary = true) { "home-offline" }
        assertEquals(UiFixture.HomeOffline, decision.fixture)
        assertEquals("home-offline", decision.status)
    }

    @Test
    fun parsesBareNamesFlagsAndTheEnvironmentForm() {
        UiFixture.entries.forEach { fixture ->
            assertEquals(fixture, parseUiFixtureLaunch(fixture.wireName))
            assertEquals(fixture, parseUiFixtureLaunch("$UI_FIXTURE_ARGUMENT ${fixture.wireName}"))
            assertEquals(fixture, parseUiFixtureLaunch("$UI_FIXTURE_ARGUMENT=${fixture.wireName}"))
        }
        assertEquals(
            UiFixture.Downloads,
            parseUiFixtureArguments(
                arguments = listOf("Nuvio", UI_FIXTURE_ARGUMENT, "downloads"),
                environmentValue = null,
            ),
        )
        assertEquals(
            UiFixture.LongTitles,
            parseUiFixtureArguments(
                arguments = listOf("Nuvio", "$UI_FIXTURE_ARGUMENT=downloads"),
                environmentValue = "$UI_FIXTURE_ENVIRONMENT=long-titles",
            ),
        )
        assertNull(parseUiFixtureLaunch(null))
        assertNull(parseUiFixtureLaunch(""))
        assertNull(parseUiFixtureLaunch("not-a-state"))
        assertNull(parseUiFixtureLaunch("$UI_FIXTURE_ARGUMENT not-a-state"))
        assertNull(
            parseUiFixtureArguments(
                arguments = listOf("Nuvio"),
                environmentValue = " ",
            ),
        )
    }

    @Test
    fun submitBarFixtureAcceptsAWidthSuffixAndTheLongVariant() {
        assertEquals(UiFixture.PlayerSubmitBar, parseUiFixtureLaunch("player-submit-bar"))
        assertEquals(UiFixture.PlayerSubmitBar, parseUiFixtureLaunch("player-submit-bar@667"))
        assertEquals(UiFixture.PlayerSubmitBar, parseUiFixtureLaunch("$UI_FIXTURE_ARGUMENT player-submit-bar@844"))
        assertEquals(844, parseUiFixtureRequest("player-submit-bar@844")?.widthDp)
        assertEquals(667, parseUiFixtureRequest("$UI_FIXTURE_ARGUMENT=player-submit-bar@667")?.widthDp)
        assertNull(parseUiFixtureRequest("player-submit-bar")?.widthDp)
        assertEquals(UiFixture.PlayerSubmitBarLong, parseUiFixtureLaunch("player-submit-bar-long"))
        assertEquals(932, parseUiFixtureRequest("player-submit-bar-long@932")?.widthDp)
        assertEquals(844, UiFixture.PlayerSubmitBar.resolvedSubmitBarWidthDp(null))
        assertEquals(667, UiFixture.PlayerSubmitBar.resolvedSubmitBarWidthDp(667))
        assertEquals(667, UiFixture.PlayerSubmitBarLong.resolvedSubmitBarWidthDp(null))
        assertNull(parseUiFixtureLaunch("player-submit-bar@wide"))
        assertNull(parseUiFixtureLaunch("home-online@844"))
        assertNull(parseUiFixtureLaunch("player-submit-bar@0"))
        val decision = decideUiFixture(isDebugBinary = true) { "player-submit-bar@932" }
        assertEquals(UiFixture.PlayerSubmitBar, decision.fixture)
        assertEquals(932, decision.widthDp)
        assertEquals("player-submit-bar", decision.status)
    }

    @Test
    fun walkScreensParseForDebugAndTheReleaseGateStillDoesNotRead() {
        assertEquals("settings-appearance", settingsWireName(SettingsPage.Appearance))
        assertEquals("settings-tmdb-enrichment", settingsWireName(SettingsPage.TmdbEnrichment))
        assertEquals("settings-mdb-list-ratings", settingsWireName(SettingsPage.MdbListRatings))
        assertEquals("settings-whats-new", settingsWireName(SettingsPage.WhatsNew))
        assertEquals("settings-debug-logs", settingsWireName(SettingsPage.DebugLogs))
        val appearance = settingsWireName(SettingsPage.Appearance)
        val request = parseUiFixtureRequest(appearance)
        assertEquals(UiFixture.Walk, request?.fixture)
        assertEquals(appearance, request?.walkId)
        assertEquals(UiFixture.Walk, parseUiFixtureLaunch("download-season-sheet"))
        assertEquals("download-season-sheet", parseUiFixtureRequest("download-season-sheet")?.walkId)
        assertEquals(UiFixture.Walk, parseUiFixtureLaunch("player-episodes"))
        assertEquals(UiFixture.Walk, parseUiFixtureLaunch("player-submit-bar-movie"))
        assertEquals("player-submit-bar-movie", parseUiFixtureRequest("player-submit-bar-movie")?.walkId)
        assertNull(parseUiFixtureRequest("settings-appearance@844"))
        assertNull(parseUiFixtureLaunch("not-a-walk-screen"))
        val names = SettingsPage.entries.map { settingsWireName(it) }
        assertEquals(names.size, names.toSet().size)
        val titles = mapOf(
            SettingsPage.Root to "Settings",
            SettingsPage.MdbListRatings to "MDBList Ratings",
            SettingsPage.TmdbEnrichment to "TMDB Enrichment",
            SettingsPage.Omdb to "OMDb",
            SettingsPage.DiscordRpc to "Discord Rich Presence",
            SettingsPage.LiveTv to "Live TV",
            SettingsPage.WhatsNew to "What's New",
            SettingsPage.LicensesAttributions to "Licenses & Attributions",
            SettingsPage.SupportersContributors to "Supporters & Contributors",
            SettingsPage.MediaServer to "Server",
        )
        titles.forEach { (page, title) ->
            assertEquals(title, settingsWalkNavigationTitle(page, titles))
        }
        assertNotEquals("Root", settingsWalkNavigationTitle(SettingsPage.Root, titles))
        assertNotEquals("Mdb List Ratings", settingsWalkNavigationTitle(SettingsPage.MdbListRatings, titles))
        var reads = 0
        val blocked = decideUiFixture(isDebugBinary = false) {
            reads += 1
            appearance
        }
        assertNull(blocked.fixture)
        assertNull(blocked.walkId)
        assertEquals(0, reads)
    }

    @Test
    fun dismissActionWordMatchesTheScript() {
        assertEquals("dismiss", parseFixtureAction("dismiss"))
        assertEquals("dismiss", parseFixtureAction(" DISMISS\n"))
        assertNull(parseFixtureAction("online"))
        assertNull(parseFixtureAction(""))
        assertNull(parseFixtureAction(null))
    }

    @Test
    fun connectivityWordsMatchTheScript() {
        assertEquals(true, parseConnectivityWord("online"))
        assertEquals(true, parseConnectivityWord(" ONLINE\n"))
        assertEquals(false, parseConnectivityWord("offline"))
        assertEquals(false, parseConnectivityWord("0"))
        assertNull(parseConnectivityWord(""))
        assertNull(parseConnectivityWord("maybe"))
    }

    @Test
    fun posterSvgIsALocalGradientWithoutUrls() {
        val svg = fixturePosterSvg(slot = 2, wide = true, label = "Harbor & Lane")
        assertTrue(svg.contains("linearGradient"))
        assertTrue(svg.contains("Harbor &amp; Lane"))
        val withoutXmlNamespace = svg.replace("http://www.w3.org/2000/svg", "")
        assertTrue(!withoutXmlNamespace.contains("http://") && !withoutXmlNamespace.contains("https://"))
        assertEquals(
            "fixture://art/wide/2/Harbor_Lane",
            fixtureArtUrl(slot = 2, wide = true, label = "Harbor Lane"),
        )
        val season = fixturePosterSvg(slot = 1, wide = false, label = "Season 1", large = true)
        assertTrue(season.contains("width=\"1600\""))
        assertTrue(season.contains("height=\"2400\""))
        val seasonWithoutNamespace = season.replace("http://www.w3.org/2000/svg", "")
        assertTrue(!seasonWithoutNamespace.contains("http://") && !seasonWithoutNamespace.contains("https://"))
        assertEquals("fixture://art/season/1/Season_1", fixtureSeasonPosterUrl(1))
    }
}
