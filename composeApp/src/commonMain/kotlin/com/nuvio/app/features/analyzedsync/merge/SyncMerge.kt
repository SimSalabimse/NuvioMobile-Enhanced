package com.nuvio.app.features.analyzedsync.merge

import com.nuvio.app.features.analyzedsync.SYNC_CUT_TOLERANCE_MS
import com.nuvio.app.features.analyzedsync.SYNC_SEGMENT_TYPES
import com.nuvio.app.features.analyzedsync.SYNC_TOMBSTONE_KEEP_MS
import com.nuvio.app.features.analyzedsync.clock.Hlc
import com.nuvio.app.features.analyzedsync.clock.clockIsAcceptable
import com.nuvio.app.features.analyzedsync.json.JsonValue
import com.nuvio.app.features.analyzedsync.json.canonicalJson
import com.nuvio.app.features.analyzedsync.json.jBool
import com.nuvio.app.features.analyzedsync.json.jNull
import com.nuvio.app.features.analyzedsync.json.jNum
import com.nuvio.app.features.analyzedsync.json.jObj
import com.nuvio.app.features.analyzedsync.json.jStr
import com.nuvio.app.features.analyzedsync.record.AliasRecord
import com.nuvio.app.features.analyzedsync.record.DetectionRecord
import com.nuvio.app.features.analyzedsync.record.MarkRecord
import com.nuvio.app.features.analyzedsync.record.ParsedRecord
import com.nuvio.app.features.analyzedsync.record.SubmissionRecord
import com.nuvio.app.features.analyzedsync.record.SyncRecord
import com.nuvio.app.features.analyzedsync.record.compareParsed
import com.nuvio.app.features.analyzedsync.record.episodeKey
import com.nuvio.app.features.analyzedsync.record.normShowId
import com.nuvio.app.features.analyzedsync.record.parseRecord

internal data class MarkView(
    val applied: Boolean,
    val durationMs: Long,
    val endMs: Long?,
    val hlc: Hlc,
    val id: String,
    val presence: String,
    val segmentIndex: Int?,
    val segmentType: String,
    val startMs: Long?,
    val suppressedBy: String?,
)

internal data class DetectionView(
    val algorithm: String,
    val algorithmVersion: Int,
    val applied: Boolean,
    val durationMs: Long,
    val endMs: Long?,
    val hlc: Hlc,
    val id: String,
    val outcome: String,
    val releaseId: String?,
    val segmentIndex: Int,
    val segmentType: String,
    val startMs: Long?,
    val suppressedBy: String?,
)

internal data class EffectiveSegment(
    val algorithm: String?,
    val durationMs: Long,
    val endMs: Long?,
    val id: String,
    val presence: String,
    val segmentIndex: Int?,
    val segmentType: String,
    val source: String,
    val startMs: Long?,
)

internal data class EpisodeState(
    val detections: List<DetectionView>,
    val effective: List<EffectiveSegment>,
    val episode: Long?,
    val episodeKey: String,
    val marks: List<MarkView>,
    val season: Long?,
    val userNoneTypes: List<String>,
)

internal data class ShowState(
    val aliases: List<String>,
    val episodes: List<EpisodeState>,
    val showId: String,
)

internal data class SubmissionState(
    val accountScope: String,
    val durationMs: Long,
    val endMs: Long,
    val episode: Long?,
    val hlc: Hlc,
    val id: String,
    val mediaType: String,
    val remoteId: String,
    val season: Long?,
    val segmentType: String,
    val showId: String,
    val startMs: Long,
    val status: String,
)

internal data class TombstoneState(
    val hlc: Hlc,
    val id: String,
    val kind: String,
    val purgeAfterMs: Long,
)

internal data class MergedState(
    val rejected: Int,
    val shows: List<ShowState>,
    val submissions: List<SubmissionState>,
    val tombstones: List<TombstoneState>,
)

internal data class AnalyseQuery(
    val showId: String,
    val season: Long?,
    val episode: Long?,
    val segmentType: String,
    val segmentIndex: Int,
    val fileDurationMs: Long,
    val localAlgorithm: Int,
)

internal fun mergeRecords(inputs: List<JsonValue>, nowMs: Long): MergedState {
    val parsed = mutableListOf<ParsedRecord>()
    var rejected = 0
    for (input in inputs) {
        val record = parseRecord(input)
        if (record == null || !clockIsAcceptable(record.record.hlc.wallMs, nowMs)) rejected += 1
        else parsed += record
    }
    return mergeParsed(parsed, rejected, nowMs)
}

