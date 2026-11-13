package com.nuvio.app.core.perf

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.network.httpHeaders
import coil3.network.httpMethod
import coil3.request.Options
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem

internal class PosterImageLeftAsPlaceholder : Exception()

internal class PosterOfflineImageFetcher(
    private val url: String,
    private val cacheKey: String,
    private val options: Options,
    private val cache: PosterOfflineImageDiskCache,
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val now = nowMillis()
        val hit = if (options.diskCachePolicy.readEnabled) cache.open(cacheKey, now) else null
        val freshness = hit?.freshness ?: PosterImageCacheFreshness.NoCache
        val kind = hit?.kind ?: PosterImageEntryKind.Image
        val network = if (options.networkCachePolicy.readEnabled) {
            PosterOfflineImageCachePolicy.fromCondition(NetworkStatusRepository.uiState.value.condition)
        } else {
            PosterImageNetworkState.Offline
        }
        val decision = PosterOfflineImageCachePolicy.decideEntry(kind, freshness, network)
        if (decision.useCachedBytes && hit?.path != null && !decision.makeNetworkRequest) {
            return@withContext fileResult(hit.path, hit.contentType, DataSource.DISK)
        }
        if (!decision.makeNetworkRequest) {
            throw PosterImageLeftAsPlaceholder()
        }
        val downloaded = download(url, options)
        if (downloaded == null) {
            if (hit?.kind == PosterImageEntryKind.Image && hit.path != null) {
                return@withContext fileResult(hit.path, hit.contentType, DataSource.DISK)
            }
            if (options.diskCachePolicy.writeEnabled) {
                cache.storeMiss(
                    cacheKey,
                    freshUntilMillis = now + PosterOfflineImageCachePolicy.NegativeTtlMs,
                    nowMillis = now,
                )
            }
            throw PosterImageLeftAsPlaceholder()
        }
        if (options.diskCachePolicy.writeEnabled) {
            val freshUntil = PosterOfflineImageCachePolicy.freshUntilMillis(downloaded.cacheControl, now)
            cache.store(
                cacheKey,
                downloaded.bytes,
                freshUntil,
                now,
                downloaded.contentType,
            )
            val stored = cache.open(cacheKey, now)
            if (stored?.path != null) {
                return@withContext fileResult(stored.path, stored.contentType, DataSource.NETWORK)
            }
        }
        SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(downloaded.bytes),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = posterImageMimeType(url, downloaded.contentType),
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val url = data.toString()
            return PosterOfflineImageFetcher(
                url = url,
                cacheKey = options.diskCacheKey ?: url,
                options = options,
                cache = PosterOfflineImageCaches.forContext(options.context),
            )
        }
    }

    private fun fileResult(path: okio.Path, contentType: String?, dataSource: DataSource): SourceFetchResult =
        SourceFetchResult(
            source = ImageSource(
                file = path,
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = posterImageMimeType(url, contentType),
            dataSource = dataSource,
        )
}

private data class PosterImageDownload(
    val bytes: ByteArray,
    val cacheControl: String?,
    val contentType: String?,
)

private suspend fun download(url: String, options: Options): PosterImageDownload? {
    val headers = options.httpHeaders.asMap().mapValues { (_, values) -> values.joinToString(",") }
    val response = try {
        httpRequestRaw(
            method = options.httpMethod.ifBlank { "GET" },
            url = url,
            headers = headers,
            body = "",
            maxResponseBodyBytes = PosterOfflineImageCachePolicy.MaxCachedImageBytes,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    }
    if (response.status !in 200..299) return null
    val contentType = response.headers["content-type"]
    if (!PosterOfflineImageCachePolicy.acceptsResponseContentType(contentType)) return null
    val bytes = response.bodyBytes
    val declaredLength = response.headers["content-length"]?.toLongOrNull()
    if (declaredLength != null && declaredLength > bytes.size) return null
    if (!PosterOfflineImageCachePolicy.isUsableCachedImage(bytes.size.toLong())) return null
    return PosterImageDownload(
        bytes = bytes,
        cacheControl = response.headers["cache-control"],
        contentType = contentType,
    )
}

internal fun posterImageMimeType(url: String, contentType: String?): String? {
    val header = contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
    if (header != null && !header.equals("text/plain", ignoreCase = true)) return header
    val path = url.substringBefore('?').substringBefore('#')
    return when {
        path.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
        path.endsWith(".png", ignoreCase = true) -> "image/png"
        path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        path.endsWith(".webp", ignoreCase = true) -> "image/webp"
        path.endsWith(".gif", ignoreCase = true) -> "image/gif"
        else -> header
    }
}
