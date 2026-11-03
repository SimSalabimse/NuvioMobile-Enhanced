package com.nuvio.app.core.uifixture

import kotlin.test.Test
import kotlin.test.assertEquals
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
    }
}
