package com.nuvio.app.core.startup

import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.FirstFrameDecision
import com.nuvio.app.features.profiles.FirstFrameDestination
import com.nuvio.app.features.profiles.FirstFrameInput
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.RememberedPinKind
import com.nuvio.app.features.profiles.StartupNetworkOutcome
import com.nuvio.app.features.profiles.decideFirstFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal object SynchronousLaunch {
    private val lock = SynchronizedObject()
    private var prepared = false
    private var decision = FirstFrameDecision(
        destination = FirstFrameDestination.SignIn,
        pickerWasFirstFrame = false,
    )

    fun prepare(): FirstFrameDecision = synchronized(lock) {
        if (prepared) return@synchronized decision
        val cache = ProfileRepository.applyCacheSynchronously()
        AvatarRepository.hydrateCachedCatalog()
        val signedIn = !AuthStorage.loadAnonymousUserId().isNullOrBlank() || cache.cachePresent
        val frame = decideFirstFrame(
            FirstFrameInput(
                signedIn = signedIn,
                profileCount = cache.profileCount,
                rememberOn = cache.rememberOn && cache.hasEverSelectedProfile,
                rememberedPin = cache.rememberedPin,
                cachePresent = cache.cachePresent,
                network = StartupNetworkOutcome.Offline,
            ),
        )
        if (frame.destination == FirstFrameDestination.Home) {
            val index = when {
                cache.rememberOn &&
                    cache.hasEverSelectedProfile &&
                    cache.rememberedPin == RememberedPinKind.NoPin -> cache.activeProfileIndex
                cache.profileCount == 1 && cache.rememberedPin == RememberedPinKind.NoPin ->
                    cache.activeProfileIndex
                else -> null
            }
            if (index != null) {
                HomeRepository.preloadSnapshot()
                ProfileRepository.selectProfile(index)
            }
        }
        decision = frame
        prepared = true
        decision
    }
}
