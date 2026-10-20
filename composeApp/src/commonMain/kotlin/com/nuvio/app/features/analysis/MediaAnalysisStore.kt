package com.nuvio.app.features.analysis

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import com.nuvio.app.features.downloads.DownloadItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Raw per-key text files (iOS: Application Support/NuvioAnalysis). Android keeps nothing yet. */
internal expect object MediaAnalysisFiles {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun list(prefix: String): List<String>
    /** Absolute path for a binary file (thumbnail sheet) in the analysis folder. */
    fun binaryPath(fileName: String): String?
    fun readBytes(fileName: String): ByteArray?
    fun delete(fileName: String)
    fun writeBytes(fileName: String, bytes: ByteArray): Boolean
}

/**
 * One file per episode (or movie), named from the show id and season/episode so the other episodes
 * of a season are a prefix listing away. Kept after the download is deleted (fingerprints and
 * segments only, see [forgetFileData]).
 */
private const val ALIASES_FILE = "show-aliases"
private const val IMPORT_CLEANUP_FLAG = "migration-192-imported-marks"

/** Without imported marks, and without the times they had been copied into. */
internal fun MediaAnalysisResult.withoutImportedMarks(): MediaAnalysisResult {
    val dropped = userSegments.filterValues { it.imported }.keys
    if (dropped.isEmpty()) return this
    var r = copy(userSegments = userSegments - dropped, learnedTypes = learnedTypes - dropped, derivedVersion = 0)
    if ("intro" in dropped) r = r.copy(introStartMs = null, introEndMs = null)
    if ("outro" in dropped) r = r.copy(creditsStartMs = null, creditsEndMs = null)
    if ("recap" in dropped) r = r.copy(recapStartMs = null, recapEndMs = null)
    return r
}

/** Without the intro/credits learned from other episodes' marks (they are learned again). */
internal fun MediaAnalysisResult.withoutLearnedTypes(): MediaAnalysisResult {
    var r = copy(learnedTypes = emptySet(), derivedVersion = 0)
    if ("intro" in learnedTypes && "intro" !in userSegments) r = r.copy(introStartMs = null, introEndMs = null)
    if ("outro" in learnedTypes && "outro" !in userSegments) r = r.copy(creditsStartMs = null, creditsEndMs = null)
    return r
}

