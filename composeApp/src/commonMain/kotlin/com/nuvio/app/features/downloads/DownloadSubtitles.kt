package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleAddonRequest
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.player.computeLocalVideoHash
import com.nuvio.app.features.player.loadAddonSubtitles
import com.nuvio.app.features.player.subtitleExtrasForDownloadedFile
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object DownloadSubtitles {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(item: DownloadItem, localVideoUri: String): Unit = withContext(Dispatchers.Default) {
        val storage = DownloadSubtitleStorage(localVideoUri)
        val manifest = readManifest(storage)
        val saved = manifest.tracks.filter { storage.localFileUri(it.fileName) != null }.toMutableList()
        val requests = requestsForDownloadedFile(item, localVideoUri)
        // A finished manifest from before the file hash is not the last word: ask again
        // with the hash so a matching release can be saved in front of the old tracks.
        val usingFileHash = requests != item.subtitleRequests
        if (manifest.complete && saved.size == manifest.tracks.size && !usingFileHash) return@withContext
        var nextFileIndex = (saved.maxOfOrNull { it.fileName.substringBefore('.').toIntOrNull() ?: -1 } ?: -1) + 1
        val addonSubtitles = loadAddonSubtitles(requests).map {
            StreamSubtitle(url = it.url, language = it.language, name = it.display)
        }
        val pending = (item.sourceSubtitles + addonSubtitles)
            .distinctBy { it.url }
            .filter { subtitle ->
                (subtitle.url.startsWith("https://", true) || subtitle.url.startsWith("http://", true)) &&
                    saved.none { it.sourceUrl == subtitle.url }
            }
        for (batch in pending.chunked(4)) {
            val fetched = coroutineScope {
                batch.map { subtitle ->
                    async {
                        val body = try {
                            withTimeoutOrNull(15_000L) {
                                httpGetTextWithHeaders(subtitle.url, subtitle.headers.orEmpty())
                            }?.takeIf { it.isNotBlank() && PlayerSubtitleCueParser.parse(it, subtitle.url).isNotEmpty() }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            null
                        }
                        subtitle to body
                    }
                }.awaitAll()
            }
            for ((subtitle, body) in fetched) {
                currentCoroutineContext().ensureActive()
                if (body == null) continue
                val extension = PlayerSubtitleCueParser.fileExtension(body, subtitle.url)
                val fileName = "${nextFileIndex++}.$extension"
                try {
                    storage.write(fileName, body)
                    val downloaded = DownloadedSubtitle(subtitle.url, fileName, subtitle.language, subtitle.name)
                    if (usingFileHash) saved.add(0, downloaded) else saved += downloaded
                    storage.write("manifest.json", json.encodeToString(SubtitleManifest(tracks = saved.toList())))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
            }
        }
        currentCoroutineContext().ensureActive()
        runCatching {
            storage.write("manifest.json", json.encodeToString(SubtitleManifest(complete = true, tracks = saved)))
        }
        Unit
    }

    /**
     * Asks add-ons about the downloaded file's own hash. The requests stored at enqueue
     * time only know the stream hint, which can name a different release. A file that
     * cannot be hashed keeps those requests.
     */
    private suspend fun requestsForDownloadedFile(
        item: DownloadItem,
        localVideoUri: String,
    ): List<SubtitleAddonRequest> {
        val hashed = computeLocalVideoHash(localVideoUri) ?: return item.subtitleRequests
        val extras = subtitleExtrasForDownloadedFile(
            filename = item.sourceFilename,
            hintedHash = null,
            hintedSize = null,
            fileHash = hashed.first,
            fileSize = hashed.second,
        )
        return addonSubtitleRequests(
            type = item.contentType,
            videoId = item.videoId,
            extras = extras,
        ).ifEmpty { item.subtitleRequests }
    }

    fun localSubtitles(localVideoUri: String): List<StreamSubtitle> {
        if (!localVideoUri.startsWith("file:")) return emptyList()
        val storage = DownloadSubtitleStorage(localVideoUri)
        return readManifest(storage).tracks.mapNotNull { track ->
            val uri = storage.localFileUri(track.fileName) ?: return@mapNotNull null
            StreamSubtitle(url = uri, language = track.language, name = track.name)
        }
    }

    private fun readManifest(storage: DownloadSubtitleStorage): SubtitleManifest =
        runCatching {
            storage.read("manifest.json")?.let { json.decodeFromString<SubtitleManifest>(it) }
        }.getOrNull() ?: SubtitleManifest()
}

@Serializable
private data class SubtitleManifest(
    val complete: Boolean = false,
    val tracks: List<DownloadedSubtitle> = emptyList(),
)

@Serializable
private data class DownloadedSubtitle(
    val sourceUrl: String,
    val fileName: String,
    val language: String,
    val name: String? = null,
)

internal expect class DownloadSubtitleStorage(localVideoUri: String) {
    fun read(fileName: String): String?
    fun write(fileName: String, text: String)
    fun localFileUri(fileName: String): String?
    fun remove()
}
