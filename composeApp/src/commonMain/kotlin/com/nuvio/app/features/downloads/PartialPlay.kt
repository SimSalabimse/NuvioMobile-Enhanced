package com.nuvio.app.features.downloads

/** Bytes that must be on disk before an in-progress file can be watched. */
internal const val EARLY_PLAY_BYTES: Long = 8L * 1024L * 1024L

/** How much of the prefix the container check needs. The 8 MiB gate uses the file length. */
internal const val PARTIAL_CLASSIFY_PREFIX_BYTES: Int = 8 * 1024 * 1024

internal const val PLAYS_WHEN_DOWNLOAD_FINISHES: String = "Plays when the download finishes."

internal enum class PartialContainerKind {
    Matroska,
    WebM,
    MpegTs,
    Mp4,
    Mov,
    Unknown,
}

internal enum class PrefixStart {
    CanStart,
    WaitForComplete,
    NotYet,
}

internal data class EarlyPlayDecision(
    val playable: Boolean,
    val playsWhenDownloadFinishes: Boolean,
)

internal fun earlyPlayDecision(
    bytesOnDisk: Long,
    fileName: String,
    prefix: ByteArray,
    downloadComplete: Boolean,
): EarlyPlayDecision {
    if (downloadComplete) {
        return EarlyPlayDecision(playable = true, playsWhenDownloadFinishes = false)
    }
    val kind = classifyPartialContainer(fileName, prefix)
    return when (partialPrefixStart(kind, prefix)) {
        PrefixStart.WaitForComplete -> EarlyPlayDecision(
            playable = false,
            playsWhenDownloadFinishes = true,
        )
        PrefixStart.CanStart -> EarlyPlayDecision(
            playable = bytesOnDisk >= EARLY_PLAY_BYTES,
            playsWhenDownloadFinishes = false,
        )
        PrefixStart.NotYet -> EarlyPlayDecision(
            playable = false,
            playsWhenDownloadFinishes = false,
        )
    }
}

internal fun classifyPartialContainer(fileName: String, prefix: ByteArray): PartialContainerKind {
    val extension = fileExtension(fileName)
    if (hasEbmlHeader(prefix)) {
        return if (prefixContainsAscii(prefix, "webm") || extension == "webm") {
            PartialContainerKind.WebM
        } else {
            PartialContainerKind.Matroska
        }
    }
    if (hasFtypBox(prefix) || extension == "mp4" || extension == "m4v" || extension == "mov") {
        if (looksLikeIsoBmff(prefix) || hasFtypBox(prefix)) {
            return if (extension == "mov" || ftypMajorBrand(prefix) == "qt  ") {
                PartialContainerKind.Mov
            } else {
                PartialContainerKind.Mp4
            }
        }
    }
    if (looksLikeMpegTs(prefix) || (extension in MPEG_TS_EXTENSIONS && prefix.firstOrNull() == MPEG_TS_SYNC)) {
        return PartialContainerKind.MpegTs
    }
    return when (extension) {
        "mkv", "mka", "mk3d", "mks" -> if (prefix.size < EBML_MAGIC.size) {
            PartialContainerKind.Matroska
        } else {
            PartialContainerKind.Unknown
        }
        "webm" -> if (prefix.size < EBML_MAGIC.size) PartialContainerKind.WebM else PartialContainerKind.Unknown
        "ts", "mts", "m2ts" -> PartialContainerKind.MpegTs
        "mp4", "m4v" -> PartialContainerKind.Mp4
        "mov" -> PartialContainerKind.Mov
        else -> PartialContainerKind.Unknown
    }
}

internal fun partialPrefixStart(kind: PartialContainerKind, prefix: ByteArray): PrefixStart = when (kind) {
    PartialContainerKind.Matroska,
    PartialContainerKind.WebM,
    -> if (prefix.size < EBML_MAGIC.size || hasEbmlHeader(prefix)) PrefixStart.CanStart else PrefixStart.NotYet
    PartialContainerKind.MpegTs -> if (prefix.isEmpty() || looksLikeMpegTs(prefix) || prefix.firstOrNull() == MPEG_TS_SYNC) {
        PrefixStart.CanStart
    } else {
        PrefixStart.NotYet
    }
    PartialContainerKind.Mp4,
    PartialContainerKind.Mov,
    -> mp4PrefixStart(prefix)
    PartialContainerKind.Unknown -> PrefixStart.NotYet
}

