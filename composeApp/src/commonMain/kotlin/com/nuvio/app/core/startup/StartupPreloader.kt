package com.nuvio.app.core.startup

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.perf.PerformanceMetrics
import com.nuvio.app.core.perf.PerformanceOptimization
import com.nuvio.app.core.perf.PerformanceSettings
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.livetv.LiveTvRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

private const val LogTag = "App/StartupPreload"
private const val SlowPreloadWarningMs = 6_000L

private class PreloadStep(val name: String, val run: () -> Unit)

/**
 * Library, watched and watch progress all register and load the shared tracking providers, so
 * they stay in one ordered lane; every other step only reads its own storage.
 */
private val trackingLane = listOf(
    PreloadStep("library") { LibraryRepository.ensureLoaded() },
    PreloadStep("watched") { WatchedRepository.ensureLoaded() },
    PreloadStep("watchProgress") { WatchProgressRepository.ensureLoaded() },
)

private val independentSteps = listOf(
    PreloadStep("continueWatching") { ContinueWatchingPreferencesRepository.ensureLoaded() },
    PreloadStep("playerSettings") { PlayerSettingsRepository.ensureLoaded() },
    PreloadStep("p2pSettings") { P2pSettingsRepository.ensureLoaded() },
    PreloadStep("servers") { ServerRepository.ensureLoaded() },
    PreloadStep("metaScreen") { MetaScreenSettingsRepository.ensureLoaded() },
    PreloadStep("releaseNotifications") { EpisodeReleaseNotificationsRepository.ensureLoaded() },
    PreloadStep("liveTv") { LiveTvRepository.ensureLoaded() },
    PreloadStep("homeSnapshot") { HomeRepository.preloadSnapshot() },
)

object StartupPreloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Deferred<Unit>? = null

    val isComplete: Boolean
        get() = job?.isCompleted == true

    suspend fun awaitReady() {
        val active = job ?: start()
        if (withTimeoutOrNull(SlowPreloadWarningMs) { active.await() } == null) {
            InAppLogger.warn(LogTag, "preload slower than ${SlowPreloadWarningMs}ms, still waiting")
            active.await()
        }
    }

    private fun start(): Deferred<Unit> = scope.async {
        val started = TimeSource.Monotonic.markNow()
        val parallel = PerformanceSettings.isEnabled(PerformanceOptimization.ParallelStartup)
        step("profiles") { ProfileRepository.loadCachedProfiles() }
        withContext(Dispatchers.IO) {
            if (parallel) {
                coroutineScope {
                    launch { trackingLane.forEach(::runStep) }
                    independentSteps.forEach { preload -> launch { runStep(preload) } }
                }
            } else {
                (trackingLane + independentSteps).forEach(::runStep)
            }
        }
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        PerformanceMetrics.recordStartupPreload(elapsedMs, parallel)
        InAppLogger.info(LogTag, "preloaded in ${elapsedMs}ms parallel=$parallel")
    }.also { job = it }

    private fun runStep(preload: PreloadStep) = step(preload.name, preload.run)

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            InAppLogger.warn(LogTag, "$name failed: ${error::class.simpleName}: ${error.message}")
        }
    }
}
