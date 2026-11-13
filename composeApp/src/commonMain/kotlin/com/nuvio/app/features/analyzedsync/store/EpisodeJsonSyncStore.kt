package com.nuvio.app.features.analyzedsync.store

import com.nuvio.app.core.serialization.LenientDefaultsJson
import com.nuvio.app.features.analysis.MEDIA_ANALYSIS_VERSION
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.UserSegment
import com.nuvio.app.features.analyzedsync.clock.Hlc
import com.nuvio.app.features.analyzedsync.json.JsonValue
import com.nuvio.app.features.analyzedsync.json.canonicalJson
import com.nuvio.app.features.analyzedsync.json.toSyncJson
import com.nuvio.app.features.analyzedsync.merge.mergeRecords
import com.nuvio.app.features.analyzedsync.record.AliasRecord
import com.nuvio.app.features.analyzedsync.record.DetectionRecord
import com.nuvio.app.features.analyzedsync.record.MarkRecord
import com.nuvio.app.features.analyzedsync.record.SubmissionRecord
import com.nuvio.app.features.analyzedsync.record.SyncRecord
import com.nuvio.app.features.analyzedsync.record.TombstoneRecord
import com.nuvio.app.features.analyzedsync.record.detectionId
import com.nuvio.app.features.analyzedsync.record.episodeKey
import com.nuvio.app.features.analyzedsync.record.isShowId
import com.nuvio.app.features.analyzedsync.record.noneId
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Reads and writes the existing per-episode analysis JSON (`show__s1e8`, `show__movie`) and the
 * `show-aliases` file. Fingerprints, energy, and thumbnail sheets stay in the episode file and
 * are never copied into a sync record.
 */
internal class EpisodeJsonSyncStore(private val files: AnalysisTextFiles) : AnalysisSyncPort {
    override fun readLocal(): List<LocalRecord> {
        val records = mutableListOf<LocalRecord>()
        records += readStored(files, TOMBSTONES_FILE)
        records += readStored(files, SUBMISSIONS_FILE)
        val storedAliases = readStored(files, ALIASES_SYNC_FILE)
        records += if (storedAliases.isNotEmpty()) storedAliases else aliasRecordsFromMap(readAliasMap(files))
        files.list("").filter { it.contains("__") }.forEach { name ->
            val parsed = parseEpisodeName(name) ?: return@forEach
            val text = files.read(name) ?: return@forEach
            val episode = decodeEpisode(text) ?: return@forEach
            val showId = diskShowToSyncId(parsed.diskShow) ?: return@forEach
            records += recordsFromEpisode(episode, showId, parsed.season, parsed.episode)
        }
        return records
    }

    override fun replaceRecords(records: List<SyncRecord>, nowMs: Long) {
        val tombstones = records.filterIsInstance<TombstoneRecord>()
        val live = records.filter { !it.deleted }
        writeStored(files, TOMBSTONES_FILE, tombstones)
        writeStored(files, SUBMISSIONS_FILE, live.filterIsInstance<SubmissionRecord>() + tombstones.filter { it.kind == "submission" })
        val aliasLive = live.filterIsInstance<AliasRecord>()
        writeStored(files, ALIASES_SYNC_FILE, aliasLive + tombstones.filter { it.kind == "alias" })
        writeAliasMap(files, aliasMapFrom(aliasLive))
        val state = mergeRecords(records.map { it.toWire() }, nowMs)
        val existing = episodeFiles(files)
        val used = mutableSetOf<String>()
        for (show in state.shows) {
            for (episode in show.episodes) {
                val disk = existingDisk(existing, show.aliases) ?: syncIdToDisk(show.showId) ?: continue
                val key = episodeFileKey(disk, episode.season, episode.episode)
                used += key
                val current = existing[key] ?: emptyEpisode(key, episode.season, episode.episode)
                val episodeRecords = live.filter { record ->
                    val showId = record.showIdOrNull() ?: return@filter false
                    showId in show.aliases && sameEpisode(record, episode.season, episode.episode)
                }
                files.write(key, encodeEpisode(project(current, episodeRecords, show.aliases, nowMs)))
            }
        }
        for ((key, episode) in existing) {
            if (key in used || episode.syncRecordsJson.isEmpty()) continue
            files.write(key, encodeEpisode(episode.copy(userSegments = emptyMap(), userNoneTypes = emptySet(), syncRecordsJson = "")))
        }
    }

