package com.nuvio.app.features.debrid

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException

internal data class TorboxSeasonPackEpisode(
    val stream: StreamItem,
    val season: Int?,
    val episode: Int?,
)

/**
 * When a season download is about to start and several episodes share one TorBox torrent,
 * ask for every file link in one `requestdl/batch` call. Episodes that do not get a link keep
 * their original stream so the per-file resolve still runs. Links are only requested here, at
 * enqueue time, because TorBox download links expire a few hours after they are issued.
 */
internal suspend fun attachTorboxSeasonPackLinks(
    episodes: List<TorboxSeasonPackEpisode>,
): List<StreamItem> {
    val original = episodes.map { it.stream }
    if (episodes.size < 2) return original
    val apiKey = try {
        val settings = DebridSettingsRepository.snapshot()
        val providerId = settings.activeResolverProviderId
        val key = providerId?.let(settings::apiKeyFor)?.trim().orEmpty()
        if (providerId != DebridProviders.TORBOX_ID || key.isBlank() || !settings.canResolvePlayableLinks) {
            return original
        }
        key
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        return original
    }
    return try {
        resolveTorboxSeasonPackEpisodes(episodes, apiKey)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        InAppLogger.warn("TorBox", "Season pack link resolve failed (${torboxSafeErrorMessage(error, apiKey)}).")
        original
    }
}

internal suspend fun resolveTorboxSeasonPackEpisodes(
    episodes: List<TorboxSeasonPackEpisode>,
    apiKey: String,
): List<StreamItem> {
    val resolved = episodes.map { it.stream }.toMutableList()
    val groups = episodes.indices.groupBy { index -> episodes[index].stream.packGroupKey() }
    for ((key, indexes) in groups) {
        if (key == null || indexes.size < 2) continue
        val updated = resolveSharedTorrent(apiKey, indexes.map { episodes[it] })
        indexes.forEachIndexed { position, index ->
            updated[position]?.let { resolved[index] = it }
        }
    }
    return resolved
}

private suspend fun resolveSharedTorrent(
    apiKey: String,
    episodes: List<TorboxSeasonPackEpisode>,
): List<StreamItem?> {
    val ready = episodes.mapNotNull { episode ->
        episode.stream.packMagnet()?.let { magnet -> episode to magnet }
    }
    if (ready.size < 2) return List(episodes.size) { null }
    val create = try {
        TorboxApiClient.createTorrent(apiKey = apiKey, magnet = ready.first().second)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        InAppLogger.warn("TorBox", "Season pack link resolve failed (${torboxSafeErrorMessage(error, apiKey)}).")
        return List(episodes.size) { null }
    }
    val torrentId = create.body?.takeIf { it.success != false }?.data?.resolvedTorrentId()
        ?: return List(episodes.size) { null }
    val torrent = try {
        TorboxApiClient.getTorrent(apiKey = apiKey, id = torrentId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        InAppLogger.warn("TorBox", "Season pack link resolve failed (${torboxSafeErrorMessage(error, apiKey)}).")
        return List(episodes.size) { null }
    }
    if (!torrent.isSuccessful) return List(episodes.size) { null }
    val files = torrent.body?.data?.files.orEmpty()
    val selector = TorboxFileSelector()
    val chosen = ready.mapNotNull { (episode, _) ->
        val resolve = episode.stream.packResolve(episode.season, episode.episode)
        val file = selector.selectFile(files, resolve, episode.season, episode.episode) ?: return@mapNotNull null
        val fileId = file.id ?: return@mapNotNull null
        ChosenPackFile(episode, file, fileId)
    }
    if (chosen.size < 2) return List(episodes.size) { null }
    val links = torboxDownloadLinksForFiles(
        apiKey = apiKey,
        torrentId = torrentId,
        fileIds = chosen.map { it.fileId },
    )
    return episodes.map { episode ->
        val hit = chosen.firstOrNull { it.episode === episode } ?: return@map null
        val url = links[hit.fileId]?.takeIf { it.isNotBlank() } ?: return@map null
        episode.stream.withResolvedDebridUrl(
            DirectDebridResolveResult.Success(
                url = url,
                filename = hit.file.displayName().takeIf { it.isNotBlank() }
                    ?: episode.stream.behaviorHints.filename?.takeIf { it.isNotBlank() },
                videoSize = hit.file.size ?: episode.stream.behaviorHints.videoSize,
            ),
        )
    }
}

private data class ChosenPackFile(
    val episode: TorboxSeasonPackEpisode,
    val file: TorboxTorrentFileDto,
    val fileId: Int,
)

private fun StreamItem.packGroupKey(): String? {
    if (playableDirectUrl != null) return null
    if (debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) return null
    val hash = p2pInfoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (needsLocalDebridResolve) return hash
    val service = DebridProviders.byId(clientResolve?.service)?.id
    if (isDirectDebridStream && service == DebridProviders.TORBOX_ID) return hash
    return null
}

private fun StreamItem.packMagnet(): String? {
    if (needsLocalDebridResolve) return DebridMagnetBuilder.fromStream(this)
    val resolve = clientResolve ?: return null
    return resolve.magnetUri?.takeIf { it.isNotBlank() } ?: buildMagnetUri(resolve)
}

private fun StreamItem.packResolve(season: Int?, episode: Int?): StreamClientResolve {
    clientResolve?.let { resolve ->
        return resolve.copy(
            season = resolve.season ?: season,
            episode = resolve.episode ?: episode,
            filename = resolve.filename ?: behaviorHints.filename,
        )
    }
    return StreamClientResolve(
        type = "torrent",
        infoHash = infoHash,
        fileIdx = fileIdx,
        magnetUri = torrentMagnetUri,
        sources = sources,
        filename = behaviorHints.filename,
        season = season,
        episode = episode,
        service = DebridProviders.TORBOX_ID,
        isCached = debridCacheStatus?.state == StreamDebridCacheState.CACHED,
    )
}
