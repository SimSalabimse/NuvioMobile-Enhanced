package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.serialization.LenientDefaultsJson
import com.nuvio.app.features.downloads.DownloadDiskInfo
import com.nuvio.app.features.downloads.PlaybackPriority
import com.nuvio.app.features.player.sameCut
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Print format version. Readers that see another version stop. See docs/prints/PRINT_FORMAT_v1.md. */
internal const val PRINT_FORMAT_VERSION = 1

internal const val PRINT_ALGO = "nuvio-print-1"
internal const val PRINT_STEP_MS = 100
internal const val PRINT_PAD_MS = 5_000L
internal const val MAX_PRINT_FRAMES_PER_PART = 1_200
internal const val MAX_SYNC_PLAINTEXT_BYTES = 8_192
internal const val MAX_LEARNED_RANGES_PER_TYPE = 3

/** Stored prints of one segment type on one show. Older episodes are dropped first. Marks stay. */
internal const val MAX_PRINTS_PER_TYPE = 16

internal const val DETECTED_FROM_YOUR_MARKS = "Detected from your marks"

private const val MIN_PRINT_MARK_MS = 8_000L
private const val MIN_OFFERED_MS = 5_000L
private const val MAX_INTRO_MS = 300_000L
private const val MAX_RECAP_MS = 1_200_000L
private const val MAX_TAIL_SEGMENT_MS = 1_800_000L
private const val CREDITS_END_SNAP_MS = 10_000L

private const val CATALOG_VERSION = 1

/**
 * Why matching stored show prints must not write a detection.
 * A mark still keeps its own print. Comparing stored prints does not decode a file.
 */
internal data class ShowPrintGate(
    val videoPlaying: Boolean = false,
    val thermal: AnalysisThermal = AnalysisThermal.Nominal,
    val lowPowerMode: Boolean = false,
    val lowBattery: Boolean = false,
    val transferActive: Boolean = false,
    val warmOverride: Boolean = false,
    val onlyWhileCharging: Boolean = false,
    val charging: Boolean = true,
    val freeBytes: Long? = null,
    /** The next-episode pass. A playing video does not block it. The queue path does. */
    val playbackPass: Boolean = false,
) {
    companion object {
        val Open = ShowPrintGate()
    }
}

internal fun showPrintGateBlocks(gate: ShowPrintGate): Boolean {
    if (gate.playbackPass) {
        val hold = nextEpisodeAnalysisHold(
            NextEpisodeAnalysisConditions(
                videoPlaying = gate.videoPlaying,
                freeBytes = gate.freeBytes,
                thermal = gate.thermal,
                lowPowerMode = gate.lowPowerMode,
                lowBattery = gate.lowBattery,
            ),
        )
        return hold != NextEpisodeAnalysisHold.None
    }
    if (effectiveAnalysisPauseReason(
            videoPlaying = gate.videoPlaying,
            thermal = gate.thermal,
            lowPowerMode = gate.lowPowerMode,
            transferActive = gate.transferActive,
            warmOverride = gate.warmOverride,
        ) != null
    ) {
        return true
    }
    if (gate.onlyWhileCharging && !gate.charging) return true
    if (gate.freeBytes != null && gate.freeBytes < ANALYSIS_FREE_SPACE_BRAKE_BYTES) return true
    return false
}

internal fun analysisRowLearnedLine(result: MediaAnalysisResult?): String? =
    if (result != null && result.learnedRanges.isNotEmpty()) DETECTED_FROM_YOUR_MARKS else null

/** One marked window, plus a short pad, kept after the download is gone. */
@Serializable
internal data class StoredShowPrint(
    val showId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val segmentType: String,
    val markId: String,
    val startMs: Long,
    val endMs: Long,
    val originMs: Long,
    val durationMs: Long,
    val stepMs: Int = PRINT_STEP_MS,
    val padMs: Long = PRINT_PAD_MS,
    val frames: String,
    val sourceKey: String,
) {
    /** Unique per episode. Two episodes of one show both use the mark id `intro`. */
    val printId: String get() = "${printEpisodeKey(season, episode)}/$segmentType/$markId"
}