internal fun mergedCanonical(inputs: List<JsonValue>, nowMs: Long): String = canonicalJson(mergeRecords(inputs, nowMs).toJson())

internal fun shouldAnalyse(state: MergedState, query: AnalyseQuery): Boolean {
    val show = state.shows.find { it.showId == query.showId || query.showId in it.aliases } ?: return true
    val episode = show.episodes.find { it.season == query.season && it.episode == query.episode } ?: return true
    if (query.segmentType in episode.userNoneTypes) return false
    val markCovers = episode.marks.any { mark ->
        mark.applied && mark.presence == "range" && mark.segmentType == query.segmentType &&
            mark.segmentIndex == query.segmentIndex && durationCovers(mark.durationMs, query.fileDurationMs)
    }
    if (markCovers) return false
    val detectionCovers = episode.detections.any { detection ->
        detection.applied && detection.outcome == "found" && detection.segmentType == query.segmentType &&
            detection.segmentIndex == query.segmentIndex && detection.algorithmVersion >= query.localAlgorithm &&
            cutsMatch(detection.durationMs, query.fileDurationMs)
    }
    return !detectionCovers
}

/** Winning records after the same clock and tombstone rules merge uses. */
internal fun keepLatest(records: List<SyncRecord>, nowMs: Long): List<SyncRecord> {
    val parsed = mutableListOf<ParsedRecord>()
    for (record in records) {
        val parsedRecord = parseRecord(record.toWire()) ?: continue
        if (!clockIsAcceptable(parsedRecord.record.hlc.wallMs, nowMs)) continue
        parsed += parsedRecord
    }
    return collapse(parsed).mapNotNull { winner ->
        if (!winner.record.deleted) winner.record
        else {
            val purgeAfterMs = winner.record.hlc.wallMs + SYNC_TOMBSTONE_KEEP_MS
            if (nowMs < purgeAfterMs) winner.record else null
        }
    }
}

private fun mergeParsed(parsed: List<ParsedRecord>, rejected: Int, nowMs: Long): MergedState {
    val winners = collapse(parsed)
    val graph = buildGraph(winners)
    val tombstones = mutableListOf<TombstoneState>()
    val marks = mutableListOf<MarkRecord>()
    val detections = mutableListOf<DetectionRecord>()
    val submissions = mutableListOf<SubmissionRecord>()
    for (winner in winners) {
        val record = winner.record
        if (record.deleted) {
            val purgeAfterMs = record.hlc.wallMs + SYNC_TOMBSTONE_KEEP_MS
            if (nowMs < purgeAfterMs) {
                tombstones += TombstoneState(record.hlc, record.id, record.kind, purgeAfterMs)
            }
            continue
        }
        when (record) {
            is MarkRecord -> marks += record
            is DetectionRecord -> detections += record
            is SubmissionRecord -> submissions += record
            else -> Unit
        }
    }
    data class Bucket(
        val showId: String,
        val season: Long?,
        val episode: Long?,
        val marks: MutableList<MarkRecord>,
        val detections: MutableList<DetectionRecord>,
    )
    val episodes = linkedMapOf<String, Bucket>()
    fun addEpisode(showId: String, season: Long?, episode: Long?): Bucket {
        val canonical = graph.canonicalOf(showId)
        val key = "$canonical\n${season ?: ""}\n${episode ?: ""}"
        return episodes.getOrPut(key) { Bucket(canonical, season, episode, mutableListOf(), mutableListOf()) }
    }
    for (mark in marks) addEpisode(mark.showId, mark.season, mark.episode).marks += mark
    for (detection in detections) addEpisode(detection.showId, detection.season, detection.episode).detections += detection
    val shows = linkedMapOf<String, ShowState>()
    for (bucket in episodes.values) {
        val existing = shows[bucket.showId]
        val markResult = resolveMarks(bucket.marks)
        val detectionResult = resolveDetections(bucket.detections, markResult.views)
        val effective = (markResult.effective + detectionResult.effective).sortedWith(::compareEffective)
        val episode = EpisodeState(
            detections = detectionResult.views,
            effective = effective,
            episode = bucket.episode,
            episodeKey = episodeKey(bucket.season, bucket.episode) ?: "movie",
            marks = markResult.views,
            season = bucket.season,
            userNoneTypes = SYNC_SEGMENT_TYPES.filter { type ->
                markResult.views.any { it.applied && it.presence == "none" && it.segmentType == type }
            },
        )
        val episodesOut = (existing?.episodes.orEmpty() + episode).sortedWith { left, right ->
            val rank = episodeRank(left).compareTo(episodeRank(right))
            if (rank != 0) rank else cmp(left.episodeKey, right.episodeKey)
        }
        shows[bucket.showId] = ShowState(graph.aliasesOf(bucket.showId), episodesOut, bucket.showId)
    }
    val submissionStates = submissions.map { submission ->
        SubmissionState(
            accountScope = submission.accountScope,
            durationMs = submission.durationMs,
            endMs = submission.endMs,
            episode = submission.episode,
            hlc = submission.hlc,
            id = submission.id,
            mediaType = submission.mediaType,
            remoteId = submission.remoteId,
            season = submission.season,
            segmentType = submission.segmentType,
            showId = graph.canonicalOf(submission.showId),
            startMs = submission.startMs,
            status = submission.status,
        )
    }.sortedWith { left, right -> cmp(left.accountScope, right.accountScope).takeIf { it != 0 } ?: cmp(left.id, right.id) }
    tombstones.sortWith { left, right -> cmp(left.kind, right.kind).takeIf { it != 0 } ?: cmp(left.id, right.id) }
    return MergedState(
        rejected = rejected,
        shows = shows.values.sortedWith { left, right -> cmp(left.showId, right.showId) },
        submissions = submissionStates,
        tombstones = tombstones,
    )
}

