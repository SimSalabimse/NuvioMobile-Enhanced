package com.nuvio.app.features.analysis

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.pow
import kotlinx.serialization.Serializable

/** Bumped when the analyzer output changes meaning; older results are analysed again. */
internal const val MEDIA_ANALYSIS_VERSION = 1

/** Version of the derived data (recaps, previews, thumbnails) computed from a stored analysis. */
internal const val MEDIA_ANALYSIS_DERIVED_VERSION = 3

/**
 * What the on-device analyzer measured in one downloaded episode or movie, stored per episode
 * (see [MediaAnalysisStore]). Fingerprints and detected segments stay after the file is deleted so
 * later episodes of the season can still be matched against them; the speech energy is only
 * useful with the same file and is dropped when the download goes.
 */
@Serializable
internal data class MediaAnalysisResult(
    val key: String,
    val version: Int = MEDIA_ANALYSIS_VERSION,
    val analyzedAtMs: Long,
    val durationMs: Long,
    /** Download file this was measured on (energy and loudness belong to that file). */
    val fileName: String? = null,
    val energyStepMs: Int = 100,
    /** Base64 of one byte per step: RMS level, 0 = -90 dBFS .. 255 = 0 dBFS. */
    val energy: String? = null,
    /** EBU R128 / BS.1770 integrated loudness of the main audio track. */
    val integratedLufs: Double? = null,
    val fingerprintStepMs: Int = 100,
    /** Base64 of little-endian uint32 sub-fingerprints of the first [HEAD_FINGERPRINT_MS]. */
    val headFingerprint: String? = null,
    /** Same for the last [TAIL_FINGERPRINT_MS], starting at [tailStartMs]. */
    val tailFingerprint: String? = null,
    val tailStartMs: Long = 0L,
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val creditsStartMs: Long? = null,
    val creditsEndMs: Long? = null,
    /** "Previously on" recap at the start, found against other episodes of the season. */
    val recapStartMs: Long? = null,
    val recapEndMs: Long? = null,
    /**
     * Why the last recap pass ended, as a token such as `rejected-no-match` or
     * `not-checked-no-print`. Null on a result saved before this was recorded.
     * A missing time with a null token was not a completed "none" result.
     */
    val recapOutcome: String? = null,
    /**
     * Why the last intro pass ended. Null means that pass did not record a completed check.
     * A missing time with a null token is "Not checked", the same rule as [recapOutcome].
     */
    val introOutcome: String? = null,
    /** Why the last credits pass ended. Same rule as [introOutcome]. */
    val creditsOutcome: String? = null,
    /** Derived data (recap, thumbnails) is up to date with [MEDIA_ANALYSIS_DERIVED_VERSION]. */
    val derivedVersion: Int = 0,
    /** Scrub-preview thumbnail sheets of this file (null: not made). */
    val thumbnails: ThumbnailSheets? = null,
    /**
     * Segments the user marked or confirmed for this episode ("intro", "recap", "outro",
     * "preview"). They always win over detection, and intro/credits marks on a fingerprinted
     * episode are the season's trusted reference (see [SkipLearning]).
     */
    val userSegments: Map<String, UserSegment> = emptyMap(),
    /** Segment types found by matching another episode's user mark. */
    val learnedTypes: Set<String> = emptySet(),
    /** Segment types found from a clip of another episode ([SiblingClipReference]): type to episode. */
    val foundUsing: Map<String, Int> = emptyMap(),
    /**
     * Analyser or sibling-clip failures for this download file. A new file name starts again at 0.
     * Three means the episode is left alone until the file changes.
     */
    val analysisAttempts: Int = 0,
    /** Agreeing siblings and mean bit errors for [introStartMs]. Null on older stored results. */
    val introConfidence: SegmentConfidence? = null,
    /** Agreeing siblings and mean bit errors for [creditsStartMs]. */
    val creditsConfidence: SegmentConfidence? = null,
    /**
     * Ranges a saved correction moved off. Detection will not put that range back.
     * A Wrong tap that does not save a new range adds nothing here.
     */
    val rejectedSegments: List<RejectedSegment> = emptyList(),
    /** Quiet runs kept after the energy track is dropped, so edges can still snap. */
    val silentRanges: List<SilentRange> = emptyList(),
    /**
     * Black-frame runs, same shape as [silentRanges]. The audio pass does not measure these
     * (video stays discarded). A caller that has them, including the synthetic harness, can set
     * them and the edge snap will prefer them.
     */
    val blackRanges: List<SilentRange> = emptyList(),
    /**
     * Next-episode preview. Chapter markers set this, and so does a fingerprint match of this
     * episode's ending against the next episode's cold open.
     */
    val previewStartMs: Long? = null,
    val previewEndMs: Long? = null,
    /**
     * Kinds whose stored times came from chapter markers ("intro", "recap", "outro", "preview").
     * Rank 2 in [SkipSourcePriority]: above fingerprint matches, below user marks and the
     * external databases the player applies at playback.
     */
    val chapterTypes: Set<String> = emptySet(),
)

