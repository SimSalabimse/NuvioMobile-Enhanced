package com.nuvio.app.core.uifixture

import com.nuvio.app.core.automation.AUTOMATION_CLIP_BYTES
import com.nuvio.app.core.automation.writeAutomationBytes
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.analysis.SkipSourceLedger
import com.nuvio.app.features.downloads.SmartDownloadSettings
import com.nuvio.app.features.downloads.SmartDownloads
import com.nuvio.app.features.player.skip.OwnAccountMarks
import com.nuvio.app.features.player.skip.OwnTheIntroDbLedger
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.HomeUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlin.time.Clock

internal object UiFixtureCatalog {
    const val SERIES_TYPE = "series"
    const val SERIES_ID = "fixture-series-harbor"
    const val SERIES_TITLE = "Harbor Lane"
    const val MOVIE_TYPE = "movie"
    const val MOVIE_ID = "fixture-movie-paper"
    const val MOVIE_TITLE = "Paper Kite"
    const val SEASON_OVERVIEW_IMDB = "tt0000101"
    const val SEASON_OVERVIEW_LONG_EPISODE_COUNT = 25
    const val DOWNLOADS_SEASON_SHOW_ID = "fixture-series-railway"
    const val DOWNLOADS_SEASON_IMDB = "tt9000001"
    const val HOME_PLAY_VIDEO_ID = "fixture-cw-harbor"
    const val LAYOUT_CLIP_FILE = "nuvio-layout-clip.mp4"
    /** Synthetic show id for the details season sheet. Not a public catalogue id. */
    const val DETAILS_SHOW_IMDB = "tt9000003"
    const val MOVIE_FIXTURE_IMDB = "tt9000002"
    const val LONG_TITLE =
        "The Extraordinary and Completely Unabridged Account of a Very Long Evening on the Northern Coastal Railway"

