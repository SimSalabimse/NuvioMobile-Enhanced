package com.nuvio.app.features.analysis

import com.nuvio.app.core.serialization.LenientJson
import com.nuvio.app.features.player.CHAPTER_SKIP_PROVIDER
import kotlinx.serialization.Serializable

/**
 * Skip-source priority. A lower rank wins. Chapter markers fill a kind only when nothing
 * of a higher rank already has it, and they never replace that result.
 *
 * 0. User mark or imported submission (`userSegments`, providers "you" and "you-theintrodb").
 * 1. External database chosen at playback (TheIntroDB, IntroDB, AniSkip, Anime-Skip, any other
 *    online provider recorded by [SkipSourceLedger]). Playback already prefers these over
 *    anything stored on the device. They are not written over by chapter times.
 * 2. Chapter markers embedded in the file (provider [CHAPTER_SKIP_PROVIDER]).
 * 3. On-device fingerprint match: pairwise season match, a mark learned from another episode
 *    (`learnedTypes`), or a sibling clip (`foundUsing`). Provider "on-device". This stays the
 *    fallback for every kind the chapters do not cover.
 * 4. Heuristic guess (provider "heuristic"), player-side only.
 *
 * A sibling's teachable mark is rank 0 for the sibling and rank 3 here until the fingerprint
 * match actually lands (`learnedTypes`). The head or tail print is kept in that case so the
 * match can still run; chapters apply only if it does not.
 */
internal object SkipSourcePriority {
    const val USER = 0
    const val EXTERNAL_DATABASE = 1
    const val CHAPTERS = 2
    const val FINGERPRINT = 3
    const val GUESS = 4

    fun rank(provider: String): Int = when {
        SkipLearning.isUserProvider(provider) -> USER
        provider == CHAPTER_SKIP_PROVIDER -> CHAPTERS
        provider == com.nuvio.app.features.player.ON_DEVICE_SKIP_PROVIDER -> FINGERPRINT
        provider == "heuristic" -> GUESS
        else -> EXTERNAL_DATABASE
    }
}

/** One chapter as the container lists it, in milliseconds. An end at or before the start means "until the next chapter". */
internal data class FileChapter(
    val title: String? = null,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
)

internal enum class ChapterSkipKind(val storageKey: String) {
    Intro("intro"),
    Recap("recap"),
    Credits("outro"),
    Preview("preview"),
}

