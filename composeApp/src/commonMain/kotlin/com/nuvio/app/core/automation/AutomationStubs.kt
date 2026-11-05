package com.nuvio.app.core.automation

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileCachePhase
import com.nuvio.app.features.profiles.ProfileState
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamItem

/** Shown in place of any saved profile name while automation mode is on. */
const val AUTOMATION_PROFILE_NAME = "Test"

const val AUTOMATION_SERIES_ID = "automation:series"
const val AUTOMATION_MOVIE_ID = "automation:movie"
const val AUTOMATION_SERIES_NAME = "Automation Series"
const val AUTOMATION_MOVIE_NAME = "Automation Movie"

internal const val AUTOMATION_ADDON_ID = "addon:nuvio-automation"
internal const val AUTOMATION_ADDON_NAME = "Automation"
internal const val AUTOMATION_CLIP_MARKER = "nuvio-automation-"

private const val SOURCE_1080_BYTES = 1_200_000_000L
private const val SOURCE_2160_BYTES = 3_400_000_000L
private const val AIRED_ON = "2020-01-15"

/**
 * Built-in catalog, sources, stream resolution, and local clip used only while
 * [AutomationTestMode] is on. Callers keep their normal clients when the flag is off.
 */
internal object AutomationStubs {
    var realClientCalls: Int = 0
        private set
    var stubClientCalls: Int = 0
        private set
    var realResolveCalls: Int = 0
        private set
    var stubResolveCalls: Int = 0
        private set
    var blockedNetworkCalls: Int = 0
        private set

    fun resetCounts() {
        realClientCalls = 0
        stubClientCalls = 0
        realResolveCalls = 0
        stubResolveCalls = 0
        blockedNetworkCalls = 0
    }

    /**
     * One branch for a lookup. [real] is the production client. [stub] never calls it.
     */
    suspend fun <T> selectClient(real: suspend () -> T, stub: () -> T): T {
        if (!AutomationTestMode.isActive) {
            realClientCalls += 1
            return real()
        }
        stubClientCalls += 1
        return stub()
    }

    fun noteRealResolve() {
        realResolveCalls += 1
    }

    fun noteStubResolve() {
        stubResolveCalls += 1
    }

    fun noteBlockedNetwork() {
        blockedNetworkCalls += 1
    }

    fun isStubSource(stream: StreamItem): Boolean = stream.addonId == AUTOMATION_ADDON_ID

    fun isLocalClip(url: String): Boolean {
        if (!AutomationTestMode.isActive) return false
        return url.lowercase().contains(AUTOMATION_CLIP_MARKER)
    }

    fun sources(): List<StreamItem> = listOf(
        source(
            name = "Automation 1080p",
            bytes = SOURCE_1080_BYTES,
            filename = "Automation.1080p.mkv",
        ),
        source(
            name = "Automation 2160p",
            bytes = SOURCE_2160_BYTES,
            filename = "Automation.2160p.mkv",
        ),
    )

    /** The same source, now pointing at the local test clip. */
    fun playable(stream: StreamItem): StreamItem = stream.copy(
        url = clipFileUrl(),
        infoHash = null,
        externalUrl = null,
        clientResolve = null,
    )

    fun clipFileUrl(): String = writeAutomationBytes("nuvio-automation-clip.mp4", AUTOMATION_CLIP_BYTES)