    fun install(fixture: UiFixture) {
        if (fixture == UiFixture.Walk) {
            UiWalkScreens.install()
            return
        }
        val now = Clock.System.now().toEpochMilliseconds()
        val longTitles = fixture == UiFixture.LongTitles
        val tallHero = fixture == UiFixture.HomeTall
        NetworkStatusRepository.adoptFixture(online = fixture.startsOnline)
        HomeCatalogSettingsRepository.installFixture(settingsItems(longTitles))
        HomeRepository.installFixture(homeState(longTitles, tallHero = tallHero))
        val watchedForOverview = if (fixture == UiFixture.SeasonOverviewLong) {
            listOf(
                progress(
                    now = now,
                    id = SERIES_ID,
                    type = SERIES_TYPE,
                    title = SERIES_TITLE,
                    videoId = "$SERIES_ID:2:1",
                    season = 2,
                    episode = 1,
                    episodeTitle = "Episode 1",
                    percent = 100f,
                    slot = 80,
                ).copy(isCompleted = true),
                progress(
                    now = now,
                    id = SERIES_ID,
                    type = SERIES_TYPE,
                    title = SERIES_TITLE,
                    videoId = "$SERIES_ID:2:25",
                    season = 2,
                    episode = 25,
                    episodeTitle = "Episode 25",
                    percent = 100f,
                    slot = 81,
                ).copy(isCompleted = true),
            )
        } else {
            emptyList()
        }
        val watching = continueWatching(now, longTitles) + watchedForOverview
        WatchProgressRepository.installFixtureEntries(
            if (fixture == UiFixture.HomePlay) watching.map { it.forHomeClip() } else {
                watching
            },
        )
        DownloadsRepository.installFixtureItems(
            when (fixture) {
                UiFixture.HomeOffline -> offlineCoverDownloads(now)
                UiFixture.HomePlay -> listOf(playableHomeDownload(layoutClipFileUrl()))
                UiFixture.Downloads -> downloadsList(now)
                UiFixture.DownloadsPlay -> downloadsSeason(now).map { item ->
                    if (item.episodeNumber == 1 && item.status == DownloadStatus.Completed) {
                        item.withLayoutClip(
                            videoId = "$DOWNLOADS_SEASON_IMDB:1:1",
                            fileUrl = layoutClipFileUrl(),
                        )
                    } else {
                        item
                    }
                }
                UiFixture.DownloadsSeason -> downloadsSeason(now)
                else -> emptyList()
            },
        )
        when (fixture) {
            UiFixture.DetailsOffline,
            UiFixture.SeasonPoster,
            -> MetaDetailsRepository.installFixture(series(seasonPosters = true))
            UiFixture.DetailsOnline -> {
                MetaDetailsRepository.installFixture(detailsOnlineShow())
                val marks = detailsShowLedgerMarks()
                OwnTheIntroDbLedger.saveFixture(
                    confirmed = marks.confirmed,
                    pending = marks.pending,
                )
            }
            UiFixture.DetailsTall -> MetaDetailsRepository.installFixture(
                series(seasonPosters = true, tallHero = true),
            )
            UiFixture.SeasonOverview -> {
                val show = series(seasonPosters = true).copy(imdbId = SEASON_OVERVIEW_IMDB)
                MetaDetailsRepository.installFixture(show)
                installSeasonOverviewSegments()
            }
            UiFixture.SeasonOverviewLong -> {
                val show = series(
                    seasonPosters = true,
                    episodesInSeason = mapOf(2 to SEASON_OVERVIEW_LONG_EPISODE_COUNT),
                ).copy(imdbId = SEASON_OVERVIEW_IMDB)
                MetaDetailsRepository.installFixture(show)
                installSeasonOverviewLongSegments()
            }
            UiFixture.SmartDownloads -> {
                val meta = series(seasonPosters = false)
                MetaDetailsRepository.installFixture(meta)
                SmartDownloadSettings.installFixture(
                    enabled = true,
                    count = 2,
                    goalKey = "$SERIES_TYPE|$SERIES_ID",
                )
                SmartDownloads.installFixtureGoalStatus(SERIES_ID, "Keeping 2 ready")
            }
            UiFixture.DetailsMovieOffline -> {
                MetaDetailsRepository.installFixture(movie().copy(imdbId = MOVIE_FIXTURE_IMDB))
                val marks = movieFixtureMarks()
                OwnTheIntroDbLedger.saveFixture(
                    confirmed = marks.confirmed,
                    pending = marks.pending,
                )
            }
            UiFixture.DownloadsSeason, UiFixture.DownloadsPlay -> {
                MetaDetailsRepository.installFixture(downloadsSeasonShow())
                val marks = downloadsSeasonLedgerMarks()
                OwnTheIntroDbLedger.saveFixture(
                    confirmed = marks.confirmed,
                    pending = marks.pending,
                )
            }
            else -> Unit
        }
        UiFixtureSession.pendingRoute = fixture.launchRoute()
        startConnectivityFixturePoller()
    }

    private fun homeState(longTitles: Boolean, tallHero: Boolean = false): HomeUiState {
        val heroes = listOf(
            preview(1, if (longTitles) LONG_TITLE else "Northwind", wide = !tallHero),
            preview(2, if (longTitles) "Tide" else "Harbor Lane", wide = true),
            preview(3, "Paper Kite", wide = true),
            preview(4, "Glass Orchard", wide = true),
        )
        val trending = (1..6).map { slot ->
            preview(
                slot = 10 + slot,
                title = if (longTitles && slot % 2 == 0) LONG_TITLE else shortNames[slot - 1],
                wide = false,
            )
        }
        val fresh = (1..6).map { slot ->
            preview(
                slot = 20 + slot,
                title = if (longTitles && slot % 2 == 1) "Low" else "Evening $slot",
                wide = false,
            )
        }
        return HomeUiState(
            isLoading = false,
            heroItems = heroes,
            sections = listOf(
                section("fixture-trending", "Trending", trending),
                section("fixture-fresh", "New releases", fresh),
            ),
        )
    }

    private fun settingsItems(longTitles: Boolean): List<HomeCatalogSettingsItem> = listOf(
        settingsItem("fixture-trending", if (longTitles) LONG_TITLE else "Trending", order = 0),
        settingsItem("fixture-fresh", "New releases", order = 1),
    )

