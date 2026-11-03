package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Learns from the user's own skip marks. A mark (from Submit Timestamps or the Good / Wrong prompt
 * after Skip) is stored on the episode and always wins there. On an episode with fingerprints, an
 * intro or credits mark is also a trusted reference for its season: its fingerprint window is
 * matched against the other episodes (also ones where nothing was found before), and new episodes
 * are matched against it when they are analysed.
 */
/**
 * The range a saved correction moved off, or null when nothing moved.
 * A Wrong tap does not call this: nothing is stored until a different range is saved.
 */
internal fun rejectedRangeForCorrection(
    category: String,
    previousStartMs: Long?,
    previousEndMs: Long?,
    savedStartMs: Long,
    savedEndMs: Long,
): RejectedSegment? {
    if (category != "intro" && category != "outro" && category != "recap") return null
    if (previousStartMs == null || previousEndMs == null) return null
    if (previousStartMs == savedStartMs && previousEndMs == savedEndMs) return null
    return RejectedSegment(category, previousStartMs, previousEndMs)
}

internal object SkipLearning {
    /** Provider name of skip intervals that come from the user's own marks. */
    const val PROVIDER = "you"
    /** A mark imported from the user's own TheIntroDB submissions. */
    const val PROVIDER_IMPORTED = "you-theintrodb"

    fun isUserProvider(provider: String): Boolean = provider == PROVIDER || provider == PROVIDER_IMPORTED

    private const val MIN_REFERENCE_MS = 8_000L
    private const val END_SNAP_MS = 10_000L

    /** intro / recap / outro / preview, or null for types that cannot be marked. */
    fun canonicalType(type: String): String? = when (type.lowercase()) {
        "intro", "op", "mixed-op", "opening" -> "intro"
        "recap" -> "recap"
        "outro", "ed", "mixed-ed", "credits", "ending", "movie-credits" -> "outro"
        "preview" -> "preview"
        else -> null
    }

    fun userMarks(parentMetaId: String, season: Int?, episode: Int?): Map<String, UserSegment> {
        if (parentMetaId.isBlank()) return emptyMap()
        return MediaAnalysisStore.load(MediaAnalysisStore.keyFor(parentMetaId, season, episode))?.userSegments.orEmpty()
    }

    /**
     * Saves a mark for one episode and re-matches the rest of its season against it.
     * Returns how many other episodes got new times from it.
     */
    suspend fun recordMark(
        parentMetaId: String,
        season: Int?,
        episode: Int?,
        type: String,
        startMs: Long,
        endMs: Long,
        durationMs: Long = 0L,
    ): Int {
        val category = canonicalType(type) ?: return 0
        if (parentMetaId.isBlank() || endMs <= startMs || startMs < 0L) return 0
        val key = MediaAnalysisStore.keyFor(parentMetaId, season, episode)
        val existing = MediaAnalysisStore.load(key)
            ?: MediaAnalysisResult(key = key, analyzedAtMs = 0L, durationMs = 0L)
        val previous = when (category) {
            "intro" -> existing.introStartMs to existing.introEndMs
            "outro" -> existing.creditsStartMs to existing.creditsEndMs
            "recap" -> existing.recapStartMs to existing.recapEndMs
            else -> null to null
        }
        val rejection = rejectedRangeForCorrection(category, previous.first, previous.second, startMs, endMs)
        val rejected = if (rejection == null || rejection in existing.rejectedSegments) {
            existing.rejectedSegments
        } else {
            existing.rejectedSegments + rejection
        }
        val updated = existing.copy(
            userSegments = existing.userSegments +
                (category to UserSegment(startMs, endMs, com.nuvio.app.features.streams.epochMs(), durationMs = durationMs)),
            learnedTypes = existing.learnedTypes - category,
            rejectedSegments = rejected,
            introConfidence = if (category == "intro") null else existing.introConfidence,
            creditsConfidence = if (category == "outro") null else existing.creditsConfidence,
            chapterTypes = existing.chapterTypes - category,
            // A new intro bounds the recap search, so run it again.
            derivedVersion = if (category == "intro") 0 else existing.derivedVersion,
        )
        MediaAnalysisStore.save(updated)
        InAppLogger.info("SkipLearning", "mark key=$key type=$category ${startMs}-${endMs}")
        val taught = if (season != null && episode != null) teachSeason(parentMetaId, season, key) else 0
        MediaAnalysisStatus.resultsChanged()
        return taught
    }

    /**
     * Stores an earlier mark (for example one of the user's TheIntroDB submissions) unless the
     * episode already has a mark of that type. Returns true when it was new.
     */
    fun importMark(
        parentMetaId: String,
        season: Int?,
        episode: Int?,
        type: String,
        startMs: Long,
        endMs: Long,
        durationMs: Long = 0L,
        fromTheIntroDb: Boolean = true,
    ): Boolean {
        val category = canonicalType(type) ?: return false
        if (parentMetaId.isBlank() || endMs <= startMs || startMs < 0L) return false
        val key = MediaAnalysisStore.keyFor(parentMetaId, season, episode)
        var added = false
        MediaAnalysisStore.update(key) { stored ->
            val existing = stored ?: MediaAnalysisResult(key = key, analyzedAtMs = 0L, durationMs = 0L)
            if (existing.userSegments.containsKey(category)) return@update null
            added = true
            existing.copy(
                userSegments = existing.userSegments + (category to UserSegment(startMs, endMs, 0L, imported = fromTheIntroDb, durationMs = durationMs)),
                learnedTypes = existing.learnedTypes - category,
                chapterTypes = existing.chapterTypes - category,
                derivedVersion = if (category == "intro") 0 else existing.derivedVersion,
            )
        }
        return added
    }

