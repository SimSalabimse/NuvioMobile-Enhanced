package com.nuvio.app.core.perf

import com.nuvio.app.core.network.NetworkCondition

internal enum class PosterImageCacheFreshness {
    Fresh,
    Expired,
    NoCache,
}

internal enum class PosterImageNetworkState {
    Online,
    Offline,
    RequestFailed,
}

internal data class PosterImageCacheDecision(
    val useCachedBytes: Boolean,
    val makeNetworkRequest: Boolean,
) {
    val showExistingPlaceholder: Boolean
        get() = !useCachedBytes && !makeNetworkRequest
}

internal data class PosterOfflineImageIndexRow(
    val key: String,
    val sizeBytes: Long,
    val freshUntilMillis: Long,
    val lastAccessMillis: Long,
    val contentType: String?,
)

internal object PosterOfflineImageCachePolicy {
    const val MaxBytes: Long = 250L * 1024L * 1024L
    const val MaxCachedImageBytes: Int = 8 * 1024 * 1024
    const val MinimumImageBytes: Long = 32L

    fun decide(
        cache: PosterImageCacheFreshness,
        network: PosterImageNetworkState,
    ): PosterImageCacheDecision {
        val cached = cache != PosterImageCacheFreshness.NoCache
        return when (network) {
            PosterImageNetworkState.Online -> when (cache) {
                PosterImageCacheFreshness.Fresh -> PosterImageCacheDecision(
                    useCachedBytes = true,
                    makeNetworkRequest = false,
                )
                PosterImageCacheFreshness.Expired,
                PosterImageCacheFreshness.NoCache,
                -> PosterImageCacheDecision(
                    useCachedBytes = false,
                    makeNetworkRequest = true,
                )
            }
            PosterImageNetworkState.Offline,
            PosterImageNetworkState.RequestFailed,
            -> PosterImageCacheDecision(
                useCachedBytes = cached,
                makeNetworkRequest = false,
            )
        }
    }

    fun fromCondition(condition: NetworkCondition): PosterImageNetworkState =
        if (condition == NetworkCondition.NoInternet) {
            PosterImageNetworkState.Offline
        } else {
            PosterImageNetworkState.Online
        }

    fun freshUntilMillis(cacheControlHeader: String?, nowMillis: Long): Long {
        val header = cacheControlHeader?.lowercase().orEmpty()
        if (header.isEmpty() || "no-store" in header || "no-cache" in header) return nowMillis
        val seconds = MAX_AGE.find(header)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return nowMillis
        val cappedSeconds = seconds.coerceAtMost(MaxFreshnessSeconds)
        return nowMillis + cappedSeconds * 1_000L
    }

    fun freshness(freshUntilMillis: Long, nowMillis: Long): PosterImageCacheFreshness =
        if (nowMillis < freshUntilMillis) PosterImageCacheFreshness.Fresh else PosterImageCacheFreshness.Expired

    fun isUsableCachedImage(sizeBytes: Long): Boolean =
        sizeBytes in MinimumImageBytes..MaxCachedImageBytes.toLong()

    fun acceptsResponseContentType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type.isEmpty()) return true
        if (type.startsWith("image/")) return true
        return type == "application/octet-stream" || type == "binary/octet-stream"
    }

    fun parseIndex(text: String): List<PosterOfflineImageIndexRow> =
        text.split('\n').mapNotNull(::parseIndexLine)

    fun indexText(rows: Collection<PosterOfflineImageIndexRow>): String =
        rows.joinToString("\n") { row ->
            "${row.key}\t${row.sizeBytes}\t${row.freshUntilMillis}\t${row.lastAccessMillis}\t${row.contentType.orEmpty()}"
        }

    private fun parseIndexLine(line: String): PosterOfflineImageIndexRow? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size != 5) return null
        val key = parts[0]
        if (key.length != 64 || key.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val sizeBytes = parts[1].toLongOrNull() ?: return null
        val freshUntilMillis = parts[2].toLongOrNull() ?: return null
        val lastAccessMillis = parts[3].toLongOrNull() ?: return null
        if (!isUsableCachedImage(sizeBytes)) return null
        val contentType = parts[4].trim().takeIf { it.isNotEmpty() }
        return PosterOfflineImageIndexRow(
            key = key,
            sizeBytes = sizeBytes,
            freshUntilMillis = freshUntilMillis,
            lastAccessMillis = lastAccessMillis,
            contentType = contentType,
        )
    }

    private val MAX_AGE = Regex("""max-age\s*=\s*(\d+)""")
    private const val MaxFreshnessSeconds = 366L * 24L * 60L * 60L
}