    private fun continueWatching(now: Long, longTitles: Boolean): List<WatchProgressEntry> = listOf(
        progress(
            now = now,
            id = SERIES_ID,
            type = SERIES_TYPE,
            title = if (longTitles) LONG_TITLE else SERIES_TITLE,
            videoId = "fixture-cw-harbor",
            season = 1,
            episode = 3,
            episodeTitle = "The Night Ferry",
            percent = 42f,
            slot = 30,
        ),
        progress(
            now = now - 60_000L,
            id = "fixture-series-orchard",
            type = SERIES_TYPE,
            title = "Glass Orchard",
            videoId = "fixture-cw-orchard",
            season = 2,
            episode = 1,
            episodeTitle = "First Light",
            percent = 15f,
            slot = 31,
        ),
    )

    private fun series(
        seasonPosters: Boolean,
        episodesInSeason: Map<Int, Int> = emptyMap(),
        tallHero: Boolean = false,
    ): MetaDetails {
        val videos = (1..3).flatMap { season ->
            val count = episodesInSeason[season] ?: 3
            (1..count).map { episode ->
                val title = if (count > 3 && episode == 12) LONG_TITLE else "Episode $episode"
                MetaVideo(
                    id = "fixture-s$season-e$episode",
                    title = title,
                    season = season,
                    episode = episode,
                    available = true,
                    overview = "A synthetic episode used for layout checks.",
                    runtime = 42,
                    thumbnail = fixtureArtUrl(40 + season * 10 + episode, wide = true, label = "S$season E$episode"),
                    seasonPoster = if (seasonPosters) fixtureSeasonPosterUrl(season) else null,
                )
            }
        }
        return MetaDetails(
            id = SERIES_ID,
            type = SERIES_TYPE,
            name = SERIES_TITLE,
            poster = fixtureArtUrl(2, wide = false, label = SERIES_TITLE),
            background = fixtureArtUrl(2, wide = !tallHero, label = SERIES_TITLE),
            description = "A synthetic series with three seasons, used to check the details layout.",
            releaseInfo = "2024",
            genres = listOf("Drama"),
            videos = videos,
            seasonPosters = if (seasonPosters) {
                (1..3).associateWith(::fixtureSeasonPosterUrl)
            } else {
                emptyMap()
            },
        )
    }

    private fun movie(): MetaDetails = MetaDetails(
        id = MOVIE_ID,
        type = MOVIE_TYPE,
        name = MOVIE_TITLE,
        poster = fixtureArtUrl(3, wide = false, label = MOVIE_TITLE),
        background = fixtureArtUrl(3, wide = true, label = MOVIE_TITLE),
        description = "A synthetic movie used to check the offline details layout.",
        releaseInfo = "2023",
        runtime = "104 min",
        genres = listOf("Adventure"),
    )

    private fun offlineCoverDownloads(now: Long): List<DownloadItem> = listOf(
        download(
            now = now,
            id = "fixture-dl-harbor",
            title = SERIES_TITLE,
            type = SERIES_TYPE,
            parentId = SERIES_ID,
            season = 1,
            episode = 2,
            episodeTitle = "Low Tide",
            status = DownloadStatus.Completed,
            downloaded = 800_000_000L,
            total = 800_000_000L,
            slot = 60,
        ),
        download(
            now = now,
            id = "fixture-dl-paper",
            title = MOVIE_TITLE,
            type = MOVIE_TYPE,
            parentId = MOVIE_ID,
            season = null,
            episode = null,
            episodeTitle = null,
            status = DownloadStatus.Completed,
            downloaded = 1_200_000_000L,
            total = 1_200_000_000L,
            slot = 61,
        ),
    )

    internal fun downloadsSeasonShow(): MetaDetails {
        val videos = (1..3).map { episode ->
            MetaVideo(
                id = "$DOWNLOADS_SEASON_IMDB:1:$episode",
                title = "Episode $episode",
                season = 1,
                episode = episode,
                available = true,
                imdbId = DOWNLOADS_SEASON_IMDB,
            )
        }
        return MetaDetails(
            id = DOWNLOADS_SEASON_SHOW_ID,
            type = SERIES_TYPE,
            name = LONG_TITLE,
            imdbId = DOWNLOADS_SEASON_IMDB,
            videos = videos,
        )
    }

