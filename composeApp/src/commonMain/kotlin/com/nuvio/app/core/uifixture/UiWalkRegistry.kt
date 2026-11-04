package com.nuvio.app.core.uifixture

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.search.DiscoverUiState
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.search.SearchUiState
import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.SettingsPageRoute
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Screenshot states for the UI walk. Append a [WalkScreen] here. Do not add
 * another [UiFixture] value for each screen: [UiFixture.Walk] is the carrier,
 * so a fixture added on [UiFixture] stays a one-line merge.
 */
internal object UiWalkScreens {
    fun idForWireName(name: String): String? =
        screens.firstOrNull { it.wireName == name }?.wireName

    fun current(): WalkScreen? {
        val id = UiFixtureSession.walkId ?: return null
        return screens.firstOrNull { it.wireName == id }
    }

    fun showsOverlay(): Boolean = current()?.overlay == true

    fun route(): AppRoute? {
        val screen = current() ?: return null
        return when (screen.kind) {
            WalkKind.Settings -> {
                val page = screen.settingsPage ?: return null
                SettingsPageRoute(pageName = page.name, title = settingsFixtureTitle(page))
            }
            WalkKind.MovieOnline -> DetailRoute(
                type = UiFixtureCatalog.MOVIE_TYPE,
                id = UiFixtureCatalog.MOVIE_ID,
                title = UiFixtureCatalog.MOVIE_TITLE,
            )
            else -> null
        }
    }

    fun install() {
        val screen = current() ?: return
        UiFixtureCatalog.seedWalkShell(online = screen.online)
        UiWalkSession.pendingTabName = screen.tabName
        UiWalkSession.searchQuery = if (screen.kind == WalkKind.Search) "Harbor" else null
        when (screen.kind) {
            WalkKind.MovieOnline -> MetaDetailsRepository.installFixture(UiFixtureCatalog.fixtureMovie())
            WalkKind.Search -> SearchRepository.installFixture(searchResults(), DiscoverUiState())
            WalkKind.Library -> LibraryRepository.installFixture(libraryState())
            WalkKind.DownloadSeason,
            WalkKind.DownloadSheetDismiss,
            WalkKind.EpisodeMenu,
            WalkKind.EpisodeDownload,
            -> MetaDetailsRepository.installFixture(UiFixtureCatalog.fixtureSeries())
            else -> Unit
        }
        UiFixtureSession.pendingRoute = route()
    }

    private val screens: List<WalkScreen> = buildList {
        add(WalkScreen("details-movie-online", WalkKind.MovieOnline))
        add(WalkScreen("search", WalkKind.Search, tabName = "Search"))
        add(WalkScreen("library", WalkKind.Library, tabName = "Library"))
        add(WalkScreen("download-season-sheet", WalkKind.DownloadSeason))
        add(WalkScreen("episode-download-sheet", WalkKind.EpisodeDownload))
        add(WalkScreen("episode-menu", WalkKind.EpisodeMenu))
        add(WalkScreen("player-episodes", WalkKind.PlayerEpisodes))
        add(WalkScreen("player-sources", WalkKind.PlayerSources))
        add(WalkScreen("player-chapters", WalkKind.PlayerChapters))
        add(WalkScreen("player-quality", WalkKind.PlayerQuality))
        add(WalkScreen("player-tools", WalkKind.PlayerTools))
        add(WalkScreen("player-subtitles", WalkKind.PlayerSubtitles))
        add(WalkScreen("player-submit-bar-movie", WalkKind.PlayerSubmitBarMovie))
        add(WalkScreen("download-sheet-dismiss", WalkKind.DownloadSheetDismiss))
        SettingsPage.entries.forEach { page ->
            add(
                WalkScreen(
                    wireName = settingsWireName(page),
                    kind = WalkKind.Settings,
                    settingsPage = page,
                ),
            )
        }
    }
}

internal enum class WalkKind {
    Settings,
    MovieOnline,
    Search,
    Library,
    DownloadSeason,
    EpisodeDownload,
    EpisodeMenu,
    PlayerEpisodes,
    PlayerSources,
    PlayerChapters,
    PlayerQuality,
    PlayerTools,
    PlayerSubtitles,
    PlayerSubmitBarMovie,
    DownloadSheetDismiss,
}

