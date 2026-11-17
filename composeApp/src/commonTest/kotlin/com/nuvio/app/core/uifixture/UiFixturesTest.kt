package com.nuvio.app.core.uifixture

import com.nuvio.app.features.details.SeasonEpisodeRef
import com.nuvio.app.features.details.SegmentMark
import com.nuvio.app.features.details.episodeSegmentOverview
import com.nuvio.app.features.details.overviewLedgerScope
import com.nuvio.app.features.details.seasonEpisodeContentKey
import com.nuvio.app.features.details.seasonOverviewOpenedByFixture
import com.nuvio.app.features.details.seasonPosterTag
import com.nuvio.app.features.details.seasonSheetEpisodes
import com.nuvio.app.features.details.showImdbIds
import com.nuvio.app.features.details.usesFixtureLedger
import com.nuvio.app.features.player.skip.OwnTheIntroDbLedger
import com.nuvio.app.features.player.skip.PublicMediaStatus
import com.nuvio.app.features.player.skip.movieSegmentRows
import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.DownloadShowRoute
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals(UiFixture.Walk, parseUiFixtureLaunch("movie-segment-rows"))
        assertEquals("movie-segment-rows", parseUiFixtureRequest("movie-segment-rows")?.walkId)
        assertEquals(UiFixture.HomeTall, parseUiFixtureLaunch("home-tall"))
        assertEquals(UiFixture.DetailsTall, parseUiFixtureLaunch("details-tall"))
        assertEquals(UiFixture.DownloadsSeason, parseUiFixtureLaunch("downloads-season"))
        assertEquals(UiFixture.DownloadsPlay, parseUiFixtureLaunch("downloads-play"))
        assertEquals(UiFixture.HomePlay, parseUiFixtureLaunch("home-play"))
        assertNull(UiFixture.HomePlay.launchRoute())
        val playRoute = UiFixture.DownloadsPlay.launchRoute() as DownloadShowRoute
        assertEquals(UiFixtureCatalog.DOWNLOADS_SEASON_SHOW_ID, playRoute.showId)
        assertTrue(UiFixture.DownloadsPlay.playsBundledClip())
        assertTrue(UiFixture.HomePlay.playsBundledClip())
        assertFalse(UiFixture.DownloadsSeason.playsBundledClip())
        assertTrue(UiFixture.DownloadsPlay.usesFixtureLedger())
        val harbor = with(UiFixtureCatalog) {
            WatchProgressEntry(
                contentType = "series",
                parentMetaId = SERIES_ID,
                parentMetaType = "series",
                videoId = HOME_PLAY_VIDEO_ID,
                title = SERIES_TITLE,
                seasonNumber = 1,
                episodeNumber = 3,
                lastPositionMs = 420_000L,
                durationMs = 1_000_000L,
                lastUpdatedEpochMs = 0L,
                progressPercent = 42f,
            ).forHomeClip()
        }
        assertEquals(1_000L, harbor.lastPositionMs)
        assertEquals(8_000L, harbor.durationMs)
        assertEquals(3, harbor.episodeNumber)
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

    @Test
    fun downloadsAndDetailsStayClosedUntilTheSeasonOverviewControlIsTapped() {
        val show = UiFixtureCatalog.downloadsSeasonShow()
        val rows = seasonSheetEpisodes(
            catalog = show.videos,
            ledger = UiFixtureCatalog.downloadsSeasonLedgerMarks(),
            showImdbIds = showImdbIds(show),
            season = 1,
        )
        assertEquals(listOf(1, 2, 3, 9), rows.map { it.episode })
        assertEquals(
            DownloadShowRoute(
                showId = UiFixtureCatalog.DOWNLOADS_SEASON_SHOW_ID,
                title = UiFixtureCatalog.LONG_TITLE,
            ),
            UiFixture.DownloadsSeason.launchRoute(),
        )
        assertNull(seasonOverviewOpenedByFixture(UiFixture.DownloadsSeason, show.id))
        assertNull(seasonOverviewOpenedByFixture(UiFixture.DetailsOnline, UiFixtureCatalog.SERIES_ID))
        assertEquals(1, seasonOverviewOpenedByFixture(UiFixture.SeasonOverview, UiFixtureCatalog.SERIES_ID))
        assertEquals(OwnTheIntroDbLedger.FIXTURE_SCOPE, overviewLedgerScope(UiFixture.DownloadsSeason))
        assertEquals(OwnTheIntroDbLedger.FIXTURE_SCOPE, overviewLedgerScope(UiFixture.DetailsOnline))
        assertTrue(UiFixture.DownloadsSeason.usesFixtureLedger())
        assertTrue(UiFixture.DetailsOnline.usesFixtureLedger())
    }

    @Test
    fun ledgerEpisodeNineStaysOnBothEntrancesAndIsNotPublic() {
        val downloadsShow = UiFixtureCatalog.downloadsSeasonShow()
        val downloadsMarks = UiFixtureCatalog.downloadsSeasonLedgerMarks()
        val downloadsRows = seasonSheetEpisodes(
            catalog = downloadsShow.videos,
            ledger = downloadsMarks,
            showImdbIds = showImdbIds(downloadsShow),
            season = 1,
        )
        val detailsShow = UiFixtureCatalog.detailsOnlineShow()
        val detailsMarks = UiFixtureCatalog.detailsShowLedgerMarks()
        val detailsRows = seasonSheetEpisodes(
            catalog = detailsShow.videos,
            ledger = detailsMarks,
            showImdbIds = showImdbIds(detailsShow),
            season = 1,
        )
        assertEquals(listOf(1, 2, 3), downloadsShow.videos.map { it.episode })
        assertEquals(listOf(1, 2, 3), detailsShow.videos.filter { it.season == 1 }.map { it.episode })
        assertFalse(detailsShow.videos.any { it.season == 1 && it.episode == 9 })
        assertFalse(UiFixtureCatalog.downloadsSeasonHasFile(9))
        assertEquals(listOf(1, 2, 3, 9), downloadsRows.map { it.episode })
        assertEquals(listOf(1, 2, 3, 9), detailsRows.map { it.episode })
        assertEquals("", downloadsRows.last().title)
        assertEquals(UiFixtureCatalog.DOWNLOADS_SEASON_IMDB, downloadsShow.imdbId)
        assertEquals(UiFixtureCatalog.DETAILS_SHOW_IMDB, detailsShow.imdbId)
        assertNotEquals(downloadsShow.imdbId, detailsShow.imdbId)

        val downloadsNine = episodeSegmentOverview(
            episode = SeasonEpisodeRef(
                season = 1,
                episode = 9,
                title = "",
                watched = false,
                contentKey = seasonEpisodeContentKey(
                    metaId = downloadsShow.id,
                    imdbId = downloadsShow.imdbId,
                    videoId = downloadsRows.last().id,
                    videoImdbId = downloadsRows.last().imdbId,
                    season = 1,
                    episode = 9,
                ),
            ),
            own = downloadsMarks,
            detectedTypes = emptySet(),
        )
        assertEquals(setOf("recap"), downloadsNine.pending)
        assertTrue(downloadsNine.submitted.isEmpty())
        assertEquals(SegmentMark.Pending, downloadsNine.marks["recap"])
        assertEquals(SegmentMark.Missing, downloadsNine.marks["intro"])

        val detailsNine = episodeSegmentOverview(
            episode = SeasonEpisodeRef(
                season = 1,
                episode = 9,
                title = "",
                watched = false,
                contentKey = seasonEpisodeContentKey(
                    metaId = detailsShow.id,
                    imdbId = detailsShow.imdbId,
                    videoId = detailsRows.last().id,
                    videoImdbId = detailsRows.last().imdbId,
                    season = 1,
                    episode = 9,
                ),
            ),
            own = detailsMarks,
            detectedTypes = emptySet(),
        )
        assertEquals(setOf("recap"), detailsNine.pending)
        assertTrue(detailsNine.submitted.isEmpty())
        val downloadsOne = episodeSegmentOverview(
            episode = SeasonEpisodeRef(
                season = 1,
                episode = 1,
                title = "Episode 1",
                watched = false,
                contentKey = "${UiFixtureCatalog.DOWNLOADS_SEASON_IMDB}:1:1",
            ),
            own = downloadsMarks,
            detectedTypes = emptySet(),
        )
        val detailsOne = episodeSegmentOverview(
            episode = SeasonEpisodeRef(
                season = 1,
                episode = 1,
                title = "Episode 1",
                watched = false,
                contentKey = "${UiFixtureCatalog.DETAILS_SHOW_IMDB}:1:1",
            ),
            own = detailsMarks,
            detectedTypes = emptySet(),
        )
        assertEquals(setOf("intro"), downloadsOne.submitted)
        assertTrue(detailsOne.submitted.isEmpty())
        assertTrue(detailsOne.pending.isEmpty())

        val downloadsScreen = sourceText("composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/DownloadsScreen.kt")
        val overview = downloadsScreen.substringAfter("val overviewEpisodes")
        assertTrue(overview.contains("overviewLedgerScope()"))
        assertFalse(overview.contains("currentTheIntroDbAccountScope()"))
        val posters = sourceText("composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailSeriesContent.kt")
        assertTrue(posters.contains("seasonPosterTag(season)"))
        assertEquals("season-poster-1", seasonPosterTag(1))
    }

    @Test
    fun movieFixtureOwnMarksAreNotAPublicCount() {
        val marks = UiFixtureCatalog.movieFixtureMarks()
        val key = "${UiFixtureCatalog.MOVIE_FIXTURE_IMDB}:movie"
        val rows = movieSegmentRows(
            media = null,
            publicStatus = PublicMediaStatus.NoSubmissions,
            confirmed = marks.confirmed[key].orEmpty(),
            pending = marks.pending[key].orEmpty(),
            detected = emptySet(),
        )
        val intro = rows.first { it.type == "intro" }
        val recap = rows.first { it.type == "recap" }
        assertTrue(intro.ownConfirmed)
        assertFalse(intro.publicAvailable)
        assertTrue(intro.publicCount == null)
        assertTrue(recap.ownPending)
        assertFalse(recap.ownConfirmed)
        assertEquals(
            DetailRoute(
                type = UiFixtureCatalog.MOVIE_TYPE,
                id = UiFixtureCatalog.MOVIE_ID,
                title = UiFixtureCatalog.MOVIE_TITLE,
            ),
            UiFixture.DetailsMovieOffline.launchRoute(),
        )
        assertEquals(OwnTheIntroDbLedger.FIXTURE_SCOPE, overviewLedgerScope(UiFixture.DetailsMovieOffline))
    }

    @Test
    fun settingsWalkNamesOpenTheRealPages() {
        assertEquals("settings-advanced", settingsWireName(SettingsPage.Advanced))
        assertEquals("settings-debug-logs", settingsWireName(SettingsPage.DebugLogs))
        assertEquals("settings-advanced", parseUiFixtureRequest("settings-advanced")?.walkId)
        assertEquals(
            "settings-debug-logs",
            parseUiFixtureRequest("$UI_FIXTURE_ARGUMENT settings-debug-logs")?.walkId,
        )
    }

    private fun sourceText(relative: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: return ""
        }
        return ""
    }
}