    /** Episode 9 is ledger-only. It is not one of the downloaded files. */
    internal fun downloadsSeasonLedgerMarks(): OwnAccountMarks = OwnAccountMarks(
        confirmed = mapOf("$DOWNLOADS_SEASON_IMDB:1:1" to setOf("intro")),
        pending = mapOf("$DOWNLOADS_SEASON_IMDB:1:9" to setOf("recap")),
    )

    internal fun downloadsSeasonHasFile(episode: Int): Boolean =
        downloadsSeason(0L).any { it.episodeNumber == episode }

    /** Writes the bundled 8-second clip once and returns its file URL. */
    internal fun layoutClipFileUrl(): String = writeAutomationBytes(LAYOUT_CLIP_FILE, AUTOMATION_CLIP_BYTES)

    /**
     * Episode 1 of the downloads show, with the clip as its local file.
     * The video id matches the season-sheet row, so Watch privately finds this item.
     */
    internal fun playableDownloadsEpisode(fileUrl: String): DownloadItem =
        downloadsSeason(0L).first { it.episodeNumber == 1 && it.status == DownloadStatus.Completed }
            .withLayoutClip(videoId = "$DOWNLOADS_SEASON_IMDB:1:1", fileUrl = fileUrl)

    /** The Home continue-watching episode, with the same clip. */
    internal fun playableHomeDownload(fileUrl: String): DownloadItem = download(
        now = 0L,
        id = "fixture-dl-harbor",
        title = SERIES_TITLE,
        type = SERIES_TYPE,
        parentId = SERIES_ID,
        season = 1,
        episode = 3,
        episodeTitle = "The Night Ferry",
        status = DownloadStatus.Completed,
        downloaded = AUTOMATION_CLIP_BYTES.size.toLong(),
        total = AUTOMATION_CLIP_BYTES.size.toLong(),
        slot = 30,
    ).withLayoutClip(videoId = HOME_PLAY_VIDEO_ID, fileUrl = fileUrl)

    /** One second into the 8-second clip, so the player can still advance. */
    internal fun WatchProgressEntry.forHomeClip(): WatchProgressEntry =
        if (videoId == HOME_PLAY_VIDEO_ID) {
            copy(lastPositionMs = 1_000L, durationMs = 8_000L, progressPercent = 12.5f)
        } else {
            this
        }

    private fun DownloadItem.withLayoutClip(videoId: String, fileUrl: String): DownloadItem = copy(
        videoId = videoId,
        localFileUri = fileUrl,
        fileName = LAYOUT_CLIP_FILE,
        sourceUrl = fileUrl,
        downloadedBytes = AUTOMATION_CLIP_BYTES.size.toLong(),
        totalBytes = AUTOMATION_CLIP_BYTES.size.toLong(),
        status = DownloadStatus.Completed,
    )

    /** Harbor Lane with a synthetic imdb so a ledger key can name an episode. */
    internal fun detailsOnlineShow(): MetaDetails =
        series(seasonPosters = true).copy(imdbId = DETAILS_SHOW_IMDB)

    /** Episode 9 is pending on this account. Episodes 1–3 have no own mark. */
    internal fun detailsShowLedgerMarks(): OwnAccountMarks = OwnAccountMarks(
        pending = mapOf("$DETAILS_SHOW_IMDB:1:9" to setOf("recap")),
    )

    internal fun movieFixtureMarks(): OwnAccountMarks = OwnAccountMarks(
        confirmed = mapOf("$MOVIE_FIXTURE_IMDB:movie" to setOf("intro")),
        pending = mapOf("$MOVIE_FIXTURE_IMDB:movie" to setOf("recap")),
    )