private fun collapse(parsed: List<ParsedRecord>): List<ParsedRecord> {
    val winners = linkedMapOf<String, ParsedRecord>()
    for (record in parsed) {
        val key = "${record.record.kind}\n${record.record.id}"
        val previous = winners[key]
        if (previous == null || compareParsed(record, previous) > 0) winners[key] = record
    }
    return winners.values.toList()
}

private class ShowGraph(
    val canonicalOf: (String) -> String,
    val aliasesOf: (String) -> List<String>,
)

private fun buildGraph(winners: List<ParsedRecord>): ShowGraph {
    val parent = linkedMapOf<String, String>()
    val spellings = linkedMapOf<String, MutableSet<String>>()
    fun find(norm: String): String {
        val current = parent[norm]
        if (current == null) {
            parent[norm] = norm
            return norm
        }
        if (current == norm) return norm
        val root = find(current)
        parent[norm] = root
        return root
    }
    fun addNorm(norm: String): String {
        if (norm !in parent) parent[norm] = norm
        return find(norm)
    }
    fun addSpelling(showId: String) {
        val norm = normShowId(showId)
        addNorm(norm)
        spellings.getOrPut(norm) { linkedSetOf() }.add(showId)
    }
    fun union(left: String, right: String) {
        val a = addNorm(left)
        val b = addNorm(right)
        if (a != b) parent[a] = b
    }
    for (winner in winners) {
        val record = winner.record
        if (record.deleted) continue
        if (record is AliasRecord) {
            addSpelling(record.from)
            addSpelling(record.to)
            union(normShowId(record.from), normShowId(record.to))
        } else {
            val showId = when (record) {
                is MarkRecord -> record.showId
                is DetectionRecord -> record.showId
                is SubmissionRecord -> record.showId
                else -> continue
            }
            addSpelling(showId)
        }
    }
    val byRoot = linkedMapOf<String, MutableList<String>>()
    for (norm in parent.keys) {
        val root = find(norm)
        byRoot.getOrPut(root) { mutableListOf() }.add(norm)
    }
    val canonicalByNorm = linkedMapOf<String, String>()
    val aliasesByNorm = linkedMapOf<String, List<String>>()
    for (norms in byRoot.values) {
        val canonical = canonicalShow(norms, spellings)
        val aliases = norms.flatMap { spellings[it].orEmpty() }.toSet().sortedWith(::cmp)
        for (norm in norms) {
            canonicalByNorm[norm] = canonical
            aliasesByNorm[norm] = aliases
        }
    }
    return ShowGraph(
        canonicalOf = { showId -> canonicalByNorm[normShowId(showId)] ?: showId },
        aliasesOf = { showId -> aliasesByNorm[normShowId(showId)] ?: listOf(showId) },
    )
}

