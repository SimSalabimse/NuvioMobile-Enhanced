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
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.runCatchingUnlessCancelled
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import com.nuvio.app.features.streams.toPluginProviderGroups
import com.nuvio.app.features.streams.toStreamItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** One source of one episode: an installed addon, a plugin provider group, or a media server. */
internal class SeasonCatalogSource(
    val addonId: String,
    val addonName: String,
    val preferred: Boolean,
    val load: suspend () -> List<StreamItem>,
)

/** Streams from the sources that have answered so far, in the stream screen's group order. */
internal data class SeasonCatalogSnapshot(
    val streams: List<StreamItem>,
    val pendingSources: Int,
) {
    val complete: Boolean get() = pendingSources == 0
}

/**
 * Loads the same stream sources the episode stream list uses, without touching that screen's state.
 *
 * The season sheet used to await every addon, plugin scraper and server before it showed one row,
 * so the slowest source (the iOS addon client allows 60 s) set the wait. It then repeated that full
 * fan-out for every other episode just to find the one addon the user picked.
 * [stream] now reports each source as it answers, and [loadFrom] asks only the picked source.
 */
internal object SeasonStreamCatalog {
    fun stream(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): Flow<SeasonCatalogSnapshot> = channelFlow {
        val embedded = MetaDetailsRepository.findEmbeddedStreams(videoId)
        if (embedded.isNotEmpty()) {
            send(SeasonCatalogSnapshot(embedded, pendingSources = 0))
            return@channelFlow
        }
        val sources = sources(type, videoId, season, episode)
        if (sources.isEmpty()) {
            send(SeasonCatalogSnapshot(emptyList(), pendingSources = 0))
            return@channelFlow
        }
        val installedOrder = installedAddonOrder(type, videoId)
        val preferredIds = sources.filter { it.preferred }.mapTo(mutableSetOf()) { it.addonId }
        val done = Channel<Pair<Int, List<StreamItem>>>(capacity = Channel.UNLIMITED)
        sources.forEachIndexed { index, source ->
            launch {
                val streams = runCatchingUnlessCancelled { source.load() }.getOrElse { emptyList() }
                done.send(index to streams)
            }
        }
        val answered = arrayOfNulls<List<StreamItem>>(sources.size)
        var pending = sources.size
        send(SeasonCatalogSnapshot(emptyList(), pendingSources = pending))
        while (pending > 0) {
            val (index, streams) = done.receive()
            answered[index] = streams
            pending -= 1
            send(
                SeasonCatalogSnapshot(
                    streams = orderedStreams(sources, answered, installedOrder, preferredIds),
                    pendingSources = pending,
                ),
            )
        }
    }

    /** Every source, all answered. */
    suspend fun load(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> {
        var last = SeasonCatalogSnapshot(emptyList(), pendingSources = 0)
        stream(type, videoId, season, episode).collect { last = it }
        return last.streams
    }

    /**
     * Only the source with [addonId]. A season match never needs the other sources:
     * [matchSeasonStream] keeps a hit only from the picked addon or its bingeGroup.
     * An id that is not a current source falls back to every source.
     */
    suspend fun loadFrom(
        addonId: String,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> {
        val embedded = MetaDetailsRepository.findEmbeddedStreams(videoId)
        if (embedded.isNotEmpty()) return embedded
        val picked = sources(type, videoId, season, episode).filter { it.addonId == addonId }
        if (picked.isEmpty()) return load(type, videoId, season, episode)
        return coroutineScope {
            picked.map { source ->
                async { runCatchingUnlessCancelled { source.load() }.getOrElse { emptyList() } }
            }.awaitAll().flatten()
        }
    }

    private fun installedAddonOrder(type: String, videoId: String): List<String> {
        if (ServerStreams.isNativeRequest(videoId)) return emptyList()
        return AddonRepository.uiState.value.addons.enabledAddons().mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            if (!manifest.supportsStream(type, videoId)) return@mapNotNull null
            addon.displayTitle.ifBlank { manifest.name }
        }
    }

    private fun orderedStreams(
        sources: List<SeasonCatalogSource>,
        answered: Array<List<StreamItem>?>,
        installedOrder: List<String>,
        preferredIds: Set<String>,
    ): List<StreamItem> {
        val groups = sources.mapIndexedNotNull { index, source ->
            val streams = answered[index] ?: return@mapIndexedNotNull null
            AddonStreamGroup(addonName = source.addonName, addonId = source.addonId, streams = streams)
        }
        return StreamAutoPlaySelector.orderAddonStreams(groups, installedOrder, preferredIds)
            .flatMap { it.streams }
    }

    private suspend fun sources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<SeasonCatalogSource> {
        val sources = mutableListOf<SeasonCatalogSource>()
        if (!ServerStreams.isNativeRequest(videoId)) {
            AddonRepository.uiState.value.addons.enabledAddons().forEach { addon ->
                val manifest = addon.manifest ?: return@forEach
                if (!manifest.supportsStream(type, videoId)) return@forEach
                val addonName = addon.displayTitle.ifBlank { manifest.name }
                val addonId = addon.streamAddonInstanceId(manifest.id)
                sources += SeasonCatalogSource(addonId, addonName, preferred = false) {
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
                    sources += SeasonCatalogSource(group.addonId, group.addonName, preferred = false) {
                        coroutineScope {
                            group.scrapers.map { scraper ->
                                async {
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
                            }.awaitAll().flatten()
                        }
                    }
                }
            }
        }
        ServerStreams.sources(type, videoId, season, episode).forEach { source ->
            sources += SeasonCatalogSource(source.addonId, source.addonName, source.preferred) {
                source.load().streams
            }
        }
        return sources
    }
}
