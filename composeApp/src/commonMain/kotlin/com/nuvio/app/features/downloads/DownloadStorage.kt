package com.nuvio.app.features.downloads

/** Free and total bytes of the volume that holds the downloads folder. */
internal data class VolumeSpace(
    val availableBytes: Long,
    val totalBytes: Long,
)

/** One entry in the downloads folder: a file, `.part` file or subtitles folder, with its size. */
internal data class DiskFile(
    val name: String,
    val sizeBytes: Long,
)

/** Disk access the storage view needs, per platform. */
internal expect object DownloadDiskInfo {
    /** Free space usable for downloads ("important usage" on iOS) and the volume size. */
    fun volumeSpace(): VolumeSpace?

    /** Top-level entries of the active downloads folder. Folders count with their contents. */
    fun listDownloadFiles(): List<DiskFile>

    /** Deletes one top-level entry of the downloads folder. */
    fun deleteDownloadFile(name: String): Boolean

    /** Stored downloads list of every profile, so another profile's files are not orphans. */
    fun allProfilePayloads(): List<String>

    /**
     * What the whole app takes on disk (the figure iOS Settings shows as Documents & Data), its
     * caches, and leftovers outside the downloads folder no download owns (loose subtitle files,
     * stale resume data). [ownedFileNames] are the download file names of every profile.
     */
    fun appStorageUsage(ownedFileNames: Set<String>): AppStorageUsage?

    /** Deletes one leftover found by [appStorageUsage]. */
    fun deleteLeftover(path: String): Boolean
}

internal data class LeftoverFile(
    val path: String,
    val sizeBytes: Long,
)

internal data class AppStorageUsage(
    /** Everything the app stores (app container, plus a downloads folder outside it). */
    val totalBytes: Long,
    /** Caches and temporary files the system may clear. */
    val cacheBytes: Long,
    val leftovers: List<LeftoverFile>,
) {
    val leftoverBytes: Long get() = leftovers.sumOf { it.sizeBytes }
}

/** Loose subtitle sidecars ("3.srt", "manifest.json") outside a download's own folder. */
private val looseSubtitleFilePattern = Regex("""(?i)(\d+\.(srt|vtt|ass|ssa|ttml|sub)|manifest\.json)""")

internal fun isLooseSubtitleFile(name: String): Boolean = looseSubtitleFilePattern.matches(name)

/** Smallest spare space kept free (500 MB); a running download stops below it. */
internal const val DOWNLOAD_FREE_SPACE_MARGIN_BYTES: Long = 500_000_000L

/** Largest spare space ever asked for on top of a file (1 GB). */
internal const val DOWNLOAD_FREE_SPACE_RESERVE_MAX_BYTES: Long = 1_000_000_000L

/** The platform's own size text (iOS: ByteCountFormatter, file style), or null to use [formatBytesDecimal]. */
internal expect fun platformByteCountString(bytes: Long): String?

/** Decimal (1000-based) size, like iOS Settings: "4.0 GB", "419.4 MB". */
internal fun formatBytesDecimal(bytes: Long): String {
    platformByteCountString(bytes)?.let { return it }
    if (bytes <= 0L) return "0 B"
    val value = bytes.toDouble()
    fun one(v: Double): String = ((v * 10.0).toLong() / 10.0).toString()
    return when {
        value >= 1e12 -> "${one(value / 1e12)} TB"
        value >= 1e9 -> "${one(value / 1e9)} GB"
        value >= 1e6 -> "${one(value / 1e6)} MB"
        value >= 1e3 -> "${one(value / 1e3)} KB"
        else -> "$bytes B"
    }
}

/** Spare space kept on top of a file: 5 % of it, at least 500 MB, at most 1 GB. */
internal fun downloadSpaceReserve(fileBytes: Long?): Long {
    val fivePercent = fileBytes?.takeIf { it > 0L }?.let { it / 20L } ?: 0L
    return fivePercent.coerceIn(DOWNLOAD_FREE_SPACE_MARGIN_BYTES, DOWNLOAD_FREE_SPACE_RESERVE_MAX_BYTES)
}

/** Why a download does not fit: what it needs (rest of the file plus the reserve) and what is free. */
internal data class DownloadSpaceShortfall(
    val neededBytes: Long?,
    val freeBytes: Long,
)

