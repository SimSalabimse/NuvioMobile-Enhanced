package com.nuvio.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.DeviceSessionRegistration
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.startup.SynchronousLaunch
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.profiles.FirstFrameDestination
import com.nuvio.app.features.profiles.coldLaunchSkipsProfilePicker
import com.nuvio.app.features.profiles.showStartupLaunchCover
import com.nuvio.app.core.perf.PerformanceOptimization
import com.nuvio.app.core.perf.PerformanceSettings
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.ui.LocalVerticalBarPadding
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.core.ui.NativeTabBridge
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.auth.AuthScreen
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileBiometricAuth
import com.nuvio.app.features.profiles.ProfileBiometricResult
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileCachePhase
import com.nuvio.app.features.profiles.ProfileCacheReadTimeoutMs
import com.nuvio.app.features.profiles.ProfileEditScreen
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSelectionScreen
import com.nuvio.app.features.profiles.ProfileStartupDestination
import com.nuvio.app.features.profiles.ProfileStartupGateInput
import com.nuvio.app.features.profiles.ProfileStartupProfile
import com.nuvio.app.features.profiles.nextStartupGate
import com.nuvio.app.features.profiles.profileAvatarImageUrl
import com.nuvio.app.navigation.AppRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class AppGateScreen {
    Loading,
    Auth,
    ProfileSelection,
    ProfileEdit,
    Main,
}

// Keep roughly in step with AppLoadingContent's own entrance timeline (AppShellComponents.kt) —
// this is how long the profile-select transition overlay stays up at minimum, regardless of how
// quickly the real content underneath becomes ready. Two budgets: the tap→center flow's own
// entrance glide already takes ~550ms on its own, so it only needs a brief settle on top of that
// before the exit is allowed to start; the no-origin (native profile switcher) path instead plays
// a slower spring-bounce entrance and needs the longer runway to fully settle before being cut
// off — shortening it regressed into exactly that cut-off-mid-bounce glitch once before.
private const val ProfileTransitionMinDurationWithOriginMs = 650L
private const val ProfileTransitionMinDurationMs = 1000L