    private fun recordsFromEpisode(episode: MediaAnalysisResult, showId: String, season: Long?, episodeNumber: Long?): List<LocalRecord> {
        val stored = decodeRecords(episode.syncRecordsJson)
        if (stored.isEmpty()) return legacyRecords(episode, showId, season, episodeNumber)
        val fresh = mutableListOf<LocalRecord>()
        val kept = stored.map { LocalRecord(it, needsStamp = false) }.toMutableList()
        for ((localType, segment) in episode.userSegments) {
            val wire = wireType(localType) ?: continue
            val match = stored.filterIsInstance<MarkRecord>().find {
                it.presence == "range" && it.segmentType == wire && (it.segmentIndex ?: 0) == 0 &&
                    it.startMs == segment.startMs && it.endMs == segment.endMs && it.durationMs == segment.durationMs
            }
            if (match == null) {
                val previous = stored.filterIsInstance<MarkRecord>().find {
                    it.presence == "range" && it.segmentType == wire && (it.segmentIndex ?: 0) == 0
                }
                fresh += LocalRecord(
                    rangeMark(previous?.id.orEmpty(), showId, season, episodeNumber, wire, segment),
                    needsStamp = true,
                )
                if (previous != null) kept.removeAll { it.record.id == previous.id && it.record.kind == "mark" }
            }
        }
        return kept + fresh
    }

    private fun legacyRecords(episode: MediaAnalysisResult, showId: String, season: Long?, episodeNumber: Long?): List<LocalRecord> {
        val records = mutableListOf<LocalRecord>()
        val marked = mutableSetOf<String>()
        for ((localType, segment) in episode.userSegments) {
            val wire = wireType(localType) ?: continue
            if (segment.endMs <= segment.startMs) continue
            marked += wire
            records += LocalRecord(rangeMark("", showId, season, episodeNumber, wire, segment), needsStamp = true)
        }
        for (localType in episode.userNoneTypes) {
            val wire = wireType(localType) ?: continue
            marked += wire
            val key = episodeKey(season, episodeNumber) ?: continue
            records += LocalRecord(
                MarkRecord(
                    id = noneId(showId, key, wire),
                    hlc = placeholderClock(),
                    showId = showId,
                    season = season,
                    episode = episodeNumber,
                    segmentType = wire,
                    presence = "none",
                    segmentIndex = null,
                    startMs = null,
                    endMs = null,
                    durationMs = 0,
                ),
                needsStamp = true,
            )
        }
        val key = episodeKey(season, episodeNumber) ?: return records
        fun found(type: String, start: Long?, end: Long?) {
            if (type in marked || start == null || end == null || end <= start) return
            records += LocalRecord(
                detection(showId, season, episodeNumber, key, type, "found", start, end, episode.durationMs),
                needsStamp = true,
            )
        }
        found("intro", episode.introStartMs, episode.introEndMs)
        found("credits", episode.creditsStartMs, episode.creditsEndMs)
        found("recap", episode.recapStartMs, episode.recapEndMs)
        if (episode.analyzedAtMs > 0L && episode.version == MEDIA_ANALYSIS_VERSION) {
            fun missing(type: String, start: Long?, end: Long?) {
                if (type in marked || (start != null && end != null)) return
                records += LocalRecord(
                    detection(showId, season, episodeNumber, key, type, "not_found", null, null, episode.durationMs),
                    needsStamp = true,
                )
            }
            missing("intro", episode.introStartMs, episode.introEndMs)
            missing("credits", episode.creditsStartMs, episode.creditsEndMs)
            if (episode.derivedVersion > 0) missing("recap", episode.recapStartMs, episode.recapEndMs)
        }
        return records
    }