@Serializable
private data class ShowPrintCatalog(
    val version: Int = CATALOG_VERSION,
    val prints: List<StoredShowPrint> = emptyList(),
)

private data class PrintHit(
    val type: String,
    val startMs: Long,
    val endMs: Long,
    val printId: String,
    val sourceKey: String,
    val overlapFrames: Int,
    val bitErrors: Double,
    val sameSeason: Boolean,
)

/**
 * Learns a compact print from each of your marks and matches it on later episodes of the show.
 * It does not submit anything to TheIntroDB. [SegmentGuess] is the unused stage-2 seam.
 */
internal object ShowPrints {
    private val json = LenientDefaultsJson
    private val catalogLock = SynchronizedObject()

    fun liveQueueGate(): ShowPrintGate {
        val power = AnalysisPower.snapshot()
        val free = runCatching { DownloadDiskInfo.volumeSpace()?.availableBytes }.getOrNull()
        return ShowPrintGate(
            videoPlaying = PlaybackPriority.isWatching(),
            thermal = power.thermal,
            lowPowerMode = power.lowPowerMode,
            lowBattery = power.lowBattery,
            transferActive = PlaybackPriority.transfersActiveNow(),
            warmOverride = AnalysisWarmOverride.armed,
            onlyWhileCharging = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.OnlyWhileCharging),
            charging = MediaAnalyzerPlatform.isCharging(),
            freeBytes = free,
            playbackPass = false,
        )
    }

    fun forPlayback(conditions: NextEpisodeAnalysisConditions): ShowPrintGate = ShowPrintGate(
        videoPlaying = conditions.videoPlaying,
        thermal = conditions.thermal,
        lowPowerMode = conditions.lowPowerMode,
        lowBattery = conditions.lowBattery,
        freeBytes = conditions.freeBytes,
        playbackPass = true,
    )

    /**
     * Stores prints for the marks on [episodeKey]. When [reteach] is set and [gate] is open,
     * matches those prints onto the other episodes. A closed gate still keeps the print.
     * Returns the episode after matching, or null when it was not loaded.
     */
    fun syncEpisode(
        parentMetaId: String,
        episodeKey: String,
        reteach: Boolean,
        gate: ShowPrintGate,
        shouldStop: () -> Boolean = { false },
    ): MediaAnalysisResult? {
        if (parentMetaId.isBlank()) return null
        val episode = MediaAnalysisStore.load(episodeKey) ?: return null
        val fresh = extractPrints(parentMetaId, episode)
        val previous = printsFor(parentMetaId).filter { it.sourceKey == episodeKey }
        val dropped = writeEpisodePrints(parentMetaId, episodeKey, fresh)
        val changed = previous.filter { old ->
            val next = fresh.find { it.printId == old.printId }
            next == null || next.frames != old.frames || next.startMs != old.startMs || next.endMs != old.endMs
        }.map { it.printId } + dropped
        changed.distinct().forEach { retract(parentMetaId, it) }
        if (!reteach || showPrintGateBlocks(gate) || shouldStop()) return MediaAnalysisStore.load(episodeKey)
        teach(parentMetaId, gate, shouldStop)
        return MediaAnalysisStore.load(episodeKey)
    }

    /** Capture prints from [episode], then match the show when [gate] is open. */
    fun finishEpisode(
        parentMetaId: String,
        episode: MediaAnalysisResult,
        gate: ShowPrintGate,
        shouldStop: () -> Boolean = { false },
    ): MediaAnalysisResult {
        if (parentMetaId.isBlank()) return episode
        val fresh = extractPrints(parentMetaId, episode)
        val previous = printsFor(parentMetaId).filter { it.sourceKey == episode.key }
        val dropped = writeEpisodePrints(parentMetaId, episode.key, fresh)
        val changed = previous.filter { old ->
            val next = fresh.find { it.printId == old.printId }
            next == null || next.frames != old.frames || next.startMs != old.startMs || next.endMs != old.endMs
        }.map { it.printId } + dropped
        changed.distinct().forEach { retract(parentMetaId, it) }
        if (showPrintGateBlocks(gate) || shouldStop()) return episode
        val prints = printsFor(parentMetaId)
        if (prints.isEmpty() && !SegmentGuess.ENABLED) return episode
        teachOthers(parentMetaId, episode.key, prints, shouldStop)
        return applyHits(episode, prints, shouldStop) ?: episode
    }

    fun teach(parentMetaId: String, gate: ShowPrintGate, shouldStop: () -> Boolean = { false }): Int {
        if (parentMetaId.isBlank() || showPrintGateBlocks(gate) || shouldStop()) return 0
        val prints = printsFor(parentMetaId)
        if (prints.isEmpty() && !SegmentGuess.ENABLED) return 0
        var changed = 0
        for (episode in showEpisodes(parentMetaId)) {
            if (shouldStop()) return changed
            val learned = applyHits(episode, prints, shouldStop) ?: return changed
            if (learned == episode) continue
            val saved = MediaAnalysisStore.update(episode.key) { current ->
                if (current == episode) learned else current
            }
            if (saved == learned) changed += 1
        }
        if (changed > 0) InAppLogger.info("ShowPrint", "matched $changed episode(s)")
        return changed
    }

    fun exportWires(parentMetaId: String, clock: ShowPrintClock): List<ShowPrintWire> =
        printsFor(parentMetaId).flatMap { it.toWireParts(clock) }

    /** Replaces this show's prints with [wires] and drops detections whose print is gone. */
    fun installWires(parentMetaId: String, wires: List<ShowPrintWire>) {
        if (parentMetaId.isBlank()) return
        val incomingShow = syncShowId(parentMetaId) ?: return
        val joined = joinWires(parentMetaId, wires.filter { it.showId == incomingShow })
        val (bounded, dropped) = boundPrints(joined)
        val previous = printsFor(parentMetaId).map { it.printId }.toSet()
        synchronized(catalogLock) {
            saveCatalog(parentMetaId, ShowPrintCatalog(prints = bounded))
        }
        val next = bounded.map { it.printId }.toSet()
        (previous.filter { it !in next } + dropped.map { it.printId }).distinct().forEach { retract(parentMetaId, it) }
    }

    fun clearPrintsForTests(parentMetaId: String) {
        synchronized(catalogLock) {
            MediaAnalysisFiles.delete(catalogFile(parentMetaId))
        }
    }

    private fun teachOthers(
        parentMetaId: String,
        exceptKey: String,
        prints: List<StoredShowPrint>,
        shouldStop: () -> Boolean,
    ) {
        for (episode in showEpisodes(parentMetaId)) {
            if (shouldStop()) return
            if (episode.key == exceptKey) continue
            val learned = applyHits(episode, prints, shouldStop) ?: return
            if (learned == episode) continue
            MediaAnalysisStore.update(episode.key) { current -> if (current == episode) learned else current }
        }
    }

    /** Prints removed because the show is over the per-type cap. The caller's marks are not touched. */
    private fun writeEpisodePrints(parentMetaId: String, episodeKey: String, fresh: List<StoredShowPrint>): List<String> {
        val dropped = synchronized(catalogLock) {
            val catalog = readCatalog(parentMetaId)
            val kept = catalog.prints.filter { it.sourceKey != episodeKey }
            val (bounded, removed) = boundPrints(kept + fresh)
            saveCatalog(parentMetaId, catalog.copy(prints = bounded))
            removed.map { it.printId }
        }
        if (fresh.isNotEmpty()) {
            InAppLogger.info("ShowPrint", "stored ${fresh.size} print(s) key=$episodeKey")
        }
        return dropped
    }

    private fun printsFor(parentMetaId: String): List<StoredShowPrint> =
        synchronized(catalogLock) { readCatalog(parentMetaId).prints }

    private fun readCatalog(parentMetaId: String): ShowPrintCatalog {
        val text = MediaAnalysisFiles.read(catalogFile(parentMetaId)) ?: return ShowPrintCatalog()
        return runCatching { json.decodeFromString(ShowPrintCatalog.serializer(), text) }.getOrNull()
            ?.takeIf { it.version == CATALOG_VERSION }
            ?: ShowPrintCatalog()
    }

    private fun saveCatalog(parentMetaId: String, catalog: ShowPrintCatalog) {
        if (catalog.prints.isEmpty()) {
            MediaAnalysisFiles.delete(catalogFile(parentMetaId))
            return
        }
        MediaAnalysisFiles.write(catalogFile(parentMetaId), json.encodeToString(ShowPrintCatalog.serializer(), catalog))
    }

    private fun catalogFile(parentMetaId: String): String {
        val show = MediaAnalysisStore.keyFor(parentMetaId, null, null).removeSuffix("__movie")
        return "show-prints-$show"
    }

    private fun showEpisodes(parentMetaId: String): List<MediaAnalysisResult> {
        val show = MediaAnalysisStore.keyFor(parentMetaId, null, null).removeSuffix("__movie")
        if (show.isEmpty()) return emptyList()
        return MediaAnalysisFiles.list("${show}__").mapNotNull { MediaAnalysisStore.load(it) }
    }

    private fun retract(parentMetaId: String, printId: String) {
        for (episode in showEpisodes(parentMetaId)) {
            if (episode.learnedRanges.none { it.printId == printId }) continue
            MediaAnalysisStore.update(episode.key) { current ->
                if (current == null || current.learnedRanges.none { it.printId == printId }) current
                else withoutPrint(current, printId)
            }
        }
    }
}