/** How sure a detected intro or credits range is. More agreeing siblings win, then fewer bit errors. */
@Serializable
internal data class SegmentConfidence(
    val agreeingSiblings: Int = 0,
    val avgBitErrors: Double = 0.0,
    /** TheIntroDB named the same range. This only raises a score; it never drops one. */
    val theIntroDbAgrees: Boolean = false,
) {
    fun strictlyBeats(other: SegmentConfidence): Boolean {
        if (agreeingSiblings != other.agreeingSiblings) return agreeingSiblings > other.agreeingSiblings
        if (avgBitErrors != other.avgBitErrors) return avgBitErrors < other.avgBitErrors
        return theIntroDbAgrees && !other.theIntroDbAgrees
    }
}

/** A range the user moved a segment away from. [type] is "intro", "outro", or "recap". */
@Serializable
internal data class RejectedSegment(
    val type: String,
    val startMs: Long,
    val endMs: Long,
)

/** A quiet stretch of the analysed file, in milliseconds. */
@Serializable
internal data class SilentRange(
    val startMs: Long,
    val endMs: Long,
)

@Serializable
internal data class UserSegment(
    val startMs: Long,
    val endMs: Long,
    val markedAtMs: Long = 0L,
    /** Brought in from the user's own TheIntroDB submissions, not marked on this phone. */
    val imported: Boolean = false,
    /** Length of the video the mark was made on (0: unknown). */
    val durationMs: Long = 0L,
    /** The user submitted that this episode has none of this segment. */
    val absent: Boolean = false,
)

/** A mark with no file length, or a "none" answer, never teaches other episodes. */
internal val UserSegment.canTeach: Boolean get() = !absent && endMs > startMs && durationMs > 0L

/** The user's marks override what was detected for this episode. */
internal fun MediaAnalysisResult.withUserSegmentsApplied(): MediaAnalysisResult {
    if (userSegments.isEmpty()) return this
    var r = this
    // A mark made on another cut of the episode doesn't replace what was found in this file.
    val marks = userSegments.filterValues { com.nuvio.app.features.player.sameCut(it.durationMs, durationMs) }
    marks["intro"]?.let { r = r.applyingStoredMark(it, intro = true) }
    marks["outro"]?.let { r = r.applyingStoredMark(it, outro = true) }
    marks["recap"]?.let { r = r.applyingStoredMark(it, recap = true) }
    marks["preview"]?.let { r = r.applyingStoredMark(it, preview = true) }
    return r
}

/** A "none" answer clears the stored range. A timed mark replaces it. */
private fun MediaAnalysisResult.applyingStoredMark(
    mark: UserSegment,
    intro: Boolean = false,
    outro: Boolean = false,
    recap: Boolean = false,
    preview: Boolean = false,
): MediaAnalysisResult = when {
    intro && mark.absent -> copy(introStartMs = null, introEndMs = null)
    intro -> copy(introStartMs = mark.startMs, introEndMs = mark.endMs)
    outro && mark.absent -> copy(creditsStartMs = null, creditsEndMs = null)
    outro -> copy(creditsStartMs = mark.startMs, creditsEndMs = mark.endMs)
    recap && mark.absent -> copy(recapStartMs = null, recapEndMs = null)
    recap -> copy(recapStartMs = mark.startMs, recapEndMs = mark.endMs)
    preview && mark.absent -> copy(previewStartMs = null, previewEndMs = null)
    preview -> copy(previewStartMs = mark.startMs, previewEndMs = mark.endMs)
    else -> this
}

/**
 * Thumbnails every [intervalMs], [columns] x [rows] tiles per JPEG sheet, sheets stored as
 * `<key>_thumbs_<n>.jpg` next to the analysis JSON.
 */
@Serializable
internal data class ThumbnailSheets(
    val intervalMs: Long,
    val count: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val columns: Int,
    val rows: Int,
    val sheets: Int,
) {
    val perSheet: Int get() = columns * rows
}

internal fun thumbnailSheetFileName(key: String, index: Int): String = "${key}_thumbs_$index.jpg"

/**
 * Opening audio kept for intro and recap matching. Long enough for a title that starts after a
 * long first act. A title past this point is not in the print. Keep in step with
 * `MediaAnalyzerSampleLoop.headWindowMs`.
 */
