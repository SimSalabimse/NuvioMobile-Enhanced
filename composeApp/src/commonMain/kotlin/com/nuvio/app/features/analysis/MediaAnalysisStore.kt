package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.DownloadItem
import kotlinx.serialization.json.Json

/** Raw per-key text files (iOS: Application Support/NuvioAnalysis). Android keeps nothing yet. */
internal expect object MediaAnalysisFiles {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun list(prefix: String): List<String>
}

/**
 * One file per episode (or movie), named from the show id and season/episode so the other episodes
 * of a season are a prefix listing away. Kept after the download is deleted (fingerprints and
 * segments only, see [forgetFileData]).
 */
internal object MediaAnalysisStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cache = mutableMapOf<String, MediaAnalysisResult?>()

    fun keyFor(parentMetaId: String, season: Int?, episode: Int?): String {
        val show = sanitize(parentMetaId.trim())
        return if (season != null && episode != null) "${show}__s${season}e$episode" else "${show}__movie"
    }

    fun keyFor(item: DownloadItem): String = keyFor(item.parentMetaId, item.seasonNumber, item.episodeNumber)

    fun load(key: String): MediaAnalysisResult? {
        if (cache.containsKey(key)) return cache[key]
        val result = MediaAnalysisFiles.read(key)?.let { text ->
            runCatching { json.decodeFromString(MediaAnalysisResult.serializer(), text) }.getOrNull()
        }?.takeIf { it.version == MEDIA_ANALYSIS_VERSION }
        cache[key] = result
        return result
    }

    fun save(result: MediaAnalysisResult) {
        cache[result.key] = result
        MediaAnalysisFiles.write(result.key, json.encodeToString(MediaAnalysisResult.serializer(), result))
    }

    /** Other episodes of the same season that have fingerprints. */
    fun seasonSiblings(parentMetaId: String, season: Int, exceptKey: String): List<MediaAnalysisResult> {
        val prefix = "${sanitize(parentMetaId.trim())}__s${season}e"
        return MediaAnalysisFiles.list(prefix)
            .filter { it != exceptKey }
            .mapNotNull(::load)
            .filter { !it.headFingerprint.isNullOrEmpty() || !it.tailFingerprint.isNullOrEmpty() }
    }

    /** The download is gone: drop what only applies to that file, keep fingerprints and segments. */
    fun forgetFileData(key: String) {
        val current = load(key) ?: return
        if (current.energy == null && current.fileName == null) return
        save(current.copy(energy = null, fileName = null, integratedLufs = null))
    }

    private fun sanitize(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("")
}