private fun extractPrints(parentMetaId: String, episode: MediaAnalysisResult): List<StoredShowPrint> {
    val season = seasonNumber(episode.key)
    val episodeNumber = episodeNumber(episode.key)
    return episode.storedUserMarks().mapNotNull { mark ->
        val segment = episode.userSegments.entries.firstOrNull { (key, stored) ->
            stored.id.ifBlank { key } == mark.id
        }?.value ?: return@mapNotNull null
        if (!segment.canTeach) return@mapNotNull null
        extractPrint(
            parentMetaId = parentMetaId,
            episode = episode,
            type = mark.type,
            markId = mark.id,
            startMs = mark.startMs,
            endMs = mark.endMs,
            durationMs = segment.durationMs.takeIf { it > 0L } ?: episode.durationMs,
            season = season,
            episodeNumber = episodeNumber,
        )
    }
}

private fun extractPrint(
    parentMetaId: String,
    episode: MediaAnalysisResult,
    type: String,
    markId: String,
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    season: Int?,
    episodeNumber: Int?,
): StoredShowPrint? {
    if (wireSegmentType(type) == null) return null
    if (episode.fingerprintStepMs != PRINT_STEP_MS) return null
    if (endMs - startMs < MIN_PRINT_MARK_MS) return null
    if (!lengthAllowed(type, startMs, endMs)) return null
    val head = type == "intro" || type == "recap"
    val frames = decodeFingerprint(if (head) episode.headFingerprint else episode.tailFingerprint)
    if (frames.isEmpty()) return null
    val step = PRINT_STEP_MS.toLong()
    val offset = if (head) 0L else episode.tailStartMs
    val windowEnd = offset + frames.size * step
    val markFrom = ((startMs - offset) / step).toInt()
    val markTo = ((endMs - offset) / step).toInt()
    if (markFrom < 0 || markTo > frames.size || markTo - markFrom < (MIN_PRINT_MARK_MS / step).toInt()) return null
    val paddedStart = (startMs - PRINT_PAD_MS).coerceAtLeast(offset)
    val paddedEnd = (endMs + PRINT_PAD_MS).coerceAtMost(windowEnd)
    val from = ((paddedStart - offset) / step).toInt().coerceIn(0, frames.size)
    val to = ((paddedEnd - offset) / step).toInt().coerceIn(from, frames.size)
    if (to <= from) return null
    return StoredShowPrint(
        showId = parentMetaId,
        season = season,
        episode = episodeNumber,
        segmentType = type,
        markId = markId,
        startMs = startMs,
        endMs = endMs,
        originMs = offset + from * step,
        durationMs = durationMs,
        stepMs = PRINT_STEP_MS,
        padMs = PRINT_PAD_MS,
        frames = encodeFingerprint(frames.copyOfRange(from, to)),
        sourceKey = episode.key,
    )
}