internal data class ChapterSegment(
    val kind: ChapterSkipKind,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Maps chapter titles to intro / recap / credits / preview.
 *
 * Titles are trimmed, case-insensitive, and matched as a whole name against a short English list.
 * A leading "the", a leading index ("1.", "II -"), underscores, and trailing punctuation are
 * ignored. Anything else, including non-English names and titles that only contain a known word,
 * is left alone. A wrong skip is worse than no skip.
 *
 * Ranges that cannot be a real segment are dropped: empty or negative, an intro longer than
 * 6 minutes or starting late, credits that begin in the first 40% of the runtime or that do not
 * finish near the end, a recap that is not at the start, a preview that is not near the end.
 */
internal fun classifyChapters(chapters: List<FileChapter>, durationMs: Long): List<ChapterSegment> {
    val resolved = chapters.resolveEnds(durationMs)
    val found = linkedMapOf<ChapterSkipKind, ChapterSegment>()
    for (chapter in resolved) {
        val kind = chapterKind(chapter.title) ?: continue
        if (kind in found) continue
        val end = if (durationMs > 0L && chapter.endMs > durationMs && chapter.endMs - durationMs <= END_SLACK_MS) {
            durationMs
        } else {
            chapter.endMs
        }
        val segment = ChapterSegment(kind, chapter.startMs, end)
        if (!segment.plausible(durationMs, found[ChapterSkipKind.Intro], found[ChapterSkipKind.Credits])) continue
        found[kind] = segment
    }
    var segments = found.values.toList()
    val intro = segments.firstOrNull { it.kind == ChapterSkipKind.Intro }
    val credits = segments.firstOrNull { it.kind == ChapterSkipKind.Credits }
    if (intro != null && credits != null && rangesOverlap(intro, credits)) {
        segments = segments.filter { it.kind != ChapterSkipKind.Intro && it.kind != ChapterSkipKind.Credits }
    }
    val recap = segments.firstOrNull { it.kind == ChapterSkipKind.Recap }
    val introKept = segments.firstOrNull { it.kind == ChapterSkipKind.Intro }
    if (recap != null && introKept != null && recap.endMs > introKept.startMs + OVERLAP_SLACK_MS) {
        segments = segments.filter { it.kind != ChapterSkipKind.Recap }
    }
    val preview = segments.firstOrNull { it.kind == ChapterSkipKind.Preview }
    val creditsKept = segments.firstOrNull { it.kind == ChapterSkipKind.Credits }
    if (preview != null && creditsKept != null && previewInside(preview, creditsKept)) {
        segments = segments.filter { it.kind != ChapterSkipKind.Preview }
    }
    return segments
}

/**
 * Writes chapter segments onto [this] for kinds that are enabled and not already owned by a
 * higher-trust source ([protectedKinds]: user marks, learned fingerprint matches, sibling clips).
 * Fingerprint-only times for a covered kind are replaced. Kinds the chapters do not cover are
 * left exactly as they were.
 */
internal fun MediaAnalysisResult.applyingChapterSegments(
    chapters: List<FileChapter>,
    durationMs: Long,
    protectedKinds: Set<String>,
    introDetection: Boolean,
    recapDetection: Boolean,
): MediaAnalysisResult {
    var out = this
    for (segment in classifyChapters(chapters, durationMs)) {
        val key = segment.kind.storageKey
        val enabled = if (segment.kind == ChapterSkipKind.Recap) recapDetection else introDetection
        if (!enabled || key in protectedKinds) continue
        out = when (segment.kind) {
            ChapterSkipKind.Intro -> out.copy(introStartMs = segment.startMs, introEndMs = segment.endMs)
            ChapterSkipKind.Recap -> out.copy(recapStartMs = segment.startMs, recapEndMs = segment.endMs)
            ChapterSkipKind.Credits -> out.copy(creditsStartMs = segment.startMs, creditsEndMs = segment.endMs)
            ChapterSkipKind.Preview -> out.copy(previewStartMs = segment.startMs, previewEndMs = segment.endMs)
        }
        out = out.copy(chapterTypes = out.chapterTypes + key)
    }
    return out
}

/**
 * Recap fingerprint search. A recap that already came from chapters is kept, including when the
 * search finds nothing. With no chapter recap this is the previous assignment, including clearing
 * a recap the search did not repeat.
 */
internal fun MediaAnalysisResult.withDetectedRecap(detected: Pair<Long, Long>?): MediaAnalysisResult =
    if ("recap" in chapterTypes) {
        this
    } else {
        copy(recapStartMs = detected?.first, recapEndMs = detected?.second)
    }

/**
 * Which fingerprint prints the chapters already replace, so the analyzer can skip that work.
 * "head" is the opening print (intro match and recap match). "tail" is the credits print.
 * A print is kept when a higher-trust fingerprint match still needs it (a user mark on this
 * episode, so it can teach the season, or a sibling mark that has not been matched yet) or when
 * the chapters do not cover that part.
 */
internal fun fingerprintPartsToSkip(
    segments: List<ChapterSegment>,
    keepFingerprint: Set<String>,
    introDetection: Boolean,
    recapDetection: Boolean,
): Set<String> {
    val kinds = segments.map { it.kind }.toSet()
    // A print is skipped only when every part that uses it will be taken from chapters.
    // Detection that is off does not count as covered: the print stays, so turning it on later
    // still has the fingerprint the matcher already stored.
    val willUseIntro = introDetection && ChapterSkipKind.Intro in kinds && "intro" !in keepFingerprint
    val willUseRecap = recapDetection && ChapterSkipKind.Recap in kinds && "recap" !in keepFingerprint
    val willUseCredits = introDetection && ChapterSkipKind.Credits in kinds && "outro" !in keepFingerprint
    val skip = mutableSetOf<String>()
    val headStillNeeded = !introDetection ||
        !willUseIntro ||
        (recapDetection && !willUseRecap) ||
        "intro" in keepFingerprint
    if (!headStillNeeded && (willUseIntro || willUseRecap)) skip += "head"
    if (willUseCredits && "outro" !in keepFingerprint) skip += "tail"
    return skip
}

@Serializable
internal data class RawChapter(
    val title: String? = null,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
)

internal fun decodeChapters(json: String): List<FileChapter> {
    if (json.isBlank()) return emptyList()
    val raw = runCatching { LenientJson.decodeFromString<List<RawChapter>>(json) }.getOrNull() ?: return emptyList()
    return raw.map { FileChapter(it.title, it.startMs, it.endMs) }
}

/**
 * Called from the iOS analyzer (Swift) after it has read the container chapters and before it
 * builds sub-fingerprints. Returns "head", "tail", "head,tail", or "" — the prints to skip.
 * The episode key is set by the scheduler for the file currently being analysed.
 */
object ChapterSkipClassifier {
    internal var episodeKey: String = ""
    internal var parentMetaId: String = ""
    internal var seasonNumber: Int = -1

    internal fun prepare(key: String, parentMetaId: String, seasonNumber: Int) {
        episodeKey = key
        this.parentMetaId = parentMetaId
        this.seasonNumber = seasonNumber
    }

    internal fun clear() {
        episodeKey = ""
        parentMetaId = ""
        seasonNumber = -1
    }

    fun fingerprintSkip(chaptersJson: String, durationMs: Long): String {
        val segments = classifyChapters(decodeChapters(chaptersJson), durationMs)
        val parts = fingerprintPartsToSkip(
            segments = segments,
            keepFingerprint = fingerprintKeepKinds(),
            introDetection = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection),
            recapDetection = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.RecapDetection),
        )
        return listOf("head", "tail").filter { it in parts }.joinToString(",")
    }
}

