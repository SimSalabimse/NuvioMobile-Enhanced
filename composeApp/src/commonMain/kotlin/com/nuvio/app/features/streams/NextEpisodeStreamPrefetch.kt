package com.nuvio.app.features.streams

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.perf.PerformanceCounter
import com.nuvio.app.core.perf.PerformanceMetrics
import com.nuvio.app.core.perf.PerformanceOptimization
import com.nuvio.app.core.perf.PerformanceSettings
import com.nuvio.app.core.perf.PlaybackQuietMode
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.prefetchAddonResponseText
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.servers.ServerStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val NextEpisodePrefetchTtlMs = 5L * 60L * 1000L

/** The addon stream request [StreamsRepository] makes; shared so prefetched bodies hit the same key. */
internal fun addonStreamRequestUrl(manifest: AddonManifest, type: String, videoId: String): String =
    buildAddonResourceUrl(
        manifestUrl = manifest.transportUrl,
        resource = "stream",
        type = type,
        id = videoId,
    )

/**
 * Warms the addon stream responses for the episode the details Play button opens, so the streams
 * screen can use them instead of waiting on every addon. Nothing here touches [StreamsRepository]
 * state or the reuse-last-link cache.
 */
internal object NextEpisodeStreamPrefetch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastRequestKey: String? = null

    fun warm(type: String, videoId: String, parentMetaId: String, season: Int?, episode: Int?) {
        if (!PerformanceSettings.isEnabled(PerformanceOptimization.NextEpisodePrefetch)) return
        if (videoId.isBlank() || PlaybackQuietMode.isQuiet()) return
        val requestKey = "$type|$videoId"
        if (requestKey == lastRequestKey) return
        lastRequestKey = requestKey
        scope.launch {
            if (NetworkStatusRepository.uiState.value.condition == NetworkCondition.NoInternet) return@launch
            if (ServerStreams.isNativeRequest(videoId)) return@launch
            if (MetaDetailsRepository.findEmbeddedStreams(videoId).isNotEmpty()) return@launch
            if (hasReusableLink(type, videoId, parentMetaId, season, episode)) return@launch
            val started = AddonRepository.uiState.value.addons.enabledAddons()
                .mapNotNull { addon -> addon.manifest?.takeIf { it.supportsStream(type, videoId) } }
                .map { manifest -> addonStreamRequestUrl(manifest, type, videoId) }
                .distinct()
                .count { url -> prefetchAddonResponseText(url, NextEpisodePrefetchTtlMs) }
            PerformanceMetrics.count(PerformanceCounter.NextEpisodePrefetches, started)
        }
    }

    private fun hasReusableLink(type: String, videoId: String, parentMetaId: String, season: Int?, episode: Int?): Boolean {
        PlayerSettingsRepository.ensureLoaded()
        val settings = PlayerSettingsRepository.uiState.value
        if (!settings.streamReuseLastLinkEnabled) return false
        val key = StreamLinkCacheRepository.contentKey(type, videoId, parentMetaId, season, episode)
        return StreamLinkCacheRepository.getValid(key, settings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L) != null
    }
}