/** Null when matching was cancelled. The episode is left as it was. */
private fun applyHits(
    episode: MediaAnalysisResult,
    prints: List<StoredShowPrint>,
    shouldStop: () -> Boolean,
): MediaAnalysisResult? {
    val guessed = SegmentGuess.rangesFor(episode)
    val hits = try {
        chooseHits(episode, prints, shouldStop)
    } catch (_: ReferenceStop) {
        return null
    }
    if (hits.isEmpty() && guessed.isEmpty() && episode.learnedRanges.isEmpty()) return episode
    return writeHits(episode, hits)
}

private fun chooseHits(
    episode: MediaAnalysisResult,
    prints: List<StoredShowPrint>,
    shouldStop: () -> Boolean,
): List<PrintHit> {
    val found = prints.mapNotNull { matchPrint(it, episode, shouldStop) }
    val sorted = found.sortedWith(
        compareByDescending<PrintHit> { it.overlapFrames }
            .thenBy { it.bitErrors }
            .thenByDescending { it.sameSeason }
            .thenBy { it.printId }
            .thenBy { it.sourceKey },
    )
    val kept = mutableListOf<PrintHit>()
    for (hit in sorted) {
        val ofType = kept.count { it.type == hit.type }
        if (ofType >= MAX_LEARNED_RANGES_PER_TYPE) continue
        val near = kept.any { it.type == hit.type && abs(it.startMs - hit.startMs) <= SEGMENT_AGREE_MS }
        if (near) continue
        kept += hit
    }
    return kept
}