/** Prints that must still be built so a user mark can teach, or a sibling mark can be matched. */
internal fun fingerprintKeepKinds(): Set<String> {
    val key = ChapterSkipClassifier.episodeKey
    if (key.isEmpty()) return emptySet()
    val kinds = mutableSetOf<String>()
    val stored = MediaAnalysisStore.load(key)
    if (stored?.userSegments?.containsKey("intro") == true) kinds += "intro"
    if (stored?.userSegments?.containsKey("outro") == true) kinds += "outro"
    val parent = ChapterSkipClassifier.parentMetaId
    val season = ChapterSkipClassifier.seasonNumber
    if (parent.isNotEmpty() && season >= 0) {
        MediaAnalysisStore.seasonSiblings(parent, season, key).forEach { sibling ->
            if (sibling.userSegments["intro"]?.canTeach == true) kinds += "intro"
            if (sibling.userSegments["outro"]?.canTeach == true) kinds += "outro"
        }
    }
    return kinds
}

private const val MIN_SEGMENT_MS = 8_000L
private const val MIN_PREVIEW_MS = 5_000L
private const val MAX_INTRO_MS = 6 * 60_000L
private const val MAX_RECAP_MS = 4 * 60_000L
private const val MAX_CREDITS_MS = 12 * 60_000L
private const val MAX_PREVIEW_MS = 3 * 60_000L
private const val MAX_INTRO_START_MS = 15 * 60_000L
private const val MAX_RECAP_START_MS = 90_000L
private const val MAX_AFTER_CREDITS_MS = 4 * 60_000L
private const val END_SLACK_MS = 1_500L
private const val OVERLAP_SLACK_MS = 1_000L