    private fun project(current: MediaAnalysisResult, records: List<SyncRecord>, aliases: List<String>, nowMs: Long): MediaAnalysisResult {
        val state = mergeRecords(records.map { it.toWire() }, nowMs)
        val episode = state.shows.find { it.showId in aliases || it.aliases.any { alias -> alias in aliases } }?.episodes?.firstOrNull()
        val marks = linkedMapOf<String, UserSegment>()
        val nones = linkedSetOf<String>()
        episode?.marks?.filter { it.applied && it.presence == "range" }?.sortedBy { it.segmentIndex ?: 0 }?.forEach { mark ->
            val local = localType(mark.segmentType)
            if (local !in marks && mark.startMs != null && mark.endMs != null) {
                marks[local] = UserSegment(
                    startMs = mark.startMs,
                    endMs = mark.endMs,
                    markedAtMs = mark.hlc.wallMs,
                    durationMs = mark.durationMs,
                )
            }
        }
        episode?.userNoneTypes?.forEach { type -> localType(type).let { nones += it } }
        var projected = current.copy(
            userSegments = marks,
            userNoneTypes = nones,
            syncRecordsJson = canonicalJson(JsonValue.Arr(records.map { it.toWire() })),
            introStartMs = null,
            introEndMs = null,
            creditsStartMs = null,
            creditsEndMs = null,
            recapStartMs = null,
            recapEndMs = null,
        )
        episode?.detections?.filter { it.applied && it.outcome == "found" && it.segmentIndex == 0 }?.forEach { detection ->
            if (detection.segmentType in episode.userNoneTypes) return@forEach
            if (marks.containsKey(localType(detection.segmentType))) return@forEach
            projected = when (detection.segmentType) {
                "intro" -> projected.copy(introStartMs = detection.startMs, introEndMs = detection.endMs)
                "credits" -> projected.copy(creditsStartMs = detection.startMs, creditsEndMs = detection.endMs)
                "recap" -> projected.copy(recapStartMs = detection.startMs, recapEndMs = detection.endMs)
                else -> projected
            }
        }
        return projected
    }
}

internal interface AnalysisTextFiles {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun list(prefix: String): List<String>
}

private const val TOMBSTONES_FILE = "tombstones-sync"
private const val SUBMISSIONS_FILE = "submissions-sync"
private const val ALIASES_SYNC_FILE = "aliases-sync"
private const val SHOW_ALIASES_FILE = "show-aliases"
private val json = LenientDefaultsJson

private fun readStored(files: AnalysisTextFiles, name: String): List<LocalRecord> =
    decodeRecords(files.read(name).orEmpty()).map { LocalRecord(it, needsStamp = false) }

private fun writeStored(files: AnalysisTextFiles, name: String, records: List<SyncRecord>) {
    if (records.isEmpty()) {
        files.write(name, "[]")
        return
    }
    files.write(name, canonicalJson(JsonValue.Arr(records.map { it.toWire() })))
}

private fun decodeRecords(text: String): List<SyncRecord> {
    if (text.isBlank() || text == "[]") return emptyList()
    val value = runCatching { Json.parseToJsonElement(text).toSyncJson() }.getOrNull() as? JsonValue.Arr ?: return emptyList()
    return value.items.mapNotNull { item -> com.nuvio.app.features.analyzedsync.record.parseRecord(item)?.record }
}

private fun decodeEpisode(text: String): MediaAnalysisResult? =
    runCatching { json.decodeFromString(MediaAnalysisResult.serializer(), text) }.getOrNull()

private fun encodeEpisode(result: MediaAnalysisResult): String = json.encodeToString(MediaAnalysisResult.serializer(), result)

internal data class EpisodeName(val diskShow: String, val season: Long?, val episode: Long?)

internal fun parseEpisodeName(name: String): EpisodeName? {
    val split = name.split("__", limit = 2)
    if (split.size != 2 || split[0].isEmpty()) return null
    if (split[1] == "movie") return EpisodeName(split[0], null, null)
    val match = Regex("^s([0-9]+)e([1-9][0-9]*)$").matchEntire(split[1]) ?: return null
    return EpisodeName(split[0], match.groupValues[1].toLong(), match.groupValues[2].toLong())
}

internal fun episodeFileKey(diskShow: String, season: Long?, episode: Long?): String =
    if (season == null || episode == null) "${diskShow}__movie" else "${diskShow}__s${season}e$episode"

internal fun diskShowToSyncId(disk: String): String? {
    if (disk.startsWith("tt") && isShowId("imdb:$disk")) return "imdb:$disk"
    val tmdb = Regex("^tmdb_([1-9][0-9]{0,9})$").matchEntire(disk) ?: return if (isShowId(disk)) disk else null
    return "tmdb:${tmdb.groupValues[1]}"
}

internal fun syncIdToDisk(showId: String): String? = when {
    showId.startsWith("imdb:tt") && isShowId(showId) -> showId.removePrefix("imdb:")
    showId.startsWith("tmdb:") && isShowId(showId) -> "tmdb_${showId.removePrefix("tmdb:")}"
    isShowId(showId) -> showId
    else -> null
}

private fun wireType(local: String): String? = when (local) {
    "outro" -> "credits"
    "intro", "recap", "credits", "preview" -> local
    else -> null
}

private fun localType(wire: String): String = if (wire == "credits") "outro" else wire