    private fun downloadsSeason(now: Long): List<DownloadItem> = listOf(
        download(
            now = now,
            id = "fixture-dl-railway-1",
            title = LONG_TITLE,
            type = SERIES_TYPE,
            parentId = DOWNLOADS_SEASON_SHOW_ID,
            season = 1,
            episode = 1,
            episodeTitle = "The Platform at the End of the Line",
            status = DownloadStatus.Completed,
            downloaded = 800_000_000L,
            total = 800_000_000L,
            slot = 80,
        ),
        download(
            now = now,
            id = "fixture-dl-railway-2",
            title = LONG_TITLE,
            type = SERIES_TYPE,
            parentId = DOWNLOADS_SEASON_SHOW_ID,
            season = 1,
            episode = 2,
            episodeTitle = "A Short Stop",
            status = DownloadStatus.Downloading,
            downloaded = 120_000_000L,
            total = 900_000_000L,
            slot = 81,
        ),
        download(
            now = now,
            id = "fixture-dl-railway-3",
            title = LONG_TITLE,
            type = SERIES_TYPE,
            parentId = DOWNLOADS_SEASON_SHOW_ID,
            season = 1,
            episode = 3,
            episodeTitle = "Lanterns Along the Cutting",
            status = DownloadStatus.Paused,
            downloaded = 40_000_000L,
            total = 900_000_000L,
            slot = 82,
        ),
    )

    private fun downloadsList(now: Long): List<DownloadItem> = listOf(
        download(
            now = now,
            id = "fixture-dl-short",
            title = "Tide",
            type = MOVIE_TYPE,
            parentId = "fixture-movie-tide",
            season = null,
            episode = null,
            episodeTitle = null,
            status = DownloadStatus.Completed,
            downloaded = 500_000_000L,
            total = 500_000_000L,
            slot = 70,
        ),
        download(
            now = now,
            id = "fixture-dl-long",
            title = LONG_TITLE,
            type = SERIES_TYPE,
            parentId = "fixture-series-railway",
            season = 1,
            episode = 4,
            episodeTitle = "The Platform at the End of the Line",
            status = DownloadStatus.Paused,
            downloaded = 240_000_000L,
            total = 900_000_000L,
            slot = 71,
        ),
        download(
            now = now,
            id = "fixture-dl-harbor-2",
            title = SERIES_TITLE,
            type = SERIES_TYPE,
            parentId = SERIES_ID,
            season = 1,
            episode = 5,
            episodeTitle = "Lanterns",
            status = DownloadStatus.Completed,
            downloaded = 700_000_000L,
            total = 700_000_000L,
            slot = 72,
        ),
    )

    private fun preview(slot: Int, title: String, wide: Boolean): MetaPreview = MetaPreview(
        id = "fixture-preview-$slot",
        type = if (slot % 2 == 0) SERIES_TYPE else MOVIE_TYPE,
        name = title,
        poster = fixtureArtUrl(slot, wide = false, label = title),
        banner = fixtureArtUrl(slot, wide = true, label = title),
        landscapePoster = fixtureArtUrl(slot, wide = true, label = title),
        description = "Synthetic layout title.",
        releaseInfo = "2024",
    )

    private fun section(key: String, title: String, items: List<MetaPreview>) = HomeCatalogSection(
        key = key,
        title = title,
        subtitle = "Layout check",
        addonName = "Fixture",
        target = CatalogTarget.Library(contentType = "movie", sectionType = "fixture"),
        items = items,
    )

    private fun settingsItem(key: String, title: String, order: Int) = HomeCatalogSettingsItem(
        key = key,
        defaultTitle = title,
        addonName = "Fixture",
        enabled = true,
        heroSourceEnabled = false,
        order = order,
    )

    private fun progress(
        now: Long,
        id: String,
        type: String,
        title: String,
        videoId: String,
        season: Int,
        episode: Int,
        episodeTitle: String,
        percent: Float,
        slot: Int,
    ) = WatchProgressEntry(
        contentType = type,
        parentMetaId = id,
        parentMetaType = type,
        videoId = videoId,
        title = title,
        poster = fixtureArtUrl(slot, wide = false, label = title),
        background = fixtureArtUrl(slot, wide = true, label = title),
        episodeThumbnail = fixtureArtUrl(slot, wide = true, label = episodeTitle),
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        lastPositionMs = (percent * 10_000f).toLong(),
        durationMs = 1_000_000L,
        lastUpdatedEpochMs = now,
        progressPercent = percent,
        isCompleted = false,
    )