internal data class WalkScreen(
    val wireName: String,
    val kind: WalkKind,
    val settingsPage: SettingsPage? = null,
    val tabName: String? = null,
    val online: Boolean = true,
) {
    val overlay: Boolean
        get() = when (kind) {
            WalkKind.DownloadSeason,
            WalkKind.EpisodeDownload,
            WalkKind.EpisodeMenu,
            WalkKind.PlayerEpisodes,
            WalkKind.PlayerSources,
            WalkKind.PlayerChapters,
            WalkKind.PlayerQuality,
            WalkKind.PlayerTools,
            WalkKind.PlayerSubtitles,
            WalkKind.PlayerSubmitBarMovie,
            WalkKind.DownloadSheetDismiss,
            -> true
            WalkKind.Settings,
            WalkKind.MovieOnline,
            WalkKind.Search,
            WalkKind.Library,
            -> false
        }
}

internal object UiWalkSession {
    var pendingTabName: String? = null
    var searchQuery: String? = null
    val dismissSignal = MutableStateFlow(false)
}

/** `Appearance` -> `settings-appearance`, `MdbListRatings` -> `settings-mdb-list-ratings`. */
internal fun settingsWireName(page: SettingsPage): String {
    val kebab = buildString {
        page.name.forEachIndexed { index, char ->
            if (index > 0 && char.isUpperCase()) append('-')
            append(char.lowercaseChar())
        }
    }
    return "settings-$kebab"
}

internal fun settingsFixtureTitle(page: SettingsPage): String = buildString {
    page.name.forEachIndexed { index, char ->
        if (index > 0 && char.isUpperCase()) append(' ')
        append(char)
    }
}

private fun searchResults(): SearchUiState {
    val items = listOf(
        preview("fixture-search-harbor", "series", "Harbor Lane", 2),
        preview("fixture-search-kite", "movie", "Paper Kite", 3),
        preview("fixture-search-orchard", "series", "Glass Orchard", 4),
    )
    return SearchUiState(
        isLoading = false,
        sections = listOf(
            HomeCatalogSection(
                key = "fixture-search",
                title = "Results",
                subtitle = "Layout check",
                addonName = "Fixture",
                target = CatalogTarget.Library(contentType = "movie", sectionType = "fixture"),
                items = items,
            ),
        ),
    )
}

private fun libraryState(): LibraryUiState {
    val now = Clock.System.now().toEpochMilliseconds()
    val series = libraryItem(
        id = UiFixtureCatalog.SERIES_ID,
        type = UiFixtureCatalog.SERIES_TYPE,
        name = UiFixtureCatalog.SERIES_TITLE,
        slot = 2,
        savedAt = now,
    )
    val movie = libraryItem(
        id = UiFixtureCatalog.MOVIE_ID,
        type = UiFixtureCatalog.MOVIE_TYPE,
        name = UiFixtureCatalog.MOVIE_TITLE,
        slot = 3,
        savedAt = now - 60_000L,
    )
    return LibraryUiState(
        items = listOf(series, movie),
        sections = listOf(
            LibrarySection(type = "series", displayTitle = "Series", items = listOf(series)),
            LibrarySection(type = "movie", displayTitle = "Movies", items = listOf(movie)),
        ),
        isLoaded = true,
    )
}

private fun preview(id: String, type: String, name: String, slot: Int) = MetaPreview(
    id = id,
    type = type,
    name = name,
    poster = fixtureArtUrl(slot, wide = false, label = name),
    banner = fixtureArtUrl(slot, wide = true, label = name),
    landscapePoster = fixtureArtUrl(slot, wide = true, label = name),
    description = "Synthetic layout title.",
    releaseInfo = "2024",
)

private fun libraryItem(
    id: String,
    type: String,
    name: String,
    slot: Int,
    savedAt: Long,
) = LibraryItem(
    id = id,
    type = type,
    name = name,
    poster = fixtureArtUrl(slot, wide = false, label = name),
    banner = fixtureArtUrl(slot, wide = true, label = name),
    description = "Synthetic layout title.",
    releaseInfo = "2024",
    savedAtEpochMs = savedAt,
)