private fun matchPrint(
    print: StoredShowPrint,
    episode: MediaAnalysisResult,
    shouldStop: () -> Boolean,
): PrintHit? {
    if (shouldStop()) throw ReferenceStop()
    if (print.sourceKey == episode.key) return null
    if (print.stepMs != episode.fingerprintStepMs) return null
    if (!sameCut(print.durationMs, episode.durationMs)) return null
    val type = print.segmentType
    if (episode.userSegments.containsKey(type) || type in episode.chapterTypes) return null
    if (analysisOwns(episode, type)) return null
    val markLength = print.endMs - print.startMs
    if (markLength < MIN_PRINT_MARK_MS || !lengthAllowed(type, print.startMs, print.endMs)) return null
    val head = type == "intro" || type == "recap"
    val targetFrames = decodeFingerprint(if (head) episode.headFingerprint else episode.tailFingerprint)
    val printFrames = decodeFingerprint(print.frames)
    if (targetFrames.isEmpty() || printFrames.isEmpty()) return null
    val step = print.stepMs.toLong()
    val markStartFrame = ((print.startMs - print.originMs) / step).toInt()
    val markEndFrame = ((print.endMs - print.originMs) / step).toInt()
    val markFrames = markEndFrame - markStartFrame
    if (markStartFrame < 0 || markEndFrame > printFrames.size || markFrames < (MIN_PRINT_MARK_MS / step).toInt()) return null
    if (!markIsDistinctive(printFrames, markStartFrame, markEndFrame)) return null
    // The shared run has to cover the mark itself. A bumper or a quiet stretch is not the segment.
    val needed = markFrames
    val segment = FingerprintMatcher.longestSharedSegment(
        printFrames,
        targetFrames,
        minFrames = needed,
        shouldStop = shouldStop,
    ) ?: return null
    val overlap = min(markEndFrame, segment.endA) - max(markStartFrame, segment.startA)
    if (overlap < needed) return null
    val align = segment.startB - segment.startA
    val targetOffset = if (head) 0L else episode.tailStartMs
    val start = targetOffset + (markStartFrame + align) * step
    var end = targetOffset + (markEndFrame + align) * step
    if (start < targetOffset || end <= start) return null
    val capturedEnd = targetOffset + targetFrames.size * step
    if (end > capturedEnd) return null
    if (episode.durationMs > 0L && end > episode.durationMs) return null
    if (type == "outro" && episode.durationMs > 0L && episode.durationMs - end in 1L until CREDITS_END_SNAP_MS) {
        end = episode.durationMs
    }
    if (!lengthAllowed(type, start, end)) return null
    if (episode.rejectedSegments.covers(type, start, end)) return null
    val targetSeason = seasonNumber(episode.key)
    return PrintHit(
        type = type,
        startMs = start,
        endMs = end,
        printId = print.printId,
        sourceKey = print.sourceKey,
        overlapFrames = overlap,
        bitErrors = segment.avgBitErrors,
        sameSeason = print.season == targetSeason,
    )
}