    private fun download(
        now: Long,
        id: String,
        title: String,
        type: String,
        parentId: String,
        season: Int?,
        episode: Int?,
        episodeTitle: String?,
        status: DownloadStatus,
        downloaded: Long,
        total: Long,
        slot: Int,
    ) = DownloadItem(
        id = id,
        contentType = type,
        parentMetaId = parentId,
        parentMetaType = type,
        videoId = id,
        title = title,
        poster = fixtureArtUrl(slot, wide = false, label = title),
        background = fixtureArtUrl(slot, wide = true, label = title),
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        streamTitle = "Layout check",
        providerName = "Fixture",
        sourceUrl = "fixture://media/$id",
        localFileUri = "fixture://file/$id",
        fileName = "$id.mp4",
        status = status,
        downloadedBytes = downloaded,
        totalBytes = total,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
    )

    private val shortNames = listOf("Northwind", "Harbor", "Kite", "Orchard", "Lantern", "Ferry")

    private fun installSeasonOverviewSegments() {
        val imdb = SEASON_OVERVIEW_IMDB
        OwnTheIntroDbLedger.saveFixture(
            confirmed = mapOf(
                "$imdb:1:1" to setOf("intro"),
                "$imdb:1:3" to setOf("intro"),
            ),
            pending = mapOf("$imdb:1:2" to setOf("recap")),
        )
        SkipSourceLedger.record(
            SERIES_ID,
            season = 1,
            episode = 2,
            intervals = listOf(SkipInterval(12.0, 70.0, "intro", TheIntroDb.PROVIDER)),
        )
        SkipSourceLedger.record(
            SERIES_ID,
            season = 1,
            episode = 3,
            intervals = listOf(SkipInterval(20.0, 90.0, "recap", TheIntroDb.PROVIDER)),
        )
    }

    private fun installSeasonOverviewLongSegments() {
        val imdb = SEASON_OVERVIEW_IMDB
        OwnTheIntroDbLedger.saveAll(
            mapOf(
                "$imdb:2:1" to setOf("recap"),
                "$imdb:2:2" to setOf("intro", "recap", "outro", "preview"),
                "$imdb:2:7" to setOf("intro"),
            ),
        )
        SkipSourceLedger.record(
            SERIES_ID,
            season = 2,
            episode = 1,
            intervals = listOf(SkipInterval(4.0, 50.0, "intro", TheIntroDb.PROVIDER)),
        )
        SkipSourceLedger.record(
            SERIES_ID,
            season = 2,
            episode = 4,
            intervals = listOf(SkipInterval(0.0, 36.0, "intro", TheIntroDb.PROVIDER)),
        )
        SkipSourceLedger.record(
            SERIES_ID,
            season = 2,
            episode = 5,
            intervals = listOf(SkipInterval(0.0, 28.0, "recap", TheIntroDb.PROVIDER)),
        )
    }
    /** Home shell the UI walk reuses. Does not set [UiFixtureSession.pendingRoute]. */
    internal fun seedWalkShell(online: Boolean) {
        val now = Clock.System.now().toEpochMilliseconds()
        NetworkStatusRepository.adoptFixture(online = online)
        HomeCatalogSettingsRepository.installFixture(settingsItems(longTitles = false))
        HomeRepository.installFixture(homeState(longTitles = false))
        WatchProgressRepository.installFixtureEntries(continueWatching(now, longTitles = false))
        startConnectivityFixturePoller()
    }

    internal fun fixtureSeries(): MetaDetails = series(seasonPosters = true)

    internal fun fixtureMovie(): MetaDetails = movie()
}

internal object UiFixtureInstaller {
    fun install() {
        val fixture = currentUiFixture() ?: return
        UiFixtureCatalog.install(fixture)
    }
}