internal fun partialContentType(fileName: String, prefix: ByteArray = ByteArray(0)): String =
    when (classifyPartialContainer(fileName, prefix)) {
        PartialContainerKind.Matroska -> "video/x-matroska"
        PartialContainerKind.WebM -> "video/webm"
        PartialContainerKind.MpegTs -> "video/mp2t"
        PartialContainerKind.Mp4 -> "video/mp4"
        PartialContainerKind.Mov -> "video/quicktime"
        PartialContainerKind.Unknown -> "application/octet-stream"
    }

private val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
private const val MPEG_TS_SYNC: Byte = 0x47
private const val MPEG_TS_PACKET = 188
private val MPEG_TS_EXTENSIONS = setOf("ts", "mts", "m2ts")

private fun fileExtension(fileName: String): String =
    fileName.substringAfterLast('.', "").substringBefore('?').lowercase()

private fun hasEbmlHeader(prefix: ByteArray): Boolean {
    if (prefix.size < EBML_MAGIC.size) return false
    return prefix[0] == EBML_MAGIC[0] &&
        prefix[1] == EBML_MAGIC[1] &&
        prefix[2] == EBML_MAGIC[2] &&
        prefix[3] == EBML_MAGIC[3]
}

private fun looksLikeMpegTs(prefix: ByteArray): Boolean {
    if (prefix.isEmpty() || prefix[0] != MPEG_TS_SYNC) return false
    if (prefix.size <= MPEG_TS_PACKET) return true
    if (prefix[MPEG_TS_PACKET] != MPEG_TS_SYNC) return false
    if (prefix.size <= MPEG_TS_PACKET * 2) return true
    return prefix[MPEG_TS_PACKET * 2] == MPEG_TS_SYNC
}

private fun looksLikeIsoBmff(prefix: ByteArray): Boolean = hasFtypBox(prefix)

private fun hasFtypBox(prefix: ByteArray): Boolean {
    if (prefix.size < 8) return false
    return boxType(prefix, 4) == "ftyp"
}

private fun ftypMajorBrand(prefix: ByteArray): String {
    if (prefix.size < 16 || boxType(prefix, 4) != "ftyp") return ""
    return boxType(prefix, 8)
}

private fun mp4PrefixStart(prefix: ByteArray): PrefixStart {
    if (prefix.size < 8) return PrefixStart.NotYet
    var offset = 0
    var sawMoov = false
    while (offset + 8 <= prefix.size) {
        val size32 = u32(prefix, offset)
        val type = boxType(prefix, offset + 4)
        if (size32 < 0L) return if (sawMoov) PrefixStart.CanStart else PrefixStart.NotYet
        val header = if (size32 == 1L) 16 else 8
        if (size32 != 0L && size32 != 1L && size32 < header) return PrefixStart.NotYet
        if (size32 == 1L && offset + 16 > prefix.size) {
            return if (sawMoov) PrefixStart.CanStart else PrefixStart.NotYet
        }
        when (type) {
            "moov" -> return PrefixStart.CanStart
            "mdat" -> return PrefixStart.WaitForComplete
        }
        if (type == "moov") sawMoov = true
        val boxSize = when (size32) {
            0L -> return if (sawMoov) PrefixStart.CanStart else PrefixStart.NotYet
            1L -> u64(prefix, offset + 8)
            else -> size32
        }
        if (boxSize < header) return PrefixStart.NotYet
        val next = offset + boxSize
        if (next <= offset || next > prefix.size) {
            return if (sawMoov) PrefixStart.CanStart else PrefixStart.NotYet
        }
        offset = next.toInt()
    }
    return if (sawMoov) PrefixStart.CanStart else PrefixStart.NotYet
}

private fun boxType(bytes: ByteArray, offset: Int): String {
    if (offset + 4 > bytes.size) return ""
    return bytes.decodeToString(offset, offset + 4)
}

private fun u32(bytes: ByteArray, offset: Int): Long {
    if (offset + 4 > bytes.size) return -1L
    return ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)
}

private fun u64(bytes: ByteArray, offset: Int): Long {
    if (offset + 8 > bytes.size) return -1L
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
    }
    return value
}

private fun prefixContainsAscii(prefix: ByteArray, needle: String): Boolean {
    if (needle.isEmpty() || prefix.size < needle.length) return false
    val bytes = needle.encodeToByteArray()
    val last = prefix.size - bytes.size
    for (start in 0..last) {
        var matches = true
        for (index in bytes.indices) {
            if (prefix[start + index] != bytes[index]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}
