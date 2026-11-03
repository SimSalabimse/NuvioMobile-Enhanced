package com.nuvio.app.features.offline

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Caller search for the composables this change edits, plus the source markers
 * for every offline action that has to stay on screen.
 */
class OfflineActionCallerTest {
    @Test
    fun changedComposablesKeepTheirCallersAndOfflineActionsStayWired() {
        val bannerHost = read("composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/OfflineBannerHost.kt")
        val downloadsHome = read("composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/OfflineDownloadsHome.kt")
        val home = read("composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt")
        val app = read("composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt")
        val button = read("composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/Components.kt")
        val insets = read("composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/PlatformInsets.kt")
        val tabs = read("composeApp/src/commonMain/kotlin/com/nuvio/app/MainTabsDestination.kt")

        assertEquals(listOf("MainAppContent.kt"), callersOf("OfflineBannerHost("))
        assertEquals(listOf("HomeScreen.kt"), callersOf("OfflineDownloadsHome("))

        assertTrue(app.contains("onRecheck = { NetworkStatusRepository.requestRefresh(force = true) }"))
        assertTrue(bannerHost.contains("onOfflineBannerTap(onRecheck)"))
        assertTrue(bannerHost.contains("offlineBannerFrame("))
        assertTrue(bannerHost.contains("Animatable"))
        assertTrue(bannerHost.contains("OfflineBannerHideFadeMs"))
        assertTrue(home.contains("homeReconnectMotion("))
        assertTrue(home.contains("heroReveal"))
        assertTrue(home.contains("offlineCoverAlpha"))
        assertTrue(!bannerHost.contains("statusTop + OfflineBannerStripHeight"))

        assertTrue(downloadsHome.contains("offline_home_show_home"))
        assertTrue(downloadsHome.contains("onShowHome"))
        assertTrue(downloadsHome.contains("offline_home_open_downloads"))
        assertTrue(downloadsHome.contains("onOpenDownloads"))
        assertTrue(downloadsHome.contains("onOpenDownload"))
        assertTrue(downloadsHome.contains("onTitleLongPress"))
        assertTrue(downloadsHome.contains("onResumeLongPress"))
        assertTrue(downloadsHome.contains("nuvioSafeBottomPadding("))
        assertTrue(downloadsHome.contains("offlineDownloadsHomeTopPaddingDp("))
        assertTrue(home.contains("onShowHome = { showOnlineHomeWhileOffline = true }"))
        assertTrue(home.contains("onOpenDownloads = onOpenDownloads"))

        assertTrue(button.contains("fun NuvioPrimaryButton("))
        assertTrue(insets.contains("tabBarContentInsetDp("))
        assertTrue(tabs.contains("floatingTabContentBottomInsetDp("))
        assertTrue(read("composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsScreen.kt").contains("nuvioSafeBottomPadding("))
        assertTrue(read("composeApp/src/commonMain/kotlin/com/nuvio/app/features/catalog/CatalogScreen.kt").contains("nuvioSafeBottomPadding("))
        assertTrue(read("composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/Components.kt").contains("nuvioSafeBottomPadding("))
    }

    private fun callersOf(symbol: String): List<String> {
        val root = sourceRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readText().contains(symbol) && !file.name.contains("Test")
            }
            .map { it.name }
            .filter { name ->
                symbol != "OfflineBannerHost(" || name != "OfflineBannerHost.kt"
            }
            .filter { name ->
                symbol != "OfflineDownloadsHome(" || name != "OfflineDownloadsHome.kt"
            }
            .sorted()
            .toList()
    }

    private fun read(relative: String): String =
        sourceCandidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error(relative)

    private fun sourceRoot(): File =
        sourceCandidates("composeApp/src/commonMain/kotlin").first { it.isDirectory }

    private fun sourceCandidates(relative: String): List<File> = listOf(
        File(relative),
        File("composeApp/$relative"),
        File("../$relative"),
    )
}
