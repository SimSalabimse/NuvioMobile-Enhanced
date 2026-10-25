package com.nuvio.app.core.startup

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.isIos
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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
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
    private val startLock = SynchronizedObject()
    private var job: Deferred<Unit>? = null

    val isComplete: Boolean
        get() = job?.isCompleted == true && job?.isCancelled != true

    /**
     * Starts the preload if it is not already running. Does not wait.
     * Safe to call again: a finished or in-flight run is left alone, a cancelled one can restart.
     */
    fun start() {
        synchronized(startLock) {
            val existing = job
            if (existing != null && !existing.isCancelled) return
            // iOS must stay off Main. Android still starts on Main and waits behind the splash.
            job = scope.async(startupPreloadDispatcher(isIos)) { runPreload() }
        }
    }

    suspend fun awaitReady() {
        start()
        val active = job ?: return
        if (withTimeoutOrNull(SlowPreloadWarningMs) { active.await() } == null) {
            InAppLogger.warn(LogTag, "preload slower than ${SlowPreloadWarningMs}ms, still waiting")
            active.await()
        }
    }

    private suspend fun runPreload() {
        val started = TimeSource.Monotonic.markNow()
        val parallel = PerformanceSettings.isEnabled(PerformanceOptimization.ParallelStartup)
        step("profiles") { ProfileRepository.loadCachedProfiles() }
        if (isIos) {
            // start() already put this coroutine on IO. Do not hop back to Main.
            runStartupPreloadSteps(
                parallel = parallel,
                tracking = trackingLane.map { preload -> suspend { runStep(preload) } },
                independent = independentSteps.map { preload -> suspend { runStep(preload) } },
            )
        } else {
            withContext(Dispatchers.IO) {
                runStartupPreloadSteps(
                    parallel = parallel,
                    tracking = trackingLane.map { preload -> suspend { runStep(preload) } },
                    independent = independentSteps.map { preload -> suspend { runStep(preload) } },
                )
            }
        }
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        PerformanceMetrics.recordStartupPreload(elapsedMs, parallel)
        InAppLogger.info(LogTag, "preloaded in ${elapsedMs}ms parallel=$parallel")
    }

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

/**
 * Dispatcher for [StartupPreloader.start]. Android stays on Main, matching the splash wait.
 * iOS uses IO so profile and repo disk reads never occupy the main thread.
 */
internal fun startupPreloadDispatcher(isIosPlatform: Boolean): CoroutineDispatcher =
    if (isIosPlatform) Dispatchers.IO else Dispatchers.Main

/**
 * iOS only. [launchReady] must already be true, which is after
 * [com.nuvio.app.core.perf.PerformanceMetrics.recordLaunchReady].
 * Starting earlier touches that object during the intro and the iOS launch clock
 * (first touch of PerformanceMetrics; markProcessStart does not reset it) then
 * includes the 1300 ms intro hold.
 */
internal fun startIosStartupPreloadAfterLaunchReady(
    isIosPlatform: Boolean,
    launchReady: Boolean,
    start: () -> Unit,
) {
    if (!isIosPlatform || !launchReady) return
    start()
}

/**
 * Tracking steps stay in one coroutine. Independent steps each get a coroutine only when [parallel]
 * is on; otherwise every step runs in order on the caller.
 */
internal suspend fun runStartupPreloadSteps(
    parallel: Boolean,
    tracking: List<suspend () -> Unit>,
    independent: List<suspend () -> Unit>,
) {
    if (parallel) {
        coroutineScope {
            launch { tracking.forEach { step -> step() } }
            independent.forEach { step -> launch { step() } }
        }
    } else {
        (tracking + independent).forEach { step -> step() }
    }
}