private fun writeHits(episode: MediaAnalysisResult, hits: List<PrintHit>): MediaAnalysisResult {
    var next = episode
    for (type in listOf("intro", "recap", "outro", "preview")) {
        if (episode.userSegments.containsKey(type) || type in episode.chapterTypes) continue
        val best = hits.firstOrNull { it.type == type }
        if (best != null) {
            next = next.withPrimary(type, best.startMs, best.endMs)
            continue
        }
        if (type !in episode.userSegments && type !in episode.chapterTypes && printWrotePrimary(episode, type)) {
            next = next.withPrimary(type, null, null)
        }
    }
    val ranges = hits.map { LearnedRange(it.type, it.startMs, it.endMs, it.printId) }
    var types = episode.learnedTypes
    for (type in listOf("intro", "recap", "outro", "preview")) {
        val now = ranges.any { it.type == type }
        val before = episode.learnedRanges.any { it.type == type }
        if (now) types = types + type
        else if (before) types = types - type
    }
    next = next.copy(learnedRanges = ranges, learnedTypes = types)
    return if (next == episode) episode else next
}

private fun withoutPrint(episode: MediaAnalysisResult, printId: String): MediaAnalysisResult {
    val removed = episode.learnedRanges.filter { it.printId == printId }
    if (removed.isEmpty()) return episode
    var next = episode.copy(learnedRanges = episode.learnedRanges.filter { it.printId != printId })
    for (range in removed) {
        if (next.learnedRanges.any { it.type == range.type }) continue
        if (range.type in next.chapterTypes || next.userSegments.containsKey(range.type)) continue
        if (range.type == "intro" && next.introConfidence != null) continue
        if (range.type == "outro" && next.creditsConfidence != null) continue
        val primary = primaryRange(next, range.type) ?: continue
        if (abs(primary.first - range.startMs) > SEGMENT_AGREE_MS) continue
        next = next.withPrimary(range.type, null, null)
        next = next.copy(learnedTypes = next.learnedTypes - range.type)
    }
    return next
}

private fun MediaAnalysisResult.withPrimary(type: String, startMs: Long?, endMs: Long?): MediaAnalysisResult {
    val current = primaryRange(this, type)
    if (current?.first == startMs && current?.second == endMs) return this
    return when (type) {
        "intro" -> copy(
            introStartMs = startMs,
            introEndMs = endMs,
            introConfidence = null,
            derivedVersion = 0,
        )
        "outro" -> copy(creditsStartMs = startMs, creditsEndMs = endMs, creditsConfidence = null)
        "recap" -> copy(recapStartMs = startMs, recapEndMs = endMs)
        "preview" -> copy(previewStartMs = startMs, previewEndMs = endMs)
        else -> this
    }
}