internal object MediaAnalysisStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cache = mutableMapOf<String, MediaAnalysisResult?>()

    private val lock = SynchronizedObject()

    fun keyFor(parentMetaId: String, season: Int?, episode: Int?): String {
        val show = showKey(parentMetaId)
        return if (season != null && episode != null) "${show}__s${season}e$episode" else "${show}__movie"
    }

    fun keyFor(item: DownloadItem): String = keyFor(item.parentMetaId, item.seasonNumber, item.episodeNumber)

    fun load(key: String): MediaAnalysisResult? = synchronized(lock) {
        dropMisimportedMarksOnce()
        if (cache.containsKey(key)) return cache[key]
        val result = MediaAnalysisFiles.read(key)?.let { text ->
            runCatching { json.decodeFromString(MediaAnalysisResult.serializer(), text) }.getOrNull()
        }?.takeIf { it.version == MEDIA_ANALYSIS_VERSION }
        cache[key] = result
        result
    }

    /** Read-modify-write of one episode under the lock, so concurrent jobs can't drop each other's changes. */
    fun update(key: String, transform: (MediaAnalysisResult?) -> MediaAnalysisResult?): MediaAnalysisResult? =
        synchronized(lock) { transform(load(key))?.also(::save) }

    /** Show ids that name the same title (tmdb:1, tt…) share one stored key: the first one seen. */
    private val aliases: MutableMap<String, String> by lazy {
        MediaAnalysisFiles.read(ALIASES_FILE)?.let { text ->
            runCatching { json.decodeFromString<Map<String, String>>(text) }.getOrNull()
        }.orEmpty().toMutableMap()
    }

    private fun showKey(parentMetaId: String): String {
        val id = sanitize(parentMetaId.trim())
        return synchronized(lock) { aliases[id]?.let { aliases[it] ?: it } } ?: id
    }

    /**
     * Records that [parentMetaId] is TMDB title [tmdbId]. The first id seen for a title owns its
     * store; episodes stored under a later id are merged into it (marks already there win).
     */
    fun linkShow(parentMetaId: String, tmdbId: Int) = synchronized(lock) {
        val id = sanitize(parentMetaId.trim())
        if (id.isEmpty() || tmdbId <= 0 || id in aliases) return@synchronized
        val group = "tmdb#$tmdbId"
        val owner = aliases[group]
        if (owner == id) return@synchronized
        if (owner == null) {
            aliases[group] = id
        } else {
            aliases[id] = group
            MediaAnalysisFiles.list("${id}__").forEach { oldKey -> mergeInto(oldKey, owner + oldKey.removePrefix(id)) }
        }
        MediaAnalysisFiles.write(ALIASES_FILE, json.encodeToString(aliases.toMap()))
    }

    private fun mergeInto(oldKey: String, newKey: String) {
        val old = load(oldKey)
        if (old != null) {
            val target = load(newKey)
            save(
                target?.copy(userSegments = old.userSegments + target.userSegments)
                    ?: old.copy(key = newKey),
            )
        }
        cache.remove(oldKey)
        MediaAnalysisFiles.delete("$oldKey.json")
    }

    private var importCleanupDone = false

    /**
     * Build 192, once (again, as 191 re-imported the same rows): imports up to 190 could put another show's TheIntroDB submissions on an
     * episode with the same number. Drops every imported mark (and what was copied or learned
     * from it in that show); the fixed import brings the real ones back on the next play.
     * Marks made on this phone are kept.
     */
    private fun dropMisimportedMarksOnce() {
        if (importCleanupDone) return
        importCleanupDone = true
        if (MediaAnalysisFiles.read(IMPORT_CLEANUP_FLAG) != null) return
        val all = MediaAnalysisFiles.list("").filter { it.contains("__") }.mapNotNull(::load)
        val affectedShows = mutableSetOf<String>()
        all.forEach { result ->
            val cleaned = result.withoutImportedMarks()
            if (cleaned != result) {
                affectedShows += result.key.substringBefore("__")
                save(cleaned)
            }
        }
        all.filter { it.key.substringBefore("__") in affectedShows && it.learnedTypes.isNotEmpty() }
            .forEach { stored -> load(stored.key)?.let { save(it.withoutLearnedTypes()) } }
        MediaAnalysisFiles.write(IMPORT_CLEANUP_FLAG, "1")
    }

    fun save(result: MediaAnalysisResult) = synchronized(lock) {
        // User marks always win over whatever was detected.
        val applied = result.withUserSegmentsApplied()
        cache[applied.key] = applied
        MediaAnalysisFiles.write(applied.key, json.encodeToString(MediaAnalysisResult.serializer(), applied))
    }

    /** Other episodes of the same season that have fingerprints. */
    fun seasonSiblings(parentMetaId: String, season: Int, exceptKey: String): List<MediaAnalysisResult> =
        seasonResults(parentMetaId, season)
            .filter { it.key != exceptKey }
            .filter { !it.headFingerprint.isNullOrEmpty() || !it.tailFingerprint.isNullOrEmpty() }

    /** Everything stored for the episodes of a season (marks without a download included). */
    fun seasonResults(parentMetaId: String, season: Int): List<MediaAnalysisResult> =
        MediaAnalysisFiles.list("${showKey(parentMetaId)}__s${season}e").mapNotNull(::load)

    /** The download is gone: drop what only applies to that file, keep fingerprints and segments. */
    fun forgetFileData(key: String) {
        val current = load(key) ?: return
        if (current.energy == null && current.fileName == null && current.thumbnails == null) return
        current.thumbnails?.let { sheets ->
            repeat(sheets.sheets) { MediaAnalysisFiles.delete(thumbnailSheetFileName(key, it)) }
        }
        save(current.copy(energy = null, fileName = null, integratedLufs = null, thumbnails = null))
    }

    /**
     * The show is finished: drop the fingerprints kept to match later episodes, keep each
     * episode's skip times for a rewatch.
     */
    fun forgetReferences(parentMetaId: String) {
        if (parentMetaId.isBlank()) return
        MediaAnalysisFiles.list("${showKey(parentMetaId)}__").forEach { key ->
            val current = load(key) ?: return@forEach
            if (current.headFingerprint == null && current.tailFingerprint == null) return@forEach
            save(current.copy(headFingerprint = null, tailFingerprint = null))
        }
    }

    /** Deletes everything stored for [key] (analysis JSON and thumbnail sheets). */
    fun reset(key: String) {
        val marks = load(key)?.userSegments.orEmpty()
        resetAll(key)
        // Analysing again keeps what the user marked.
        if (marks.isNotEmpty()) save(MediaAnalysisResult(key = key, analyzedAtMs = 0L, durationMs = 0L, userSegments = marks))
    }

    private fun resetAll(key: String) {
        load(key)?.thumbnails?.let { sheets ->
            repeat(sheets.sheets) { MediaAnalysisFiles.delete(thumbnailSheetFileName(key, it)) }
        }
        MediaAnalysisFiles.delete("$key.json")
        cache[key] = null
    }

    private fun sanitize(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("")
}