private fun canonicalShow(norms: List<String>, spellings: Map<String, Set<String>>): String {
    val tmdb = norms.filter { it.startsWith("tmdb:") }.map { it.removePrefix("tmdb:").toLong() }
    if (tmdb.isNotEmpty()) return "tmdb:${tmdb.min()}"
    val imdb = norms.filter { it.startsWith("imdb:") }.map { it.removePrefix("imdb:").toLong() }
    if (imdb.isNotEmpty()) {
        val best = imdb.min()
        val options = spellings["imdb:$best"].orEmpty().toMutableList()
        options.sortWith { left, right ->
            val leftDigits = left.removePrefix("imdb:tt")
            val rightDigits = right.removePrefix("imdb:tt")
            if (leftDigits.length != rightDigits.length) rightDigits.length - leftDigits.length
            else cmp(left, right)
        }
        return options.firstOrNull() ?: "imdb:tt$best"
    }
    val raw = norms.filter { it.startsWith("raw:") }.sortedWith(::cmp)
    return raw.firstOrNull() ?: norms.firstOrNull() ?: ""
}

private data class MarkResolution(val views: List<MarkView>, val effective: List<EffectiveSegment>)

private fun resolveMarks(marks: List<MarkRecord>): MarkResolution {
    val views = mutableListOf<MarkView>()
    val effective = mutableListOf<EffectiveSegment>()
    for (type in SYNC_SEGMENT_TYPES) {
        val group = marks.filter { it.segmentType == type }
        val nones = group.filter { it.presence == "none" }
        val ranges = group.filter { it.presence == "range" }
        val winningNone = maxRecord(nones)
        for (none in nones) {
            if (winningNone != null && none.id != winningNone.id) views += markView(none, false, "newer-none")
        }
        val appliedRanges = mutableListOf<MarkRecord>()
        for (range in ranges) {
            if (winningNone != null && compareParsed(wrap(winningNone), wrap(range)) > 0) {
                views += markView(range, false, "none")
            } else {
                appliedRanges += range
                views += markView(range, true, null)
            }
        }
        if (winningNone != null) {
            val applied = appliedRanges.isEmpty()
            views += markView(winningNone, applied, if (applied) null else "range")
            if (applied) effective += effectiveFromMark(winningNone)
        }
        for (range in appliedRanges) effective += effectiveFromMark(range)
    }
    views.sortWith { left, right -> cmp(left.id, right.id) }
    return MarkResolution(views, effective)
}

private data class DetectionResolution(val views: List<DetectionView>, val effective: List<EffectiveSegment>)

private fun resolveDetections(detections: List<DetectionRecord>, marks: List<MarkView>): DetectionResolution {
    val groups = linkedMapOf<String, MutableList<DetectionRecord>>()
    for (detection in detections) {
        groups.getOrPut("${detection.segmentType}\n${detection.segmentIndex}") { mutableListOf() }.add(detection)
    }
    val views = mutableListOf<DetectionView>()
    val effective = mutableListOf<EffectiveSegment>()
    for (group in groups.values) {
        val found = group.filter { it.outcome == "found" }.sortedWith { left, right ->
            when {
                left.durationMs != right.durationMs -> if (left.durationMs > right.durationMs) 1 else -1
                else -> cmp(left.id, right.id)
            }
        }
        val clusters = mutableListOf<MutableList<DetectionRecord>>()
        for (detection in found) {
            val current = clusters.lastOrNull()
            val minimum = current?.firstOrNull()
            if (current == null || minimum == null || detection.durationMs - minimum.durationMs > SYNC_CUT_TOLERANCE_MS) {
                clusters += mutableListOf(detection)
            } else {
                current += detection
            }
        }
        val winners = mutableSetOf<String>()
        val shadowed = mutableSetOf<String>()
        for (cluster in clusters) {
            val winner = maxDetection(cluster) ?: continue
            winners += winner.id
            for (other in cluster) if (other.id != winner.id) shadowed += other.id
        }
        for (detection in group) {
            var applied = false
            var suppressedBy: String? = null
            val marked = markSuppresses(detection, marks)
            if (detection.outcome == "not_found") {
                suppressedBy = if (marked) "mark" else null
            } else if (detection.id in shadowed) {
                suppressedBy = "newer-detection"
            } else if (detection.id in winners) {
                if (marked) suppressedBy = "mark" else applied = true
            }
            views += detectionView(detection, applied, suppressedBy)
            if (applied) effective += effectiveFromDetection(detection)
        }
    }
    views.sortWith { left, right -> cmp(left.id, right.id) }
    return DetectionResolution(views, effective)
}