/**
 * Up-front check. A known size must fit with its reserve; an unknown size never blocks here
 * (the running download checks instead, see [spaceRunningOut]).
 */
internal fun spaceShortfallBeforeStart(
    remainingBytes: Long?,
    fileBytes: Long?,
    volume: VolumeSpace?,
): DownloadSpaceShortfall? {
    val free = volume?.availableBytes ?: return null
    val remaining = remainingBytes?.takeIf { it > 0L } ?: return null
    val needed = remaining + downloadSpaceReserve(fileBytes ?: remaining)
    return if (needed > free) DownloadSpaceShortfall(needed, free) else null
}

/** While downloading: stop only when free space drops under the 500 MB floor. */
internal fun spaceRunningOut(volume: VolumeSpace?): DownloadSpaceShortfall? {
    val free = volume?.availableBytes ?: return null
    return if (free < DOWNLOAD_FREE_SPACE_MARGIN_BYTES) DownloadSpaceShortfall(null, free) else null
}

/** "Needs 3.8 GB, 4.0 GB free. Free up space, then tap Retry." */
internal fun downloadSpaceMessage(shortfall: DownloadSpaceShortfall): String {
    val free = formatSeasonBytes(shortfall.freeBytes)
    val head = shortfall.neededBytes?.let { "Needs ${formatSeasonBytes(it)}, $free free" } ?: "Only $free free"
    return "$head. $DOWNLOAD_SPACE_HINT"
}

internal const val DOWNLOAD_SPACE_HINT = "Free up space, then tap Retry."

internal data class SeasonStorage(
    val seasonNumber: Int,
    val bytes: Long,
    val downloadIds: List<String>,
)

internal data class TitleStorage(
    val parentMetaId: String,
    val title: String,
    val isShow: Boolean,
    val bytes: Long,
    val seasons: List<SeasonStorage>,
    val downloadIds: List<String>,
)

internal data class DownloadStorageSummary(
    /** Everything in the downloads folder that belongs to a download of any profile. */
    val nuvioBytes: Long,
    /** Of [nuvioBytes]: subtitles and other sidecars kept with the downloads. */
    val subtitleBytes: Long = 0L,
    /** The whole app on disk, with caches and leftovers; null when the platform cannot tell. */
    val app: AppStorageUsage? = null,
    val volume: VolumeSpace?,
    /** This profile's titles, largest first. */
    val titles: List<TitleStorage>,
    /** Leftover files no download of any profile owns. */
    val orphans: List<DiskFile>,
    /** Failed or paused rows of this profile ("Failed / incomplete downloads"), with their bytes on disk. */
    val incompleteIds: List<String> = emptyList(),
    val incompleteBytes: Long = 0L,
) {
    val orphanBytes: Long get() = orphans.sumOf { it.sizeBytes }

    /** Video files (and their partial downloads), without subtitles. */
    val downloadBytes: Long get() = (nuvioBytes - subtitleBytes).coerceAtLeast(0L)

    /** Orphans in the downloads folder plus leftovers elsewhere in the app. */
    val allLeftoverBytes: Long get() = orphanBytes + (app?.leftoverBytes ?: 0L)

    /** Everything Nuvio stores: the app total when known, else what the downloads folder holds. */
    val appTotalBytes: Long
        get() = maxOf(app?.totalBytes ?: 0L, nuvioBytes + allLeftoverBytes + (app?.cacheBytes ?: 0L))

    /** App data that is none of the above (settings, artwork, databases). */
    val appOtherBytes: Long
        get() = (appTotalBytes - nuvioBytes - allLeftoverBytes - (app?.cacheBytes ?: 0L)).coerceAtLeast(0L)

    /** Used by everything else on the device. */
    val otherBytes: Long?
        get() = volume?.let { (it.totalBytes - it.availableBytes - appTotalBytes).coerceAtLeast(0L) }
}

/** File names under the downloads folder look like `Title S01E02_<time>_<n>.mkv`. */
private val nuvioFileNamePattern = Regex(""".+_[0-9a-z]{6,}_[0-9a-z]+\.[A-Za-z0-9]{2,5}(\..+)?""")
internal fun isNuvioDownloadFileName(name: String): Boolean = nuvioFileNamePattern.matches(name)