/**
 * A stored time the print must not replace: a user mark, a chapter, a scored detector range,
 * or a recap/preview that analysis already stored.
 * Intro and credits with no confidence score are mark-derived (season learning or a print).
 * A print may record those, so deleting the mark can take the detection with it.
 */
private fun analysisOwns(episode: MediaAnalysisResult, type: String): Boolean {
    if (type in episode.userSegments || type in episode.chapterTypes) return true
    if (primaryRange(episode, type) == null) return false
    if (printWrotePrimary(episode, type)) return false
    return when (type) {
        "intro" -> episode.introConfidence != null
        "outro" -> episode.creditsConfidence != null
        else -> true
    }
}

/** True when the stored time is the one a print wrote, so a later pass may move or clear it. */
private fun printWrotePrimary(episode: MediaAnalysisResult, type: String): Boolean {
    val primary = primaryRange(episode, type) ?: return false
    val near = episode.learnedRanges.any { it.type == type && abs(it.startMs - primary.first) <= SEGMENT_AGREE_MS }
    if (!near) return false
    return when (type) {
        "intro" -> episode.introConfidence == null
        "outro" -> episode.creditsConfidence == null
        else -> true
    }
}

/**
 * Drops print-written times so the detector can run. A user mark, a chapter, and a scored
 * analysis range stay. [restoringUnfilledPrintTimes] puts a print back when detection finds nothing.
 */
internal fun MediaAnalysisResult.withoutPrintOwnedTimes(): MediaAnalysisResult {
    if (learnedRanges.isEmpty()) return this
    val types = learnedRanges.map { it.type }.toSet()
    var next = copy(learnedTypes = learnedTypes - types, learnedRanges = emptyList())
    for (type in types) {
        if (!printWrotePrimary(this, type)) continue
        if (type in userSegments || type in chapterTypes) continue
        next = next.withPrimary(type, null, null)
    }
    return next
}

/** Puts a print time back for a type detection and chapters left empty. */
internal fun MediaAnalysisResult.restoringUnfilledPrintTimes(previous: MediaAnalysisResult?): MediaAnalysisResult {
    if (previous == null || previous.key != key) return this
    if (previous.fileName != null && fileName != null && previous.fileName != fileName) return this
    var next = this
    val restored = ArrayList<LearnedRange>()
    for (range in previous.learnedRanges) {
        if (range.type in next.userSegments || range.type in next.chapterTypes) continue
        if (primaryRange(next, range.type) != null) continue
        val old = primaryRange(previous, range.type) ?: continue
        if (abs(old.first - range.startMs) > SEGMENT_AGREE_MS) continue
        if (!printWrotePrimary(previous, range.type)) continue
        next = next.withPrimary(range.type, old.first, old.second)
        restored += range
    }
    if (restored.isEmpty() && learnedRanges == previous.learnedRanges) return next
    val kept = learnedRanges.filter { current -> restored.none { it.type == current.type } } + restored
    return next.copy(
        learnedRanges = kept,
        learnedTypes = learnedTypes + restored.map { it.type },
    )
}

/** Silence and a held tone share one fingerprint. That is not an intro, recap, credits, or preview. */
private fun markIsDistinctive(frames: IntArray, from: Int, until: Int): Boolean {
    if (until - from < 2 || from < 0 || until > frames.size) return false
    val first = frames[from]
    for (index in (from + 1) until until) {
        if ((frames[index] xor first).countOneBits() > 6) return true
    }
    return false
}