private fun markSuppresses(detection: DetectionRecord, marks: List<MarkView>): Boolean = marks.any { mark ->
    if (!mark.applied || mark.segmentType != detection.segmentType) return@any false
    if (mark.presence == "none") return@any true
    if (mark.segmentIndex != detection.segmentIndex) return@any false
    if (mark.durationMs == 0L) return@any true
    cutsMatch(mark.durationMs, detection.durationMs)
}

private fun durationCovers(markDurationMs: Long, fileDurationMs: Long): Boolean {
    if (markDurationMs == 0L) return true
    return cutsMatch(markDurationMs, fileDurationMs)
}

internal fun cutsMatch(left: Long, right: Long): Boolean {
    if (left <= 0L || right <= 0L) return false
    val delta = if (left > right) left - right else right - left
    return delta <= SYNC_CUT_TOLERANCE_MS
}

private fun markView(mark: MarkRecord, applied: Boolean, suppressedBy: String?): MarkView = MarkView(
    applied = applied,
    durationMs = mark.durationMs,
    endMs = mark.endMs,
    hlc = mark.hlc,
    id = mark.id,
    presence = mark.presence,
    segmentIndex = mark.segmentIndex,
    segmentType = mark.segmentType,
    startMs = mark.startMs,
    suppressedBy = suppressedBy,
)

private fun detectionView(detection: DetectionRecord, applied: Boolean, suppressedBy: String?): DetectionView = DetectionView(
    algorithm = detection.algorithm,
    algorithmVersion = detection.algorithmVersion,
    applied = applied,
    durationMs = detection.durationMs,
    endMs = detection.endMs,
    hlc = detection.hlc,
    id = detection.id,
    outcome = detection.outcome,
    releaseId = detection.releaseId,
    segmentIndex = detection.segmentIndex,
    segmentType = detection.segmentType,
    startMs = detection.startMs,
    suppressedBy = suppressedBy,
)

private fun effectiveFromMark(mark: MarkRecord): EffectiveSegment = EffectiveSegment(
    algorithm = null,
    durationMs = mark.durationMs,
    endMs = mark.endMs,
    id = mark.id,
    presence = mark.presence,
    segmentIndex = mark.segmentIndex,
    segmentType = mark.segmentType,
    source = "mark",
    startMs = mark.startMs,
)

private fun effectiveFromDetection(detection: DetectionRecord): EffectiveSegment = EffectiveSegment(
    algorithm = detection.algorithm,
    durationMs = detection.durationMs,
    endMs = detection.endMs,
    id = detection.id,
    presence = "range",
    segmentIndex = detection.segmentIndex,
    segmentType = detection.segmentType,
    source = "detection",
    startMs = detection.startMs,
)

private fun compareEffective(left: EffectiveSegment, right: EffectiveSegment): Int {
    val type = SYNC_SEGMENT_TYPES.indexOf(left.segmentType) - SYNC_SEGMENT_TYPES.indexOf(right.segmentType)
    if (type != 0) return type
    val index = (left.segmentIndex ?: -1) - (right.segmentIndex ?: -1)
    if (index != 0) return index
    if (left.source != right.source) return if (left.source < right.source) -1 else 1
    return cmp(left.id, right.id)
}

private fun cmp(left: String, right: String): Int = when {
    left < right -> -1
    left > right -> 1
    else -> 0
}

private fun episodeRank(episode: EpisodeState): Long {
    if (episode.season == null) return -1
    return episode.season * 1_000_000 + (episode.episode ?: 0)
}

private fun maxRecord(marks: List<MarkRecord>): MarkRecord? {
    var best: MarkRecord? = null
    for (mark in marks) {
        if (best == null || compareParsed(wrap(mark), wrap(best)) > 0) best = mark
    }
    return best
}

private fun maxDetection(detections: List<DetectionRecord>): DetectionRecord? {
    var best: DetectionRecord? = null
    for (detection in detections) {
        if (best == null || compareDetections(detection, best) > 0) best = detection
    }
    return best
}

private fun compareDetections(left: DetectionRecord, right: DetectionRecord): Int {
    if (left.algorithmVersion != right.algorithmVersion) return if (left.algorithmVersion > right.algorithmVersion) 1 else -1
    return compareParsed(wrap(left), wrap(right))
}