@Composable
internal fun AppGate(
    initialTab: AppScreenTab,
    initialRoute: AppRoute,
    useNativeNavigation: Boolean,
    useNativeTabBar: Boolean,
    useTabletFloatingTabBar: Boolean,
    ownsAppRuntime: Boolean,
    bypassAppGate: Boolean,
    renderMainContent: Boolean,
    onNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)?,
    onGoBack: (() -> Unit)?,
    onReplace: ((AppRoute) -> Unit)?,
    onActivate: ((AppScreenTab) -> Unit)?,
    onAppReady: ((Boolean) -> Unit)?,
    onMainContentMountChanged: ((Boolean) -> Unit)?,
    onMainContentVisibleChanged: ((Boolean) -> Unit)?,
    onTabTitles: ((home: String, search: String, library: String, profile: String, switchProfile: String, addProfile: String) -> Unit)?,
    nativeProfileSwitcherController: NativeProfileSwitcherController?,
    appGateController: AppGateController?,
) {
    if (bypassAppGate) {
        MainAppContent(
            initialTab = initialTab,
            initialRoute = initialRoute,
            useNativeNavigation = useNativeNavigation,
            useNativeTabBar = useNativeTabBar,
            useTabletFloatingTabBar = useTabletFloatingTabBar,
            ownsAppRuntime = ownsAppRuntime,
            showLaunchOverlay = appGateController == null,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = onActivate,
            onTabTitles = onTabTitles,
            appGateController = appGateController,
            onRootContentReady = appGateController?.let { controller ->
                controller::reportMainContentReady
            },
            onSwitchProfile = appGateController?.let { controller ->
                controller::requestProfileSelection
            } ?: {},
        )
        return
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        InAppLogger.info("App", "Application started")
        AuthRepository.initialize()
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        InAppLogger.info("App/Runtime", "Starting network/profile/avatar services")
        NetworkStatusRepository.ensureStarted()
        MemberAccessRepository.ensureStarted()
        ProfileRepository.loadCachedProfiles()
        if (PerformanceSettings.isEnabled(PerformanceOptimization.CachedHomeFirst)) {
            withContext(Dispatchers.Default) { HomeRepository.preloadSnapshotForLaunch() }
        }
    }

    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val profileAvatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val launchFrame = remember { SynchronousLaunch.prepare() }

    LaunchedEffect(authState) {
        if (!ownsAppRuntime) return@LaunchedEffect
        DeviceSessionRegistration.registerIfAuthenticated(force = true)
    }

    LaunchedEffect(
        profileState.activeProfile?.profileIndex,
        profileState.activeProfile?.name,
        profileState.activeProfile?.avatarColorHex,
        profileState.activeProfile?.avatarId,
        profileState.activeProfile?.avatarUrl,
        profileAvatars,
    ) {
        val activeProfile = profileState.activeProfile
        val avatarItem = activeProfile?.avatarId?.let { avatarId ->
            profileAvatars.find { it.id == avatarId }
        }
        NativeTabBridge.publishProfileTabIcon(
            name = activeProfile?.name,
            avatarColorHex = activeProfile?.avatarColorHex,
            avatarImageUrl = activeProfile?.let { profileAvatarImageUrl(it, avatarItem) },
            avatarBackgroundColorHex = avatarItem?.bgColor,
        )
    }

    val gateScope = rememberCoroutineScope()
    var gateScreen by rememberSaveable {
        mutableStateOf(
            when (launchFrame.destination) {
                FirstFrameDestination.Home -> AppGateScreen.Main.name
                FirstFrameDestination.Picker -> AppGateScreen.ProfileSelection.name
                FirstFrameDestination.SignIn -> AppGateScreen.Auth.name
            },
        )
    }
    var profilePickerRequestedByUser by rememberSaveable { mutableStateOf(false) }
    if (
        coldLaunchSkipsProfilePicker(launchFrame.destination) &&
        !profilePickerRequestedByUser &&
        gateScreen != AppGateScreen.Main.name
    ) {
        gateScreen = AppGateScreen.Main.name
    }
    var snapHomeOverPicker by remember { mutableStateOf(false) }
    LaunchedEffect(gateScreen) {
        if (!ownsAppRuntime) return@LaunchedEffect
        if (
            gateScreen == AppGateScreen.ProfileSelection.name ||
            gateScreen == AppGateScreen.ProfileEdit.name
        ) {
            AvatarRepository.fetchAvatars()
        }
    }
    var editingProfile by remember { mutableStateOf<NuvioProfile?>(null) }
    var profileEditReturnScreen by rememberSaveable { mutableStateOf(AppGateScreen.ProfileSelection.name) }
    var autoSkipProfileSelection by rememberSaveable {
        mutableStateOf(launchFrame.destination == FirstFrameDestination.Picker)
    }
    var profileSelectionLoading by rememberSaveable { mutableStateOf(false) }
    var profileSelectionTransitionActive by rememberSaveable { mutableStateOf(false) }
    // The profile just tapped, shown bouncing into center on the transition overlay below
    // (Netflix-style) instead of the generic wordmark. Not `rememberSaveable`: NuvioProfile isn't
    // a saveable type, and this is purely a transient animation cue anyway.
    var transitioningProfile by remember { mutableStateOf<NuvioProfile?>(null) }
    // Where the tapped avatar sat on screen at the moment of the tap (window coordinates), so the
    // transition overlay can glide its emblem in from there instead of just appearing in place.
    // Null for transitions with no such originating tap (e.g. the native profile switcher), in
    // which case the overlay falls back to its old drift-down-from-above entrance.
    var profileTransitionOrigin by remember { mutableStateOf<Offset?>(null) }
    // The main content usually becomes ready well before AppLoadingContent's entrance animation
    // finishes playing, since both start at the same moment a profile is picked — without this,
    // the transition overlay was torn down the instant content was ready, cutting the animation
    // off partway through instead of letting it play out. `contentReadyDuringTransition` records
    // that signal without acting on it immediately; the overlay only actually closes once that
    // AND the minimum duration below have both happened.
    var contentReadyDuringTransition by remember { mutableStateOf(false) }
    var profileTransitionMinDurationElapsed by remember { mutableStateOf(true) }
    // Set once both of the above are true, to play AppLoadingContent's exit-toward-the-Profile-tab
    // animation; only once *that* finishes does it actually close the overlay.
    var profileTransitionExiting by remember { mutableStateOf(false) }
    // Prevent profile sync/state updates from launching a second startup biometric prompt while the
    // first system prompt is still visible. The guard is transient and clears as soon as the attempt ends.
    var startupBiometricInProgress by remember { mutableStateOf(false) }

    // Resetting these three flags has to happen synchronously, in the very same recomposition
    // that flips `profileSelectionTransitionActive` on — not from a LaunchedEffect keyed on it,
    // which only runs *after* that recomposition. On a second/third profile switch, the previous
    // cycle leaves `profileTransitionExiting` (etc.) sitting at `true`; AppLoadingContent gets
    // freshly recomposed for the new cycle and, if it reads that stale `true` on its very first
    // frame, immediately plays its exit-and-close sequence instead of ever really appearing. This
    // was the exact bug reported: the animation worked once, then silently "didn't show" after.
    fun beginProfileTransition(profile: NuvioProfile, origin: Offset? = null) {
        profileTransitionMinDurationElapsed = false
        contentReadyDuringTransition = false
        profileTransitionExiting = false
        transitioningProfile = profile
        profileTransitionOrigin = origin
        profileSelectionLoading = true
        profileSelectionTransitionActive = true
    }

    LaunchedEffect(profileSelectionTransitionActive) {
        if (!profileSelectionTransitionActive) return@LaunchedEffect
        val minDuration = if (profileTransitionOrigin != null) {
            ProfileTransitionMinDurationWithOriginMs
        } else {
            ProfileTransitionMinDurationMs
        }
        kotlinx.coroutines.delay(minDuration)
        profileTransitionMinDurationElapsed = true
    }

    LaunchedEffect(contentReadyDuringTransition, profileTransitionMinDurationElapsed) {
        if (contentReadyDuringTransition && profileTransitionMinDurationElapsed) {
            profileTransitionExiting = true
        }
    }
    var skipProfileSelectionEnterAnimation by remember { mutableStateOf(false) }
    var mainContentStarted by rememberSaveable { mutableStateOf(false) }
    val externalMainContentReady = if (!renderMainContent && appGateController != null) {
        val ready by appGateController.mainContentReady.collectAsStateWithLifecycle()
        ready
    } else {
        false
    }

    LaunchedEffect(profileSelectionTransitionActive) {
        if (!profileSelectionTransitionActive) return@LaunchedEffect
        kotlinx.coroutines.delay(20_000)
        profileSelectionLoading = false
        profileSelectionTransitionActive = false
    }

    fun reportAppReady(ready: Boolean) {
        appGateController?.reportAppReady(ready)
        onAppReady?.invoke(ready)
    }

    LaunchedEffect(gateScreen, onAppReady) {
        if (gateScreen != AppGateScreen.Main.name) {
            reportAppReady(false)
        }
    }

    LaunchedEffect(gateScreen, renderMainContent, onMainContentMountChanged) {
        if (renderMainContent) return@LaunchedEffect
        when (gateScreen) {
            AppGateScreen.Main.name -> {
                mainContentStarted = true
                onMainContentMountChanged?.invoke(true)
            }
            AppGateScreen.Loading.name,
            AppGateScreen.Auth.name,
            -> {
                mainContentStarted = false
                appGateController?.reportMainContentReady(false)
                onMainContentMountChanged?.invoke(false)
            }
            else -> onMainContentMountChanged?.invoke(mainContentStarted)
        }
    }

    LaunchedEffect(appGateController, renderMainContent) {
        if (renderMainContent) return@LaunchedEffect
        appGateController?.profileSelectionRequests?.collect {
            autoSkipProfileSelection = false
            profilePickerRequestedByUser = true
            snapHomeOverPicker = false
            profileSelectionLoading = false
            profileSelectionTransitionActive = false
            skipProfileSelectionEnterAnimation = true
            gateScreen = AppGateScreen.ProfileSelection.name
        }
    }

    LaunchedEffect(nativeProfileSwitcherController, appGateController, renderMainContent) {
        if (renderMainContent || appGateController == null) return@LaunchedEffect
        nativeProfileSwitcherController?.requestedManageProfiles?.collect {
            appGateController.requestProfileSelection()
        }
    }

    LaunchedEffect(nativeProfileSwitcherController, appGateController, renderMainContent) {
        if (renderMainContent || appGateController == null) return@LaunchedEffect
        nativeProfileSwitcherController?.selectedProfileIndices?.collect { profileIndex ->
            if (profileIndex == ProfileRepository.state.value.activeProfile?.profileIndex) return@collect
            val profile = ProfileRepository.state.value.profiles
                .firstOrNull { it.profileIndex == profileIndex }
                ?: return@collect
            autoSkipProfileSelection = false
            beginProfileTransition(profile)
            skipProfileSelectionEnterAnimation = true
            appGateController.beginContentReload()
            ProfileRepository.selectProfile(profile.profileIndex)
            SyncManager.pullAllForProfile(profile.profileIndex)
            gateScreen = AppGateScreen.Main.name
            onActivate?.invoke(AppScreenTab.Home)
        }
    }

    // Keyed on `profileSelectionTransitionActive` too: on the very first profile pick, content
    // isn't ready yet, so this fires once `externalMainContentReady` later flips true. On a
    // subsequent switch-profile, though, Home content is usually *already* ready from the
    // previous profile — `externalMainContentReady` never changes value again, so a LaunchedEffect
    // keyed only on it would never re-fire, and the transition would never know content was ready
    // this time around (the exact bug reported: the animation worked once, then got stuck/skipped
    // on every switch after).
    LaunchedEffect(externalMainContentReady, renderMainContent, profileSelectionTransitionActive) {
        if (!renderMainContent && externalMainContentReady && profileSelectionTransitionActive) {
            contentReadyDuringTransition = true
        }
    }

    LaunchedEffect(
        renderMainContent,
        gateScreen,
        externalMainContentReady,
        onMainContentVisibleChanged,
    ) {
        if (!renderMainContent) {
            onMainContentVisibleChanged?.invoke(
                gateScreen == AppGateScreen.Main.name && externalMainContentReady,
            )
        }
    }

    fun selectProfile(profile: NuvioProfile, sync: Boolean) {
        if (!renderMainContent) {
            appGateController?.beginContentReload()
        }
        ProfileRepository.selectProfile(profile.profileIndex)
        if (sync) {
            SyncManager.pullAllForProfile(profile.profileIndex)
        }
    }

    fun startupDestination(): ProfileStartupDestination = when (gateScreen) {
        AppGateScreen.Main.name -> ProfileStartupDestination.Main
        AppGateScreen.ProfileSelection.name -> ProfileStartupDestination.ProfileSelection
        else -> ProfileStartupDestination.Loading
    }

    fun startupGateInput(): ProfileStartupGateInput {
        val fresh = ProfileRepository.state.value
        return ProfileStartupGateInput(
            phase = fresh.cachePhase,
            profiles = fresh.profiles.map { profile ->
                ProfileStartupProfile(profile.profileIndex, profile.pinEnabled)
            },
            rememberLastProfileEnabled = fresh.rememberLastProfileEnabled,
            hasEverSelectedProfile = fresh.hasEverSelectedProfile,
            activeProfileIndex = ProfileRepository.activeProfileId,
            readTimedOut = false,
        )
    }

    fun openStartupHome(profile: NuvioProfile, snap: Boolean) {
        if (snap) snapHomeOverPicker = true
        selectProfile(profile, sync = false)
        autoSkipProfileSelection = false
        profilePickerRequestedByUser = false
        gateScreen = AppGateScreen.Main.name
    }

    suspend fun applyAutomaticGate() {
        if (gateScreen == AppGateScreen.ProfileEdit.name) return
        if (startupBiometricInProgress) return
        val current = startupDestination()
        if (profilePickerRequestedByUser && current == ProfileStartupDestination.ProfileSelection) return
        var decision = nextStartupGate(
            current = current,
            input = startupGateInput(),
            pickerOpenedByUser = profilePickerRequestedByUser,
        )
        val fresh = ProfileRepository.state.value
        if (
            decision.destination == ProfileStartupDestination.ProfileSelection &&
            (gateScreen == AppGateScreen.Loading.name || gateScreen == AppGateScreen.Auth.name) &&
            fresh.rememberLastProfileEnabled &&
            fresh.hasEverSelectedProfile
        ) {
            val rememberedLockedProfile = fresh.profiles.find {
                it.profileIndex == ProfileRepository.activeProfileId && it.pinEnabled && it.profileIndex == 1
            }
            if (rememberedLockedProfile != null) {
                startupBiometricInProgress = true
                val unlocked = try {
                    val biometricResult = try {
                        ProfileBiometricAuth.authenticate(1, rememberedLockedProfile.userId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ProfileBiometricResult.Failed
                    }
                    biometricResult == ProfileBiometricResult.Success
                } finally {
                    startupBiometricInProgress = false
                }
                if (unlocked) {
                    openStartupHome(rememberedLockedProfile, snap = false)
                    return
                }
                decision = nextStartupGate(
                    current = startupDestination(),
                    input = startupGateInput(),
                    pickerOpenedByUser = false,
                )
            }
        }
        when (decision.destination) {
            ProfileStartupDestination.Loading -> Unit
            ProfileStartupDestination.Main -> {
                if (gateScreen != AppGateScreen.Main.name) {
                    val profile = fresh.profiles.firstOrNull { it.profileIndex == decision.selectProfileIndex }
                        ?: ProfileRepository.state.value.profiles.firstOrNull {
                            it.profileIndex == decision.selectProfileIndex
                        }
                    if (profile != null) {
                        openStartupHome(profile, snap = decision.snapHomeOverPicker)
                    }
                }
            }
            ProfileStartupDestination.ProfileSelection -> {
                if (
                    gateScreen == AppGateScreen.Loading.name ||
                    gateScreen == AppGateScreen.Auth.name ||
                    (gateScreen == AppGateScreen.ProfileSelection.name && decision.snapHomeOverPicker)
                ) {
                    autoSkipProfileSelection = true
                    profilePickerRequestedByUser = false
                    profileSelectionLoading = false
                    profileSelectionTransitionActive = false
                    gateScreen = AppGateScreen.ProfileSelection.name
                }
            }
        }
    }

    LaunchedEffect(
        authState,
        profileState.cachePhase,
        profileState.profiles,
        profileState.rememberLastProfileEnabled,
        profileState.hasEverSelectedProfile,
        profileState.activeProfile?.profileIndex,
    ) {
        val fresh = ProfileRepository.state.value
        val stillReading = fresh.cachePhase == ProfileCachePhase.Reading
        when (authState) {
            is AuthState.Loading -> {
                if (
                    gateScreen == AppGateScreen.Main.name ||
                    gateScreen == AppGateScreen.ProfileSelection.name ||
                    gateScreen == AppGateScreen.Auth.name ||
                    gateScreen == AppGateScreen.ProfileEdit.name
                ) {
                    return@LaunchedEffect
                }
                if (fresh.profiles.isNotEmpty() && !stillReading) {
                    applyAutomaticGate()
                }
            }
            is AuthState.Unauthenticated -> {
                if (fresh.profiles.isNotEmpty() && !stillReading) {
                    applyAutomaticGate()
                } else if (!stillReading) {
                    if (fresh.cachePhase != ProfileCachePhase.Reading) {
                        ProfileRepository.clearInMemory()
                    }
                    profileSelectionLoading = false
                    profileSelectionTransitionActive = false
                    profilePickerRequestedByUser = false
                    gateScreen = AppGateScreen.Auth.name
                }
            }
            is AuthState.Authenticated -> {
                val authenticatedState = authState as AuthState.Authenticated
                val phase = ProfileRepository.state.value.cachePhase
                if (phase == ProfileCachePhase.Reading) {
                    withTimeoutOrNull(ProfileCacheReadTimeoutMs) {
                        ProfileRepository.ensureLoaded(authenticatedState.userId)
                    }
                } else {
                    ProfileRepository.ensureLoaded(authenticatedState.userId)
                }
                applyAutomaticGate()
            }
        }
    }

    LaunchedEffect((authState as? AuthState.Authenticated)?.userId) {
        val authenticatedState = authState as? AuthState.Authenticated ?: return@LaunchedEffect
        ProfileRepository.ensureLoaded(authenticatedState.userId)
        ProfileRepository.pullProfiles()
    }

    val profileOverlayVisible =
        gateScreen == AppGateScreen.ProfileSelection.name || profileSelectionLoading
    val profileOverlayState = remember {
        MutableTransitionState(profileOverlayVisible)
    }
    profileOverlayState.targetState = profileOverlayVisible
    LaunchedEffect(snapHomeOverPicker, gateScreen, profileOverlayState.isIdle, profileOverlayState.currentState) {
        if (
            snapHomeOverPicker &&
            gateScreen == AppGateScreen.Main.name &&
            profileOverlayState.isIdle &&
            !profileOverlayState.currentState
        ) {
            snapHomeOverPicker = false
        }
    }
    val launchOverlayVisible =
        !renderMainContent &&
            showStartupLaunchCover(
                destination = launchFrame.destination,
                gateIsHome = gateScreen == AppGateScreen.Main.name,
                mainContentReady = externalMainContentReady,
            )
    val launchOverlayState = remember {
        MutableTransitionState(launchOverlayVisible)
    }
    launchOverlayState.targetState = launchOverlayVisible

    LaunchedEffect(
        renderMainContent,
        gateScreen,
        externalMainContentReady,
        profileSelectionLoading,
        profileOverlayState.currentState,
        profileOverlayState.isIdle,
        launchOverlayState.currentState,
        launchOverlayState.isIdle,
        onAppReady,
    ) {
        if (renderMainContent) return@LaunchedEffect
        val overlaysHidden =
            profileOverlayState.isIdle &&
                !profileOverlayState.currentState &&
                launchOverlayState.isIdle &&
                !launchOverlayState.currentState
        reportAppReady(
            gateScreen == AppGateScreen.Main.name &&
                externalMainContentReady &&
                !profileSelectionLoading &&
                overlaysHidden,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = gateScreen,
            label = "app_gate",
            transitionSpec = {
                if (snapHomeOverPicker) {
                    androidx.compose.animation.EnterTransition.None
                        .togetherWith(androidx.compose.animation.ExitTransition.None)
                } else {
                    (fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.94f))
                        .togetherWith(fadeOut(tween(250)))
                }
            },
        ) { currentGate ->
            when (currentGate) {
                AppGateScreen.Loading.name -> {
                    AppIntroContent(modifier = Modifier.fillMaxSize())
                }
                AppGateScreen.Auth.name -> {
                    AuthScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.nuvio.colors.background)
                            .padding(LocalVerticalBarPadding.current),
                    )
                }
                AppGateScreen.ProfileSelection.name -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.nuvio.colors.background),
                    )
                }
                AppGateScreen.ProfileEdit.name -> {
                    // A brand-new anonymous guest with no profiles yet lands here straight from
                    // "Continue Without Account" — creating a profile is mandatory before this
                    // point, so profileEditReturnScreen is ProfileSelection, but that screen has
                    // nothing to show them but the same "add a profile" prompt with no way out
                    // either. Cancelling out of profile creation in that specific situation should
                    // undo the guest choice and return to the login form, not loop back into it.
                    val returnFromProfileEdit = {
                        val isForcedGuestProfileCreation =
                            profileEditReturnScreen == AppGateScreen.ProfileSelection.name &&
                                profileState.profiles.isEmpty() &&
                                (authState as? AuthState.Authenticated)?.isAnonymous == true
                        if (isForcedGuestProfileCreation) {
                            gateScope.launch { AuthRepository.signOut() }
                        } else {
                            gateScreen = profileEditReturnScreen
                        }
                        Unit
                    }
                    PlatformBackHandler(enabled = gateScreen == AppGateScreen.ProfileEdit.name) {
                        returnFromProfileEdit()
                    }
                    ProfileEditScreen(
                        profile = editingProfile,
                        onBack = returnFromProfileEdit,
                        onSaved = { gateScreen = profileEditReturnScreen },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AppGateScreen.Main.name -> {
                    if (renderMainContent) {
                        MainAppContent(
                            initialTab = initialTab,
                            initialRoute = initialRoute,
                            useNativeNavigation = useNativeNavigation,
                            useNativeTabBar = useNativeTabBar,
                            useTabletFloatingTabBar = useTabletFloatingTabBar,
                            ownsAppRuntime = ownsAppRuntime,
                            showLaunchOverlay = !profileSelectionLoading &&
                                !coldLaunchSkipsProfilePicker(launchFrame.destination),
                            onNavigate = onNavigate,
                            onGoBack = onGoBack,
                            onReplace = onReplace,
                            onActivate = onActivate,
                            onTabTitles = onTabTitles,
                            appGateController = appGateController,
                            onRootContentReady = { ready ->
                                if (ready) {
                                    contentReadyDuringTransition = true
                                }
                                onAppReady?.invoke(
                                    ready && gateScreen == AppGateScreen.Main.name && !profileSelectionLoading,
                                )
                            },
                            onSwitchProfile = {
                                autoSkipProfileSelection = false
                                profilePickerRequestedByUser = true
                                snapHomeOverPicker = false
                                profileSelectionLoading = false
                                profileSelectionTransitionActive = false
                                skipProfileSelectionEnterAnimation = false
                                gateScreen = AppGateScreen.ProfileSelection.name
                            },
                        )
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visibleState = launchOverlayState,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.fillMaxSize(),
        ) {
            AppLaunchOverlay(
                profile = profileState.activeProfile ?: profileState.profiles.firstOrNull(),
                modifier = Modifier.fillMaxSize(),
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visibleState = profileOverlayState,
            enter = if (skipProfileSelectionEnterAnimation) {
                androidx.compose.animation.EnterTransition.None
            } else {
                fadeIn(tween(400))
            },
            exit = if (snapHomeOverPicker) {
                androidx.compose.animation.ExitTransition.None
            } else {
                fadeOut(tween(400))
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(NuvioTokens.Z.dialog),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val onBack: (() -> Unit)? = if (!autoSkipProfileSelection) {
                    {
                        skipProfileSelectionEnterAnimation = false
                        gateScreen = AppGateScreen.Main.name
                    }
                } else {
                    null
                }
                PlatformBackHandler(
                    enabled = gateScreen == AppGateScreen.ProfileSelection.name && !profileSelectionLoading,
                ) {
                    onBack?.invoke()
                }
                ProfileSelectionScreen(
                    onProfileSelected = { profile, tapCenter ->
                        if (
                            !profileSelectionLoading &&
                            (autoSkipProfileSelection || profile.profileIndex != ProfileRepository.state.value.activeProfile?.profileIndex)
                        ) {
                            beginProfileTransition(profile, tapCenter)
                            skipProfileSelectionEnterAnimation = false
                            selectProfile(
                                profile = profile,
                                sync = authState is AuthState.Authenticated,
                            )
                            gateScreen = AppGateScreen.Main.name
                            if (!renderMainContent) {
                                onActivate?.invoke(AppScreenTab.Home)
                            }
                        }
                    },
                    onEditProfile = { profile ->
                        editingProfile = profile
                        skipProfileSelectionEnterAnimation = false
                        profileEditReturnScreen = AppGateScreen.ProfileSelection.name
                        gateScreen = AppGateScreen.ProfileEdit.name
                    },
                    onAddProfile = {
                        editingProfile = null
                        skipProfileSelectionEnterAnimation = false
                        profileEditReturnScreen = AppGateScreen.ProfileSelection.name
                        gateScreen = AppGateScreen.ProfileEdit.name
                    },
                    onSignInWithAccount = if ((authState as? AuthState.Authenticated)?.isAnonymous == true) {
                        { gateScope.launch { AuthRepository.signOut() } }
                    } else {
                        null
                    },
                    interactionEnabled = !profileSelectionLoading,
                    onBack = onBack,
                    activeProfileIndex = if (autoSkipProfileSelection) null else profileState.activeProfile?.profileIndex,
                    // Also gated on `gateScreen`, not just `profileSelectionTransitionActive`:
                    // `onExitFinished` below resets that flag to false the moment
                    // AppLoadingContent's exit glide finishes, which — with no other guard — made
                    // this flip back to true and fade the profile grid back in for a frame while
                    // the overlay Box around all of this was *also* fading out underneath it
                    // (profileOverlayVisible, driven by profileSelectionLoading), reading as a
                    // flicker of the profile-select screen right before the tab bar appeared.
                    // Once `gateScreen` has moved on to Main there's no legitimate reason for this
                    // content to ever reappear, regardless of what the transition flag does next.
                    contentVisible = !profileSelectionTransitionActive &&
                        gateScreen != AppGateScreen.Main.name,
                    modifier = Modifier.fillMaxSize(),
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = profileSelectionTransitionActive,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AppLoadingContent(
                        modifier = Modifier.fillMaxSize(),
                        profile = transitioningProfile,
                        entryOrigin = profileTransitionOrigin,
                        exitTowardProfileTab = profileTransitionExiting,
                        onExitFinished = {
                            profileSelectionLoading = false
                            profileSelectionTransitionActive = false
                        },
                    )
                }
            }
        }
    }
}
