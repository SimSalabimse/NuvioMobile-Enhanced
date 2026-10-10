package com.nuvio.app.features.downloads

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.servers.ServerStreams
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.runCatchingUnlessCancelled
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import com.nuvio.app.features.streams.toPluginProviderGroups
import com.nuvio.app.features.streams.toStreamItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Loads the same stream sources the episode stream list uses, without touching that screen's state.
 */
internal object SeasonStreamCatalog {
    suspend fun load(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> = coroutineScope {
        val embedded = MetaDetailsRepository.findEmbeddedStreams(videoId)
        if (embedded.isNotEmpty()) return@coroutineScope embedded

        val loads = mutableListOf<kotlinx.coroutines.Deferred<List<StreamItem>>>()
        if (!ServerStreams.isNativeRequest(videoId)) {
            val installed = AddonRepository.uiState.value.addons.enabledAddons()
            installed.forEach { addon ->
                val manifest = addon.manifest ?: return@forEach
                if (!manifest.supportsStream(type, videoId)) return@forEach
                val addonName = addon.displayTitle.ifBlank { manifest.name }
                val addonId = addon.streamAddonInstanceId(manifest.id)
                loads += async {
                    runCatchingUnlessCancelled {
                        val url = buildAddonResourceUrl(
                            manifestUrl = manifest.transportUrl,
                            resource = "stream",
                            type = type,
                            id = videoId,
                        )
                        StreamParser.parse(
                            payload = fetchAddonResponseText(url),
                            addonName = addonName,
                            addonId = addonId,
                            addonLogo = manifest.logoUrl,
                        )
                    }.getOrElse { emptyList() }
                }
            }
            if (AppFeaturePolicy.pluginsEnabled) {
                PluginRepository.initialize()
                val pluginState = PluginRepository.uiState.value
                val groups = PluginRepository.getEnabledScrapersForType(type).toPluginProviderGroups(
                    repositories = pluginState.repositories,
                    groupByRepository = pluginState.groupStreamsByRepository,
                )
                groups.forEach { group ->
                    group.scrapers.forEach { scraper ->
                        loads += async {
                            PluginRepository.executeScraper(
                                scraper = scraper,
                                tmdbId = pluginContentId(videoId, season, episode),
                                mediaType = type,
                                season = season,
                                episode = episode,
                            ).getOrElse { emptyList() }.map { result ->
                                result.toStreamItem(
                                    scraper = scraper,
                                    addonName = group.addonName,
                                    addonId = group.addonId,
                                )
                            }
                        }
                    }
                }
            }
        }
        loads += async {
            ServerStreams.sources(type, videoId, season, episode).map { source ->
                async { source.load().streams }
            }.awaitAll().flatten()
        }
        loads.awaitAll().flatten()
    }
}
