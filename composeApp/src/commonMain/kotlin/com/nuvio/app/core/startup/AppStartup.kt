package com.nuvio.app.core.startup

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.PerformanceSettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * App startup coordinator with parallel initialization support.
 * Item 3: Parallel startup - loads non-dependent services concurrently.
 * 
 * Implemented by Simsalabim for Nuvio Mobile performance.
 */
object AppStartup {
    
    suspend fun initialize() {
        InAppLogger.info("App", "Application started")
        
        PerformanceSettingsRepository.ensureLoaded()
        val parallelEnabled = PerformanceSettingsRepository.parallelStartupEnabled.value
        
        if (parallelEnabled) {
            // Item 3: Parallel startup - initialize services concurrently
            initializeParallel()
        } else {
            // Sequential initialization (original behavior)
            AuthRepository.initialize()
        }
    }
    
    suspend fun startServices() {
        PerformanceSettingsRepository.ensureLoaded()
        val parallelEnabled = PerformanceSettingsRepository.parallelStartupEnabled.value
        
        InAppLogger.info("App/Runtime", "Starting network/profile/avatar services")
        
        if (parallelEnabled) {
            // Item 3: Parallel startup - start services concurrently
            startServicesParallel()
        } else {
            // Sequential service start (original behavior)
            startServicesSequential()
        }
    }
    
    private suspend fun initializeParallel() = coroutineScope {
        InAppLogger.info("App/Startup", "Parallel initialization enabled")
        // Auth must complete before other services can use it
        AuthRepository.initialize()
    }
    
    private suspend fun startServicesParallel() = coroutineScope {
        // These services don't depend on each other, so start them in parallel
        awaitAll(
            async { NetworkStatusRepository.ensureStarted() },
            async { MemberAccessRepository.ensureStarted() },
            async { ProfileRepository.loadCachedProfiles() },
            async { AvatarRepository.fetchAvatars() },
        )
    }
    
    private fun startServicesSequential() {
        NetworkStatusRepository.ensureStarted()
        MemberAccessRepository.ensureStarted()
        ProfileRepository.loadCachedProfiles()
        AvatarRepository.fetchAvatars()
    }
}
