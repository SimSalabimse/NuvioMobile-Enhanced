package com.nuvio.app.core.network

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineBannerHintInventoryTest {
    @Test
    fun removedDuplicateHintsAreGoneAndKeptHintsStay() {
        assertEquals(removed.map { it.id }, removedIds)
        assertEquals(kept.map { it.id }, keptIds)
        val kotlin = kotlinSources()
        assertFalse(kotlin.any { it.contains("NuvioNetworkOfflineCard") }, "offline card composable remains")
        assertFalse(sourceCandidates("composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/NetworkOfflineCard.kt").any { it.isFile })
        for (hint in removed) {
            assertTrue(hint.because.isNotBlank(), hint.id)
            val source = read(hint.path)
            assertFalse(source.contains(hint.marker), "${hint.id} still in ${hint.path}")
        }
        for (hint in kept) {
            assertTrue(hint.because.isNotBlank(), hint.id)
            val source = read(hint.path)
            assertTrue(source.contains(hint.marker), "${hint.id} missing from ${hint.path}")
        }
        val english = read("composeApp/src/commonMain/composeResources/values/strings.xml")
        assertTrue(english.contains("<string name=\"offline_banner_saved_data\">Offline: showing saved data</string>"))
        val resourceRoot = sourceCandidates("composeApp/src/commonMain/composeResources").first { it.isDirectory }
        resourceRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith("values") }?.forEach { dir ->
            val strings = dir.resolve("strings.xml")
            if (!strings.isFile) return@forEach
            val text = strings.readText()
            if (text.contains("network_no_internet_connection")) {
                assertTrue(
                    text.contains("offline_banner_saved_data"),
                    "${dir.name} is missing the offline banner string",
                )
            }
        }
    }

    private data class Hint(
        val id: String,
        val path: String,
        val marker: String,
        val because: String,
    )

    private val removed = listOf(
        Hint(
            "home-offline-card",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt",
            "NuvioNetworkOfflineCard",
            "Home empty states repeated the offline status.",
        ),
        Hint(
            "library-offline-card",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryScreen.kt",
            "NuvioNetworkOfflineCard",
            "Library load failures repeated the offline status.",
        ),
        Hint(
            "catalog-offline-card",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/catalog/CatalogScreen.kt",
            "NuvioNetworkOfflineCard",
            "Catalog empty states repeated the offline status.",
        ),
        Hint(
            "search-offline-card",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/search/SearchScreen.kt",
            "NuvioNetworkOfflineCard",
            "Search empty states repeated the offline status.",
        ),
        Hint(
            "discover-offline-card",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/search/SearchDiscoverContent.kt",
            "NuvioNetworkOfflineCard",
            "Discover empty states repeated the offline status.",
        ),
        Hint(
            "offline-toast-no-internet",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt",
            "network_no_internet_connection",
            "A toast repeated the offline status.",
        ),
        Hint(
            "offline-toast-servers",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt",
            "network_cannot_reach_servers",
            "A toast repeated the servers-unreachable status.",
        ),
        Hint(
            "details-check-connection",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsScreen.kt",
            "details_check_connection",
            "The title failure body was replaced with an offline hint.",
        ),
        Hint(
            "details-servers-unreachable",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsScreen.kt",
            "details_servers_unreachable",
            "The title failure body was replaced with a servers hint.",
        ),
        Hint(
            "downloads-home-title",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/OfflineDownloadsHome.kt",
            "offline_home_title",
            "The downloads home headline repeated that you are offline.",
        ),
        Hint(
            "downloads-home-message",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/OfflineDownloadsHome.kt",
            "offline_home_message",
            "The downloads home tagline restated the offline banner.",
        ),
    )

    private val kept = listOf(
        Hint(
            "downloads-home-open",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/OfflineDownloadsHome.kt",
            "offline_home_open_downloads",
            "The downloads-first home still opens the download library.",
        ),
        Hint(
            "downloads-home-show",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/OfflineDownloadsHome.kt",
            "offline_home_show_home",
            "The downloads-first home can still show the catalog.",
        ),
        Hint(
            "downloads-home-rows",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/OfflineDownloadsHome.kt",
            "offline_home_downloaded",
            "Saved downloads stay listed on that home.",
        ),
        Hint(
            "details-load-failed",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsScreen.kt",
            "details_failed_to_load",
            "A title that failed to load still says so and can be loaded again.",
        ),
        Hint(
            "player-stall",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlaybackStall.kt",
            "player_stall_connection_offline",
            "A stall inside the full-screen player explains playback, and the banner is hidden there.",
        ),
        Hint(
            "profile-pin",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/ProfileRepository.kt",
            "profile_pin_offline_verification_requires_online",
            "A PIN that has not been unlocked online yet is a different message.",
        ),
        Hint(
            "back-online-toast",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt",
            "network_back_online",
            "Coming back online is a different message from being offline.",
        ),
        Hint(
            "party-presence",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/party/PartyUi.kt",
            "party_status_offline",
            "Watch party presence is about a person, not this device's connection.",
        ),
    )

    private val removedIds = listOf(
        "home-offline-card",
        "library-offline-card",
        "catalog-offline-card",
        "search-offline-card",
        "discover-offline-card",
        "offline-toast-no-internet",
        "offline-toast-servers",
        "details-check-connection",
        "details-servers-unreachable",
        "downloads-home-title",
        "downloads-home-message",
    )

    private val keptIds = listOf(
        "downloads-home-open",
        "downloads-home-show",
        "downloads-home-rows",
        "details-load-failed",
        "player-stall",
        "profile-pin",
        "back-online-toast",
        "party-presence",
    )

    private fun read(relative: String): String =
        sourceCandidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error(relative)

    private fun sourceCandidates(relative: String): List<File> = listOf(
        File(relative),
        File("composeApp/$relative"),
        File("../$relative"),
    )

    private fun kotlinSources(): List<String> {
        val root = sourceCandidates("composeApp/src/commonMain").first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
    }
}