private val storedFileNamePattern = Regex(""""fileName"\s*:\s*"((?:[^"\\]|\\.)*)"""")

internal fun ownsDiskFile(fileName: String, diskName: String): Boolean =
    fileName.isNotBlank() && (diskName == fileName || diskName.startsWith("$fileName."))

internal fun storedFileNames(payloads: List<String>): Set<String> =
    payloads.flatMap { payload ->
        storedFileNamePattern.findAll(payload).map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }.toList()
    }.toSet()

/**
 * Sizes per title and season from what is on disk (a finished file, its `.part` and its
 * subtitles), falling back to the reported bytes for a row with nothing on disk yet.
 */
internal fun summarizeDownloadStorage(
    items: List<DownloadItem>,
    files: List<DiskFile>,
    otherProfileFileNames: Set<String>,
    volume: VolumeSpace?,
): DownloadStorageSummary {
    val ownedNames = items.map { it.fileName } + otherProfileFileNames
    fun bytesOf(item: DownloadItem): Long {
        val onDisk = files.filter { ownsDiskFile(item.fileName, it.name) }.sumOf { it.sizeBytes }
        return if (onDisk > 0L) onDisk else 0L
    }
    val itemBytes = items.associate { it.id to bytesOf(it) }
    val titles = items
        .groupBy { it.parentMetaId.trim() + if (it.isEpisode) "|show" else "|movie" }
        .map { (_, group) ->
            val first = group.first()
            val seasons = if (first.isEpisode) {
                group.groupBy { it.seasonNumber ?: 0 }
                    .map { (season, eps) ->
                        SeasonStorage(season, eps.sumOf { itemBytes[it.id] ?: 0L }, eps.map { it.id })
                    }
                    .sortedByDescending { it.bytes }
            } else {
                emptyList()
            }
            TitleStorage(
                parentMetaId = first.parentMetaId,
                title = first.title,
                isShow = first.isEpisode,
                bytes = group.sumOf { itemBytes[it.id] ?: 0L },
                seasons = seasons,
                downloadIds = group.map { it.id },
            )
        }
        .sortedByDescending { it.bytes }
    val owned = files.filter { file -> ownedNames.any { ownsDiskFile(it, file.name) } }
    val orphans = files.filter { file ->
        ownedNames.none { ownsDiskFile(it, file.name) } &&
            (nuvioFileNamePattern.matches(file.name) || isLooseSubtitleFile(file.name))
    }
    val incomplete = items.filter { it.status == DownloadStatus.Failed || it.status == DownloadStatus.Paused }
    return DownloadStorageSummary(
        incompleteIds = incomplete.map { it.id },
        incompleteBytes = incomplete.sumOf { itemBytes[it.id] ?: 0L },
        nuvioBytes = owned.sumOf { it.sizeBytes },
        subtitleBytes = owned.filter { it.name.endsWith(".subtitles") }.sumOf { it.sizeBytes },
        volume = volume,
        titles = titles,
        orphans = orphans.sortedByDescending { it.sizeBytes },
    )
}

/** Free space after the margin, or null when the platform cannot tell. */
internal fun usableFreeBytes(volume: VolumeSpace?): Long? =
    volume?.let { (it.availableBytes - DOWNLOAD_FREE_SPACE_MARGIN_BYTES).coerceAtLeast(0L) }

/** True when [remainingBytes] would not fit with its reserve, or free space is under the floor. */
internal fun lacksSpaceFor(remainingBytes: Long?, volume: VolumeSpace?): Boolean =
    spaceRunningOut(volume) != null || spaceShortfallBeforeStart(remainingBytes, remainingBytes, volume) != null

/** Size assumed for an episode when neither the source nor earlier downloads say. */
internal const val GUESSED_EPISODE_BYTES = 2_000_000_000L

/** A typical episode of this show: the median size of its downloads; null without any. */
internal fun typicalEpisodeBytes(metaId: String, items: List<DownloadItem>): Long? {
    val sizes = items.filter { it.parentMetaId == metaId && it.isEpisode }
        .mapNotNull { it.totalBytes?.takeIf { bytes -> bytes > 0L } }
        .sorted()
    return sizes.getOrNull(sizes.size / 2)
}
