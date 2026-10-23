package com.nuvio.app.features.offline

import co.touchlab.kermit.Logger
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.nuvio.app.core.perf.compactArtworkUrl
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.analysis.MediaAnalysisFiles
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toPath
import com.nuvio.app.core.serialization.LenientJson

/**
 * Local copies of the artwork (poster, backdrop, logo, episode still) of downloaded items, so the
 * offline home and Downloads look right without a network. Images live next to the analysis files;
 * a url -> file index is kept in settings. The Coil interceptor swaps a known URL for the local file.
 * Text metadata (title, episode names, season/episode) already lives on DownloadItem itself.
 */
object OfflineArtwork {
    private const val INDEX_KEY = "offline_artwork_index_v1"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val OFFLINE_MAX_WIDTH_PX = 1280
    private val log = Logger.withTag("OfflineArtwork")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val json = LenientJson
    private var index: MutableMap<String, String>? = null

    private fun loadedIndex(): MutableMap<String, String> {
        index?.let { return it }
        val loaded = runCatching {
            DownloadsSettingsStorage.loadString(INDEX_KEY)?.let { json.decodeFromString<Map<String, String>>(it) }
        }.getOrNull().orEmpty().toMutableMap()
        index = loaded
        return loaded
    }

    private fun save(map: Map<String, String>) {
        runCatching { DownloadsSettingsStorage.saveString(INDEX_KEY, json.encodeToString(map)) }
    }

    /** Local file path for a remote image URL, if a copy exists. */
    fun localPathFor(url: String): String? {
        val name = loadedIndex()[url] ?: return null
        return MediaAnalysisFiles.binaryPath(name)
    }

    fun cache(item: DownloadItem) {
        val urls = listOfNotNull(item.poster, item.background, item.logo, item.episodeThumbnail)
            .filter { it.startsWith("http") }
            .distinct()
        if (urls.isEmpty()) return
        scope.launch { urls.forEach { fetch(it) } }
    }

    fun cacheAll(items: List<DownloadItem>) = items.forEach(::cache)

    private suspend fun fetch(url: String) {
        if (loadedIndex().containsKey(url)) return
        val name = "art_" + url.hashCode().toUInt().toString(16) + "_" + url.length + ".img"
        val bytes = runCatching {
            val source = compactArtworkUrl(url, OFFLINE_MAX_WIDTH_PX)
            val response = httpRequestRaw("GET", source, emptyMap(), "", maxResponseBodyBytes = MAX_BYTES)
            if (response.status in 200..299) response.bodyBytes else null
        }.onFailure { log.w { "fetch failed: ${it.message}" } }.getOrNull() ?: return
        if (bytes.size < 64) return
        if (!MediaAnalysisFiles.writeBytes(name, bytes)) return
        mutex.withLock {
            val map = loadedIndex()
            map[url] = name
            save(map)
        }
    }
}

class OfflineArtworkInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        if (!url.startsWith("http")) return chain.proceed()
        val path = OfflineArtwork.localPathFor(url) ?: return chain.proceed()
        val local = chain.request.newBuilder().data(path.toPath()).build()
        return chain.withRequest(local).proceed()
    }
}
