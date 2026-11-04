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
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerLibrary
import com.nuvio.app.features.servers.ServerMediaKind
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.settings.MediaServerSelection
import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.SettingsPageRoute
import org.jetbrains.compose.resources.getString
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
                // Replaced with the localized page title before the route is shown.
                SettingsPageRoute(pageName = page.name, title = page.name)
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
        if (screen.settingsPage == SettingsPage.MediaServer) {
            seedMediaServerWalk()
        } else {
            ServerRepository.clearWalkFixture()
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

/**
 * Navigation title for a settings walk route.
 * Callers pass the localized page title. The enum name is not a title.
 */
internal fun settingsWalkNavigationTitle(page: SettingsPage, localizedTitles: Map<SettingsPage, String>): String {
    val title = localizedTitles[page]?.trim().orEmpty()
    require(title.isNotEmpty()) { "Missing settings title for ${page.name}" }
    return title
}

internal suspend fun AppRoute.withLocalizedSettingsWalkTitle(): AppRoute {
    val settings = this as? SettingsPageRoute ?: return this
    val page = runCatching { SettingsPage.valueOf(settings.pageName) }.getOrNull() ?: return this
    val titles = SettingsPage.entries.associateWith { getString(it.titleRes) }
    return settings.copy(title = settingsWalkNavigationTitle(page, titles))
}

private fun seedMediaServerWalk() {
    val connection = ServerConnection(
        id = "walk-media-server",
        providerId = "jellyfin",
        name = "Harbor",
        address = "http://harbor.local:8096",
        remoteServerId = "walk-server",
        remoteUserId = "walk-user",
        userName = "Harbor",
        credentialRef = "walk-key",
        libraries = listOf(
            ServerLibrary(id = "movies", name = "Movies", kind = ServerMediaKind.MOVIE),
            ServerLibrary(id = "shows", name = "Shows", kind = ServerMediaKind.SERIES, selected = false),
        ),
    )
    MediaServerSelection.connectionId = connection.id
    ServerRepository.installWalkFixture(listOf(connection))
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