    fun downloadFileUrl(downloadId: String): String {
        val safe = downloadId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "item" }
        return writeAutomationBytes("nuvio-automation-download-$safe.mp4", AUTOMATION_CLIP_BYTES)
    }

    fun clipByteCount(): Long = AUTOMATION_CLIP_BYTES.size.toLong()

    fun homeState(): HomeUiState {
        val items = listOf(seriesPreview(), moviePreview())
        val section = HomeCatalogSection(
            key = "automation:catalog",
            title = "Automation",
            subtitle = AUTOMATION_ADDON_NAME,
            addonName = AUTOMATION_ADDON_NAME,
            target = CatalogTarget.Addon(
                manifestUrl = "https://automation.invalid/manifest.json",
                contentType = "series",
                catalogId = "automation",
                supportsPagination = false,
            ),
            items = items,
            availableItemCount = items.size,
            hasMore = false,
        )
        return HomeUiState(
            isLoading = false,
            heroItems = items,
            sections = listOf(section),
        )
    }

    fun libraryState(): LibraryUiState {
        val savedAt = 1_577_836_800_000L
        val items = listOf(
            libraryItem(seriesPreview(), savedAt),
            libraryItem(moviePreview(), savedAt - 1),
        )
        return LibraryUiState(
            sourceMode = LibrarySourceMode.LOCAL,
            items = items,
            sections = listOf(
                LibrarySection(type = "series", displayTitle = "Series", items = listOf(items[0])),
                LibrarySection(type = "movie", displayTitle = "Movies", items = listOf(items[1])),
            ),
            isLoaded = true,
            isLoading = false,
        )
    }

    fun meta(type: String, id: String): MetaDetails? = when {
        id == AUTOMATION_SERIES_ID || (type.equals("series", true) && id == AUTOMATION_SERIES_ID) -> series()
        id == AUTOMATION_MOVIE_ID || (type.equals("movie", true) && id == AUTOMATION_MOVIE_ID) -> movie()
        else -> null
    }

    fun series(): MetaDetails = MetaDetails(
        id = AUTOMATION_SERIES_ID,
        type = "series",
        name = AUTOMATION_SERIES_NAME,
        description = "Local automation series. No artwork.",
        releaseInfo = "2020",
        videos = (1..3).flatMap { season ->
            (1..3).map { episode ->
                MetaVideo(
                    id = "$AUTOMATION_SERIES_ID:$season:$episode",
                    title = "Episode $episode",
                    released = AIRED_ON,
                    available = true,
                    season = season,
                    episode = episode,
                    overview = "Season $season, episode $episode.",
                )
            }
        },
    )

    fun movie(): MetaDetails = MetaDetails(
        id = AUTOMATION_MOVIE_ID,
        type = "movie",
        name = AUTOMATION_MOVIE_NAME,
        description = "Local automation movie. No artwork.",
        releaseInfo = "2020",
        runtime = "1 min",
    )

    private fun seriesPreview(): MetaPreview = MetaPreview(
        id = AUTOMATION_SERIES_ID,
        type = "series",
        name = AUTOMATION_SERIES_NAME,
        description = "Local automation series. No artwork.",
        releaseInfo = "2020",
    )

    private fun moviePreview(): MetaPreview = MetaPreview(
        id = AUTOMATION_MOVIE_ID,
        type = "movie",
        name = AUTOMATION_MOVIE_NAME,
        description = "Local automation movie. No artwork.",
        releaseInfo = "2020",
    )

    private fun libraryItem(preview: MetaPreview, savedAt: Long): LibraryItem = LibraryItem(
        id = preview.id,
        type = preview.type,
        name = preview.name,
        description = preview.description,
        releaseInfo = preview.releaseInfo,
        savedAtEpochMs = savedAt,
    )

    private fun source(name: String, bytes: Long, filename: String): StreamItem = StreamItem(
        name = name,
        title = name,
        addonName = AUTOMATION_ADDON_NAME,
        addonId = AUTOMATION_ADDON_ID,
        behaviorHints = StreamBehaviorHints(
            bingeGroup = "automation",
            videoSize = bytes,
            filename = filename,
        ),
        clientResolve = StreamClientResolve(
            type = "debrid",
            service = "automation",
            isCached = true,
            filename = filename,
        ),
    )
}

internal fun ProfileState.withAutomationProfileName(): ProfileState {
    if (!AutomationTestMode.isActive) return this
    val seeded = if (profiles.isEmpty() && activeProfile == null) {
        val profile = automationStubProfile()
        copy(
            profiles = listOf(profile),
            activeProfile = profile,
            isLoaded = true,
            cachePhase = ProfileCachePhase.Ready,
            hasEverSelectedProfile = true,
        )
    } else {
        this
    }
    val renamed = seeded.profiles.map { profile ->
        if (profile.name == AUTOMATION_PROFILE_NAME) profile else profile.copy(name = AUTOMATION_PROFILE_NAME)
    }
    val active = seeded.activeProfile ?: renamed.firstOrNull()
    return seeded.copy(
        profiles = renamed,
        activeProfile = active?.copy(name = AUTOMATION_PROFILE_NAME),
    )
}

/** In-memory only. Id 8001 stays outside the saved 1..6 list and is not written to profile_payload. */
internal fun automationStubProfile(): NuvioProfile = NuvioProfile(
    id = AUTOMATION_PROFILE_ID.toString(),
    profileIndex = AUTOMATION_PROFILE_ID,
    name = AUTOMATION_PROFILE_NAME,
)

/** Empty body, no socket. The URL is not logged. */
internal fun blockedAutomationHttpText(): String? {
    if (!AutomationTestMode.isActive) return null
    AutomationStubs.noteBlockedNetwork()
    return ""
}

internal expect fun writeAutomationBytes(fileName: String, bytes: ByteArray): String