private fun wrap(record: SyncRecord): ParsedRecord {
    val parsed = parseRecord(record.toWire()) ?: error("internal record failed to parse")
    return parsed
}

private fun MergedState.toJson(): JsonValue = jObj(
    "rejected" to jNum(rejected.toLong()),
    "shows" to JsonValue.Arr(shows.map { it.toJson() }),
    "submissions" to JsonValue.Arr(submissions.map { it.toJson() }),
    "tombstones" to JsonValue.Arr(tombstones.map { it.toJson() }),
)

private fun ShowState.toJson(): JsonValue = jObj(
    "aliases" to JsonValue.Arr(aliases.map { jStr(it) }),
    "episodes" to JsonValue.Arr(episodes.map { it.toJson() }),
    "showId" to jStr(showId),
)

private fun EpisodeState.toJson(): JsonValue = jObj(
    "detections" to JsonValue.Arr(detections.map { it.toJson() }),
    "effective" to JsonValue.Arr(effective.map { it.toJson() }),
    "episode" to jsonLong(episode),
    "episodeKey" to jStr(episodeKey),
    "marks" to JsonValue.Arr(marks.map { it.toJson() }),
    "season" to jsonLong(season),
    "userNoneTypes" to JsonValue.Arr(userNoneTypes.map { jStr(it) }),
)

private fun MarkView.toJson(): JsonValue = jObj(
    "applied" to jBool(applied),
    "durationMs" to jNum(durationMs),
    "endMs" to jsonLong(endMs),
    "hlc" to hlc.toJson(),
    "id" to jStr(id),
    "presence" to jStr(presence),
    "segmentIndex" to jsonInt(segmentIndex),
    "segmentType" to jStr(segmentType),
    "startMs" to jsonLong(startMs),
    "suppressedBy" to jsonText(suppressedBy),
)

private fun DetectionView.toJson(): JsonValue = jObj(
    "algorithm" to jStr(algorithm),
    "algorithmVersion" to jNum(algorithmVersion.toLong()),
    "applied" to jBool(applied),
    "durationMs" to jNum(durationMs),
    "endMs" to jsonLong(endMs),
    "hlc" to hlc.toJson(),
    "id" to jStr(id),
    "outcome" to jStr(outcome),
    "releaseId" to jsonText(releaseId),
    "segmentIndex" to jNum(segmentIndex.toLong()),
    "segmentType" to jStr(segmentType),
    "startMs" to jsonLong(startMs),
    "suppressedBy" to jsonText(suppressedBy),
)

private fun EffectiveSegment.toJson(): JsonValue = jObj(
    "algorithm" to jsonText(algorithm),
    "durationMs" to jNum(durationMs),
    "endMs" to jsonLong(endMs),
    "id" to jStr(id),
    "presence" to jStr(presence),
    "segmentIndex" to jsonInt(segmentIndex),
    "segmentType" to jStr(segmentType),
    "source" to jStr(source),
    "startMs" to jsonLong(startMs),
)

private fun SubmissionState.toJson(): JsonValue = jObj(
    "accountScope" to jStr(accountScope),
    "durationMs" to jNum(durationMs),
    "endMs" to jNum(endMs),
    "episode" to jsonLong(episode),
    "hlc" to hlc.toJson(),
    "id" to jStr(id),
    "mediaType" to jStr(mediaType),
    "remoteId" to jStr(remoteId),
    "season" to jsonLong(season),
    "segmentType" to jStr(segmentType),
    "showId" to jStr(showId),
    "startMs" to jNum(startMs),
    "status" to jStr(status),
)

private fun TombstoneState.toJson(): JsonValue = jObj(
    "hlc" to hlc.toJson(),
    "id" to jStr(id),
    "kind" to jStr(kind),
    "purgeAfterMs" to jNum(purgeAfterMs),
)

private fun Hlc.toJson(): JsonValue = jObj(
    "counter" to jNum(counter.toLong()),
    "deviceId" to jStr(deviceId),
    "wallMs" to jNum(wallMs),
)

private fun jsonLong(value: Long?): JsonValue = if (value == null) jNull() else jNum(value)
private fun jsonInt(value: Int?): JsonValue = if (value == null) jNull() else jNum(value.toLong())
private fun jsonText(value: String?): JsonValue = if (value == null) jNull() else jStr(value)