internal const val HEAD_FINGERPRINT_MS = 25 * 60_000L

/**
 * Ending audio kept for credits and preview matching. Keep in step with `MediaAnalysisRun.tailMs`.
 * Credits longer than this window are only found for the part that was captured.
 */
internal const val TAIL_FINGERPRINT_MS = 15 * 60_000L

/**
 * True when a stored print is shorter than the capture window for this file. Older results kept
 * the first 10 minutes and the last 8. A late title is not in those prints.
 * A file shorter than the window is complete once its print covers the file.
 */
internal fun MediaAnalysisResult.capturedWindowIsShort(): Boolean {
    val step = fingerprintStepMs.coerceAtLeast(1).toLong()
    if (durationMs <= 0L || step <= 0L) return false
    fun short(encoded: String?, limitMs: Long): Boolean {
        val frames = decodeFingerprint(encoded)
        if (frames.isEmpty()) return false
        val expected = (minOf(limitMs, durationMs) / step).toInt()
        if (expected <= 0) return false
        return frames.size + 5 < expected * 9 / 10
    }
    return short(headFingerprint, HEAD_FINGERPRINT_MS) || short(tailFingerprint, TAIL_FINGERPRINT_MS)
}

/** ReplayGain 2 reference level; films and episodes land a little under music targets. */
internal const val LOUDNESS_TARGET_LUFS = -18.0
private const val LOUDNESS_MAX_BOOST_DB = 6.0
private const val LOUDNESS_MAX_CUT_DB = -12.0

/** Gain that brings [MediaAnalysisResult.integratedLufs] to the target, capped against clipping. */
internal fun MediaAnalysisResult.loudnessGainDb(): Double? {
    val lufs = integratedLufs ?: return null
    if (!lufs.isFinite() || lufs < -70.0) return null
    return (LOUDNESS_TARGET_LUFS - lufs).coerceIn(LOUDNESS_MAX_CUT_DB, LOUDNESS_MAX_BOOST_DB)
}

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeFingerprint(encoded: String?): IntArray {
    if (encoded.isNullOrEmpty()) return IntArray(0)
    val bytes = runCatching { Base64.decode(encoded) }.getOrNull() ?: return IntArray(0)
    val out = IntArray(bytes.size / 4)
    for (i in out.indices) {
        val o = i * 4
        out[i] = (bytes[o].toInt() and 0xFF) or
            ((bytes[o + 1].toInt() and 0xFF) shl 8) or
            ((bytes[o + 2].toInt() and 0xFF) shl 16) or
            ((bytes[o + 3].toInt() and 0xFF) shl 24)
    }
    return out
}

@OptIn(ExperimentalEncodingApi::class)
internal fun encodeFingerprint(values: IntArray): String {
    val bytes = ByteArray(values.size * 4)
    values.forEachIndexed { i, v ->
        bytes[i * 4] = v.toByte()
        bytes[i * 4 + 1] = (v ushr 8).toByte()
        bytes[i * 4 + 2] = (v ushr 16).toByte()
        bytes[i * 4 + 3] = (v ushr 24).toByte()
    }
    return Base64.encode(bytes)
}

/** One byte per step, 0 = -90 dBFS .. 255 = 0 dBFS. */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeEnergyLevels(encoded: String?): IntArray {
    if (encoded.isNullOrEmpty()) return IntArray(0)
    val bytes = runCatching { Base64.decode(encoded) }.getOrNull() ?: return IntArray(0)
    return IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun encodeEnergyLevels(levels: IntArray): String {
    val bytes = ByteArray(levels.size) { levels[it].toByte() }
    return Base64.encode(bytes)
}

/** Inverse of [decodeEnergy], so a test can store the same uint8 levels the analyser writes. */
internal fun energyLevelForLinear(energy: Double): Int {
    if (energy <= 0.0) return 0
    val db = 20.0 * kotlin.math.log10(energy)
    return ((db + 90.0) / 90.0 * 255.0).toInt().coerceIn(0, 255)
}

/** Linear RMS per step (the scale the live subtitle sync capture reports). */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeEnergy(encoded: String?): DoubleArray {
    if (encoded.isNullOrEmpty()) return DoubleArray(0)
    val bytes = runCatching { Base64.decode(encoded) }.getOrNull() ?: return DoubleArray(0)
    return DoubleArray(bytes.size) { i ->
        val level = bytes[i].toInt() and 0xFF
        if (level == 0) 0.0 else 10.0.pow(((level / 255.0) * 90.0 - 90.0) / 20.0)
    }
}