    /** Re-matches every other episode of the season against the marks of [referenceKey]. */
    suspend fun teachSeason(parentMetaId: String, season: Int, referenceKey: String): Int {
        val reference = MediaAnalysisStore.load(referenceKey) ?: return 0
        if (reference.headFingerprint.isNullOrEmpty() && reference.tailFingerprint.isNullOrEmpty()) return 0
        if (listOf("intro", "outro").none { reference.userSegments[it]?.canTeach == true }) return 0
        val siblings = MediaAnalysisStore.seasonSiblings(parentMetaId, season, referenceKey)
        if (siblings.isEmpty()) return 0
        val changed = withContext(Dispatchers.Default) {
            siblings.mapNotNull { sibling ->
                val learned = learnFromReferences(sibling, listOf(reference))
                (sibling to learned).takeIf { learned != sibling }
            }
        }
        // Saved only when the episode is unchanged since it was read, so a mark or analysis
        // that landed meanwhile isn't overwritten (it is matched again on the next teach).
        changed.forEach { (before, learned) ->
            MediaAnalysisStore.update(before.key) { current -> if (current == before) learned else current }
        }
        if (changed.isNotEmpty()) {
            InAppLogger.info("SkipLearning", "taught ${changed.size} episode(s) from $referenceKey")
        }
        return changed.size
    }

    /**
     * [episode] with intro/credits taken from the best match against the user-marked windows of
     * [references]. Types the user marked on [episode] itself are left alone.
     */
    fun learnFromReferences(episode: MediaAnalysisResult, references: List<MediaAnalysisResult>): MediaAnalysisResult {
        var out = episode
        for (category in listOf("intro", "outro")) {
            if (episode.userSegments.containsKey(category)) continue
            var best: Triple<Long, Long, Int>? = null
            for (reference in references) {
                if (reference.key == episode.key) continue
                val match = matchReference(reference, category, episode) ?: continue
                if (match.third > (best?.third ?: 0)) best = match
            }
            val found = best ?: continue
            if (episode.rejectedSegments.covers(category, found.first, found.second)) continue
            out = when (category) {
                "intro" -> out.copy(
                    introStartMs = found.first,
                    introEndMs = found.second,
                    introConfidence = if (out.introStartMs != found.first || out.introEndMs != found.second) null else out.introConfidence,
                    derivedVersion = if (out.introStartMs != found.first || out.introEndMs != found.second) 0 else out.derivedVersion,
                )
                else -> out.copy(
                    creditsStartMs = found.first,
                    creditsEndMs = found.second,
                    creditsConfidence = if (out.creditsStartMs != found.first || out.creditsEndMs != found.second) null else out.creditsConfidence,
                )
            }
            out = out.copy(learnedTypes = out.learnedTypes + category)
        }
        return out
    }

    /** Start, end (ms in [target]) and matched frame count of [reference]'s marked window, or null. */
    internal fun matchReference(
        reference: MediaAnalysisResult,
        category: String,
        target: MediaAnalysisResult,
    ): Triple<Long, Long, Int>? {
        val mark = reference.userSegments[category]?.takeIf { it.canTeach } ?: return null
        if (!com.nuvio.app.features.player.sameCut(mark.durationMs, reference.durationMs)) return null
        if (reference.durationMs > 0L && target.durationMs > 0L &&
            !com.nuvio.app.features.player.sameCut(reference.durationMs, target.durationMs)
        ) {
            return null
        }
        if (reference.fingerprintStepMs != target.fingerprintStepMs) return null
        val step = reference.fingerprintStepMs.coerceAtLeast(1).toLong()
        val isIntro = category == "intro"
        val refFrames = decodeFingerprint(if (isIntro) reference.headFingerprint else reference.tailFingerprint)
        val refOffset = if (isIntro) 0L else reference.tailStartMs
        val from = ((mark.startMs - refOffset) / step).toInt().coerceIn(0, refFrames.size)
        val to = ((mark.endMs - refOffset) / step).toInt().coerceIn(0, refFrames.size)
        val minFrames = (MIN_REFERENCE_MS / step).toInt()
        if (to - from < minFrames) return null
        val window = refFrames.copyOfRange(from, to)
        val targetFrames = decodeFingerprint(if (isIntro) target.headFingerprint else target.tailFingerprint)
        if (targetFrames.isEmpty()) return null
        val needed = maxOf(minFrames, window.size / 2)
        val segment = FingerprintMatcher.longestSharedSegment(window, targetFrames, minFrames = needed) ?: return null
        if (segment.length * 2 < window.size) return null
        val startFrame = segment.startB
        val endFrame = segment.endB
        if (endFrame - startFrame < needed) return null
        val targetOffset = if (isIntro) 0L else target.tailStartMs
        val start = targetOffset + startFrame * step
        var end = targetOffset + endFrame * step
        if (!isIntro && target.durationMs > 0L && target.durationMs - end < END_SNAP_MS) end = target.durationMs
        return Triple(start, end, segment.length)
    }
}