private fun boundPrints(prints: List<StoredShowPrint>): Pair<List<StoredShowPrint>, List<StoredShowPrint>> {
    val kept = ArrayList<StoredShowPrint>(prints.size)
    val dropped = ArrayList<StoredShowPrint>()
    for (group in prints.groupBy { it.segmentType }.values) {
        if (group.size <= MAX_PRINTS_PER_TYPE) {
            kept += group
            continue
        }
        val ranked = group.sortedWith(
            compareByDescending<StoredShowPrint> { it.season ?: -1 }
                .thenByDescending { it.episode ?: -1 }
                .thenBy { it.markId },
        )
        kept += ranked.take(MAX_PRINTS_PER_TYPE)
        dropped += ranked.drop(MAX_PRINTS_PER_TYPE)
    }
    return kept to dropped
}

internal fun printEpisodeKey(season: Int?, episode: Int?): String =
    if (season != null && episode != null) "s${season}e${episode}" else "movie"

private fun primaryRange(episode: MediaAnalysisResult, type: String): Pair<Long, Long>? = when (type) {
    "intro" -> pairOrNull(episode.introStartMs, episode.introEndMs)
    "outro" -> pairOrNull(episode.creditsStartMs, episode.creditsEndMs)
    "recap" -> pairOrNull(episode.recapStartMs, episode.recapEndMs)
    "preview" -> pairOrNull(episode.previewStartMs, episode.previewEndMs)
    else -> null
}

private fun pairOrNull(start: Long?, end: Long?): Pair<Long, Long>? =
    if (start != null && end != null) start to end else null

private fun lengthAllowed(type: String, startMs: Long, endMs: Long): Boolean {
    val length = endMs - startMs
    if (length < MIN_OFFERED_MS) return false
    val max = when (type) {
        "intro" -> MAX_INTRO_MS
        "recap" -> MAX_RECAP_MS
        "outro", "preview" -> MAX_TAIL_SEGMENT_MS
        else -> return false
    }
    return length <= max
}

private fun seasonNumber(key: String): Int? =
    Regex("""__s(\d+)e(\d+)$""").find(key)?.groupValues?.get(1)?.toInt()

private fun episodeNumber(key: String): Int? =
    Regex("""__s(\d+)e(\d+)$""").find(key)?.groupValues?.get(2)?.toInt()

private fun joinWires(parentMetaId: String, wires: List<ShowPrintWire>): List<StoredShowPrint> {
    val groups = wires.groupBy { "${it.showId}/${it.season}/${it.episode}/${it.segmentType}/${it.markId}" }
    val out = ArrayList<StoredShowPrint>(groups.size)
    for (group in groups.values) {
        val first = group.first()
        if (group.any { it.parts != first.parts || it.segmentType != first.segmentType || it.markId != first.markId }) continue
        val parts = group.distinctBy { it.part }.sortedBy { it.part }
        if (parts.size != first.parts || parts.map { it.part } != (0 until first.parts).toList()) continue
        val frames = parts.flatMap { decodeFingerprint(it.frames).asList() }.toIntArray()
        if (frames.isEmpty()) continue
        val localType = localSegmentType(first.segmentType) ?: continue
        val localMark = localMarkId(first.segmentType, first.markId)
        val sourceKey = if (first.season != null && first.episode != null) {
            MediaAnalysisStore.keyFor(parentMetaId, first.season, first.episode)
        } else {
            MediaAnalysisStore.keyFor(parentMetaId, null, null)
        }
        out += StoredShowPrint(
            showId = parentMetaId,
            season = first.season,
            episode = first.episode,
            segmentType = localType,
            markId = localMark,
            startMs = first.startMs,
            endMs = first.endMs,
            originMs = first.originMs,
            durationMs = first.durationMs,
            stepMs = first.stepMs,
            padMs = first.padMs,
            frames = encodeFingerprint(frames),
            sourceKey = sourceKey,
        )
    }
    return out
}

private fun localMarkId(wireType: String, wireMarkId: String): String {
    val local = localSegmentType(wireType) ?: return wireMarkId
    if (wireMarkId == wireType) return local
    if (wireMarkId.startsWith("$wireType-")) return local + wireMarkId.removePrefix(wireType)
    return wireMarkId
}
