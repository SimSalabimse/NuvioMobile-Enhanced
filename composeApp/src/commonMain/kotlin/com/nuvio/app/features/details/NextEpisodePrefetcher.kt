package com.nuvio.app.features.details

import com.nuvio.app.features.settings.PerformanceSettingsRepository
import com.nuvio.app.features.streams.StreamsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Next episode prefetcher for faster "Play Next" experience.
 * Item 8: Prefetch next episode link on details screen.
 * 
 * Implemented by Simsalabim for Nuvio Mobile performance.
 */
object NextEpisodePrefetcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var prefetchJob: Job? = null
    
    /**
     * Prefetch streams for the next episode when viewing details.
     */
    fun prefetchNextEpisode(
        seriesId: String,
        currentSeason: Int,
        currentEpisode: Int,
    ) {
        PerformanceSettingsRepository.ensureLoaded()
        val prefetchEnabled = PerformanceSettingsRepository.nextEpisodePrefetchEnabled.value
        
        if (!prefetchEnabled) return
        
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            try {
                // Calculate next episode
                val nextSeason = currentSeason
                val nextEpisode = currentEpisode + 1
                
                // Prefetch streams for next episode in background
                // This warms up the cache so "Play Next" is instant
                StreamsRepository.prefetchStreams(
                    type = "series",
                    id = seriesId,
                    season = nextSeason,
                    episode = nextEpisode,
                )
            } catch (e: Exception) {
                // Silent failure - prefetch is optimization, not critical
            }
        }
    }
    
    /**
     * Cancel any ongoing prefetch.
     */
    fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }
}