private val introTitles = setOf(
    "intro",
    "introduction",
    "opening",
    "opening credits",
    "opening theme",
    "title sequence",
    "op",
)

private val creditsTitles = setOf(
    "credits",
    "credit",
    "ending credits",
    "end credits",
    "outro",
    "closing credits",
    "main credits",
    "ed",
)

private val recapTitles = setOf(
    "recap",
    "previously",
    "previously on",
    "last time",
    "last time on",
)

private val previewTitles = setOf(
    "preview",
    "next time",
    "next time on",
    "next episode",
    "coming up",
)

private val leadingIndex = Regex("""^(?:\d{1,3}|[ivxlcdm]{1,6})[\s.)\-:]+""")

internal fun normalizeChapterTitle(raw: String): String {
    var text = raw.trim().lowercase().replace('_', ' ').replace('-', ' ')
    text = text.replace(Regex("""\s+"""), " ").trim(' ', '.', '!', ':', ';', '"', '\'')
    if (text.startsWith("the ")) text = text.removePrefix("the ").trim()
    text = leadingIndex.replace(text, "").trim()
    if (text.startsWith("the ")) text = text.removePrefix("the ").trim()
    return text.trim(' ', '.', '!', ':')
}

private fun chapterKind(title: String?): ChapterSkipKind? {
    val name = title?.takeIf { it.isNotBlank() }?.let(::normalizeChapterTitle) ?: return null
    return when (name) {
        in introTitles -> ChapterSkipKind.Intro
        in recapTitles -> ChapterSkipKind.Recap
        in creditsTitles -> ChapterSkipKind.Credits
        in previewTitles -> ChapterSkipKind.Preview
        else -> null
    }
}

private fun List<FileChapter>.resolveEnds(durationMs: Long): List<FileChapter> {
    val sorted = filter { it.startMs >= 0L }.sortedBy { it.startMs }
    return sorted.mapIndexed { index, chapter ->
        val nextStart = sorted.getOrNull(index + 1)?.startMs
        val end = when {
            chapter.endMs > chapter.startMs -> chapter.endMs
            nextStart != null && nextStart > chapter.startMs -> nextStart
            durationMs > chapter.startMs -> durationMs
            else -> chapter.endMs
        }
        chapter.copy(endMs = end)
    }
}

private fun ChapterSegment.plausible(
    durationMs: Long,
    intro: ChapterSegment?,
    credits: ChapterSegment?,
): Boolean {
    if (endMs <= startMs || startMs < 0L) return false
    if (durationMs > 0L && (startMs > durationMs || endMs > durationMs)) return false
    val length = endMs - startMs
    return when (kind) {
        ChapterSkipKind.Intro ->
            length in MIN_SEGMENT_MS..MAX_INTRO_MS &&
                startMs <= MAX_INTRO_START_MS &&
                (durationMs <= 0L || startMs * 5 < durationMs * 2)
        ChapterSkipKind.Recap ->
            length in MIN_SEGMENT_MS..MAX_RECAP_MS &&
                startMs <= MAX_RECAP_START_MS &&
                (intro == null || endMs <= intro.startMs + OVERLAP_SLACK_MS)
        ChapterSkipKind.Credits ->
            durationMs > 0L &&
                length in MIN_SEGMENT_MS..MAX_CREDITS_MS &&
                startMs * 5 >= durationMs * 2 &&
                durationMs - endMs <= MAX_AFTER_CREDITS_MS
        ChapterSkipKind.Preview ->
            durationMs > 0L &&
                length in MIN_PREVIEW_MS..MAX_PREVIEW_MS &&
                startMs * 10 >= durationMs * 7 &&
                (credits == null || !previewInside(this, credits))
    }
}

private fun rangesOverlap(a: ChapterSegment, b: ChapterSegment): Boolean =
    a.startMs < b.endMs && b.startMs < a.endMs

private fun previewInside(preview: ChapterSegment, credits: ChapterSegment): Boolean =
    preview.startMs >= credits.startMs && preview.endMs <= credits.endMs
