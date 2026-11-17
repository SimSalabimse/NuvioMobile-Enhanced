package com.nuvio.app.navigation

import com.nuvio.app.core.deeplink.parseAppDeepLink
import com.nuvio.app.core.deeplink.AppDeepLink
import com.nuvio.app.core.ui.DETAIL_SOFT_EDGE_PEAK
import com.nuvio.app.core.ui.HOME_SOFT_EDGE_PEAK
import com.nuvio.app.features.details.components.detailHeaderTitleAlpha
import com.nuvio.app.features.player.PlayerLaunch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayEntryTest {
    @Test
    fun detailsKeepsAPrivateSession() = assertPrivate(PlayEntry.Details)

    @Test
    fun episodeListKeepsAPrivateSession() = assertPrivate(PlayEntry.EpisodeList)

    @Test
    fun seasonRowKeepsAPrivateSession() = assertPrivate(PlayEntry.SeasonRow)

    @Test
    fun continueWatchingKeepsAPrivateSession() = assertPrivate(PlayEntry.ContinueWatching)

    @Test
    fun sourceSheetKeepsAPrivateSession() = assertPrivate(PlayEntry.SourceSheet)

    @Test
    fun nextUpKeepsAPrivateSession() = assertPrivate(PlayEntry.NextUp)

    @Test
    fun deepLinkKeepsAPrivateSession() {
        val link = parseAppDeepLink("nuvio://meta?type=series&id=tt0000001&private=1")
        assertTrue(link is AppDeepLink.Meta)
        assertTrue((link as AppDeepLink.Meta).privateSession)
        assertPrivate(PlayEntry.DeepLink)
        val plain = parseAppDeepLink("nuvio://meta?type=series&id=tt0000001")
        assertFalse((plain as AppDeepLink.Meta).privateSession)
    }

    @Test
    fun manualNextUpKeepsPrivateAndAutomaticNextUpDoesNotStart() {
        val manual = nextEpisodeCarry(privateSession = true, submitMode = false, automatic = false)
        assertEquals(PlayEntry.NextUp, manual?.entry)
        assertTrue(manual?.privateSession == true)
        assertNull(nextEpisodeCarry(privateSession = true, submitMode = false, automatic = true))
        assertNull(nextEpisodeCarry(privateSession = false, submitMode = true, automatic = false))
    }

    @Test
    fun theDetailsFadeIsTheHomeFadeOnlyMilder() {
        assertTrue(DETAIL_SOFT_EDGE_PEAK < HOME_SOFT_EDGE_PEAK)
        assertEquals(0f, detailHeaderTitleAlpha(0f))
        assertEquals(1f, detailHeaderTitleAlpha(1f))
        assertEquals(0.4f, detailHeaderTitleAlpha(0.4f))
    }

    private fun assertPrivate(entry: PlayEntry) {
        val launch = sample().carrying(carriedPlay(entry, privateSession = true))
        assertEquals(entry, carriedPlay(entry, privateSession = true).entry)
        assertTrue(launch.privateSession)
        assertFalse(launch.submitMode)
        assertFalse(launch.persistsProgress)
        assertEquals(0L, launch.initialPositionMs)
        assertEquals(null, launch.initialProgressFraction)
        val ordinary = sample().carrying(carriedPlay(entry, privateSession = false))
        assertFalse(ordinary.privateSession)
        assertTrue(ordinary.persistsProgress)
        assertEquals(40_000L, ordinary.initialPositionMs)
    }

    private fun sample() = PlayerLaunch(
        profileId = 1,
        title = "Northwind",
        sourceUrl = "file:///episode.mp4",
        streamTitle = "Episode",
        providerName = "Local",
        parentMetaId = "tt0000001",
        parentMetaType = "series",
        seasonNumber = 1,
        episodeNumber = 3,
        initialPositionMs = 40_000L,
        initialProgressFraction = 0.2f,
    )
}