private fun placeholderClock(): Hlc = Hlc(0, 0, "local")

private fun rangeMark(id: String, showId: String, season: Long?, episode: Long?, type: String, segment: UserSegment): MarkRecord =
    MarkRecord(
        id = id,
        hlc = placeholderClock(),
        showId = showId,
        season = season,
        episode = episode,
        segmentType = type,
        presence = "range",
        segmentIndex = 0,
        startMs = segment.startMs,
        endMs = segment.endMs,
        durationMs = segment.durationMs,
    )

private fun detection(
    showId: String,
    season: Long?,
    episode: Long?,
    key: String,
    type: String,
    outcome: String,
    start: Long?,
    end: Long?,
    durationMs: Long,
): DetectionRecord {
    val duration = durationMs.coerceAtLeast(0)
    return DetectionRecord(
        id = detectionId(showId, key, type, 0, duration, MEDIA_ANALYSIS_VERSION),
        hlc = placeholderClock(),
        showId = showId,
        season = season,
        episode = episode,
        segmentType = type,
        segmentIndex = 0,
        outcome = outcome,
        startMs = start,
        endMs = end,
        durationMs = duration,
        algorithm = "nuvio-analysis-$MEDIA_ANALYSIS_VERSION",
        algorithmVersion = MEDIA_ANALYSIS_VERSION,
        releaseId = null,
    )
}

private fun SyncRecord.showIdOrNull(): String? = when (this) {
    is MarkRecord -> showId
    is DetectionRecord -> showId
    else -> null
}

private fun sameEpisode(record: SyncRecord, season: Long?, episode: Long?): Boolean = when (record) {
    is MarkRecord -> record.season == season && record.episode == episode
    is DetectionRecord -> record.season == season && record.episode == episode
    else -> false
}

private fun episodeFiles(files: AnalysisTextFiles): Map<String, MediaAnalysisResult> =
    files.list("").filter { it.contains("__") }.mapNotNull { name ->
        val text = files.read(name) ?: return@mapNotNull null
        val episode = decodeEpisode(text) ?: return@mapNotNull null
        name to episode
    }.toMap()

private fun existingDisk(existing: Map<String, MediaAnalysisResult>, aliases: List<String>): String? {
    val disks = aliases.mapNotNull(::syncIdToDisk).toSet()
    return existing.keys.firstNotNullOfOrNull { key ->
        val name = parseEpisodeName(key) ?: return@firstNotNullOfOrNull null
        if (name.diskShow in disks) name.diskShow else null
    }
}

private fun emptyEpisode(key: String, season: Long?, episode: Long?): MediaAnalysisResult =
    MediaAnalysisResult(key = key, analyzedAtMs = 0L, durationMs = 0L)

private fun readAliasMap(files: AnalysisTextFiles): Map<String, String> {
    val text = files.read(SHOW_ALIASES_FILE) ?: return emptyMap()
    return runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), text) }.getOrDefault(emptyMap())
}

private fun writeAliasMap(files: AnalysisTextFiles, map: Map<String, String>) {
    files.write(SHOW_ALIASES_FILE, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), map))
}

private fun aliasRecordsFromMap(map: Map<String, String>): List<LocalRecord> {
    val records = mutableListOf<LocalRecord>()
    val groups = map.filterKeys { it.startsWith("tmdb#") }
    for ((group, owner) in groups) {
        val tmdb = group.removePrefix("tmdb#").toLongOrNull() ?: continue
        val tmdbId = "tmdb:$tmdb"
        if (!isShowId(tmdbId)) continue
        val members = map.filterValues { it == group }.keys + owner
        for (member in members) {
            val showId = diskShowToSyncId(member) ?: continue
            if (showId == tmdbId) continue
            records += LocalRecord(
                AliasRecord("alias/$showId", placeholderClock(), showId, tmdbId),
                needsStamp = true,
            )
        }
    }
    return records
}

private fun aliasMapFrom(records: List<AliasRecord>): Map<String, String> {
    val map = linkedMapOf<String, String>()
    for (alias in records) {
        val tmdb = listOf(alias.from, alias.to).firstOrNull { it.startsWith("tmdb:") } ?: continue
        val other = if (alias.from == tmdb) alias.to else alias.from
        val group = "tmdb#${tmdb.removePrefix("tmdb:")}"
        val disk = syncIdToDisk(other) ?: continue
        if (group !in map) map[group] = disk
        if (disk != map[group]) map[disk] = group
    }
    return map
}
