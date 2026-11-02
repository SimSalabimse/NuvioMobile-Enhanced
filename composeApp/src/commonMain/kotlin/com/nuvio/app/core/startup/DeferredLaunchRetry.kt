package com.nuvio.app.core.startup

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

internal object LaunchRefreshController {
    private val lock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = TimeSource.Monotonic.markNow()
    private var state = LaunchRefreshState()

    fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds

    fun noteAttemptFailed() {
        val start = synchronized(lock) {
            state = noteFailedLaunchAttempt(state)
            if (!NetworkStatusRepository.uiState.value.isOnline) return@synchronized false
            val reduced = reduceConnectivityRegained(state, nowMs())
            state = reduced.first
            reduced.second
        }
        if (start) launchRecovery()
    }

    fun onConnectivityRegained() {
        val start = synchronized(lock) {
            val reduced = reduceConnectivityRegained(state, nowMs())
            state = reduced.first
            reduced.second
        }
        if (start) launchRecovery()
    }

    private fun launchRecovery() {
        scope.launch {
            val ok = runCatching { retryDeferredLaunchNetwork() }.getOrDefault(false)
            synchronized(lock) {
                state = finishLaunchRefresh(state, ok)
            }
        }
    }
}

internal suspend fun retryDeferredLaunchNetwork(): Boolean {
    AddonRepository.refreshUnresolvedManifests()
    AddonRepository.awaitManifestRefreshSettled()
    AddonRepository.refreshUnresolvedManifests()
    AddonRepository.awaitManifestRefreshSettled()
    val enabled = AddonRepository.uiState.value.addons.enabledAddons()
    if (enabled.any { it.manifest != null }) {
        HomeRepository.refresh(enabled, force = true)
    }
    withTimeoutOrNull(DeferredLaunchNetworkTimeoutMs) {
        AvatarRepository.fetchAvatars()
    }
    val auth = AuthRepository.state.value as? AuthState.Authenticated
    if (auth != null && !auth.isAnonymous) {
        val profileId = ProfileRepository.state.value.activeProfile?.profileIndex
            ?: ProfileRepository.activeProfileId
        SyncManager.pullAllForProfile(profileId)
    }
    return enabled.all { it.manifest != null }
}
