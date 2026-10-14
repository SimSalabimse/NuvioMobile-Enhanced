package com.nuvio.app.features.analysis

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.pow
import kotlinx.serialization.Serializable

/** Bumped when the analyzer output changes meaning; older results are analysed again. */
internal const val MEDIA_ANALYSIS_VERSION = 1

/** Version of the derived data (recaps, thumbnails) computed from a stored analysis. */
internal const val MEDIA_ANALYSIS_DERIVED_VERSION = 2

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
)

@Serializable
internal data class UserSegment(
    val startMs: Long,
    val endMs: Long,
    val markedAtMs: Long = 0L,
)

/** The user's marks override what was detected for this episode. */
internal fun MediaAnalysisResult.withUserSegmentsApplied(): MediaAnalysisResult {
    if (userSegments.isEmpty()) return this
    var r = this
    userSegments["intro"]?.let { r = r.copy(introStartMs = it.startMs, introEndMs = it.endMs) }
    userSegments["outro"]?.let { r = r.copy(creditsStartMs = it.startMs, creditsEndMs = it.endMs) }
    userSegments["recap"]?.let { r = r.copy(recapStartMs = it.startMs, recapEndMs = it.endMs) }
    return r
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

internal const val HEAD_FINGERPRINT_MS = 10 * 60_000L
internal const val TAIL_FINGERPRINT_MS = 8 * 60_000L

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
