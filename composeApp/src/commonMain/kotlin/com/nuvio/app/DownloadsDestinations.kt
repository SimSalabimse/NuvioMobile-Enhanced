package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStorageScreen
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsScreen
import com.nuvio.app.features.settings.DownloadsSettingsScreen
import com.nuvio.app.navigation.DownloadShowRoute
import com.nuvio.app.navigation.DownloadsRoute
import com.nuvio.app.navigation.DownloadsSettingsRoute
import com.nuvio.app.navigation.DownloadsStorageRoute
import com.nuvio.app.navigation.NuvioNavigator

@Composable
internal fun DownloadsDestination(
    route: DownloadsRoute,
    navController: NuvioNavigator,
    useNativeNavigation: Boolean,
    onOpenDownload: (DownloadItem) -> Unit,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    val settingsRoute = downloadsSettingsPushRoute(
        useNativeNavigation = useNativeNavigation,
        title = route.title,
    )
    DownloadsScreen(
        onBack = onBack,
        onOpenDownload = onOpenDownload,
        onOpenSettings = settingsRoute?.let { target ->
            { navController.navigate(target) }
        },
        onOpenStorage = downloadsStoragePushRoute(useNativeNavigation, route.title)?.let { target ->
            { navController.navigate(target) }
        },
        onNavigateToShow = if (useNativeNavigation) {
            { showId, title -> navController.navigate(DownloadShowRoute(showId, title)) }
        } else {
            null
        },
    )
}

@Composable
internal fun DownloadsSettingsDestination(
    route: DownloadsSettingsRoute,
    navController: NuvioNavigator,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    DownloadsSettingsScreen(onBack = onBack)
}

@Composable
internal fun DownloadShowDestination(
    route: DownloadShowRoute,
    navController: NuvioNavigator,
    onOpenDownload: (DownloadItem) -> Unit,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    DownloadsScreen(
        onBack = onBack,
        onOpenDownload = onOpenDownload,
        initialShowId = route.showId,
        onBackFromShow = onBack,
    )
}

@Composable
internal fun DownloadsStorageDestination(
    route: DownloadsStorageRoute,
    navController: NuvioNavigator,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    DownloadStorageScreen(items = uiState.items, onBack = onBack)
}

/**
 * Storage is pushed, like settings, when native navigation owns back: drawn over the downloads
 * list, the native back button and swipe popped Downloads itself and landed on the Library.
 */
internal fun downloadsStoragePushRoute(
    useNativeNavigation: Boolean,
    title: String,
): DownloadsStorageRoute? = if (useNativeNavigation) DownloadsStorageRoute(title) else null

/** Native back pops one screen, so settings is pushed instead of drawn over the downloads list. */
internal fun downloadsSettingsPushRoute(
    useNativeNavigation: Boolean,
    title: String,
): DownloadsSettingsRoute? = if (useNativeNavigation) DownloadsSettingsRoute(title) else null
