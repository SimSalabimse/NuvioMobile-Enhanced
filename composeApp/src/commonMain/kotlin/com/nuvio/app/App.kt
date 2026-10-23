package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.nuvio.app.core.perf.PerformanceOptimization
import com.nuvio.app.core.perf.PerformanceSettings
import com.nuvio.app.core.perf.addPerformanceInterceptors
import com.nuvio.app.core.perf.applyPosterMemoryCachePolicy
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.configurePlatformImageLoader
import com.nuvio.app.core.ui.platformProvidesImageLoader
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.TabsRoute

fun disposeRoute(route: AppRoute) {
    disposeRouteResources(route)
}

@OptIn(ExperimentalCoilApi::class)
@Composable
@Preview
fun App(
    initialTab: AppScreenTab = AppScreenTab.Home,
    initialRoute: AppRoute = TabsRoute,
    useNativeNavigation: Boolean = false,
    useNativeTabBar: Boolean = false,
    useTabletFloatingTabBar: Boolean = false,
    ownsAppRuntime: Boolean = true,
    bypassAppGate: Boolean = false,
    onNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)? = null,
    onGoBack: (() -> Unit)? = null,
    onReplace: ((AppRoute) -> Unit)? = null,
    onActivate: ((AppScreenTab) -> Unit)? = null,
    onAppReady: ((Boolean) -> Unit)? = null,
    onTabTitles: ((home: String, search: String, library: String, profile: String, switchProfile: String, addProfile: String) -> Unit)? = null,
    nativeProfileSwitcherController: NativeProfileSwitcherController? = null,
    appGateController: AppGateController? = null,
) {
    AppEnvironment {
        AppGate(
            initialTab = initialTab,
            initialRoute = initialRoute,
            useNativeNavigation = useNativeNavigation,
            useNativeTabBar = useNativeTabBar,
            useTabletFloatingTabBar = useTabletFloatingTabBar,
            ownsAppRuntime = ownsAppRuntime,
            bypassAppGate = bypassAppGate,
            renderMainContent = true,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = onActivate,
            onAppReady = onAppReady,
            onMainContentMountChanged = null,
            onMainContentVisibleChanged = null,
            onTabTitles = onTabTitles,
            nativeProfileSwitcherController = nativeProfileSwitcherController,
            appGateController = appGateController,
        )
    }
}

@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun AppEnvironment(content: @Composable () -> Unit) {
    if (!platformProvidesImageLoader) {
        setSingletonImageLoaderFactory { context ->
            ImageLoader.Builder(context)
                .crossfade(true)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .components {
                    add(com.nuvio.app.features.offline.OfflineArtworkInterceptor())
                    add(CustomPosterFallbackInterceptor())
                    addPerformanceInterceptors()
                    add(SvgDecoder.Factory())
                    add(
                        coil3.network.ktor3.KtorNetworkFetcherFactory(
                            cacheStrategy = { coil3.network.cachecontrol.CacheControlCacheStrategy() },
                        ),
                    )
                }
                .configurePlatformImageLoader()
                .build()
        }
    }
    val platformContext = LocalPlatformContext.current
    val performanceState by remember {
        PerformanceSettings.ensureLoaded()
        PerformanceSettings.state
    }.collectAsStateWithLifecycle()
    val posterDownsampling = performanceState[PerformanceOptimization.PosterDownsampling] ?: true
    LaunchedEffect(platformContext, posterDownsampling) {
        SingletonImageLoader.get(platformContext).applyPosterMemoryCachePolicy(posterDownsampling)
    }
    val selectedTheme by remember {
        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.selectedTheme
    }.collectAsStateWithLifecycle()
    val amoledEnabled by remember {
        ThemeSettingsRepository.amoledEnabled
    }.collectAsStateWithLifecycle()

    val customThemeColors by ThemeSettingsRepository.customThemeColors.collectAsStateWithLifecycle()

    NuvioTheme(appTheme = selectedTheme, amoled = amoledEnabled, customThemeColors = customThemeColors) {
        content()
    }
}
