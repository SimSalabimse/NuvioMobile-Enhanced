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
}

/** Keep this much free; a download does not start, or stops, below it. */
internal const val DOWNLOAD_FREE_SPACE_MARGIN_BYTES: Long = 1024L * 1024L * 1024L

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
    val volume: VolumeSpace?,
    /** This profile's titles, largest first. */
    val titles: List<TitleStorage>,
    /** Leftover files no download of any profile owns. */
    val orphans: List<DiskFile>,
) {
    val orphanBytes: Long get() = orphans.sumOf { it.sizeBytes }

    /** Used by everything else on the device. */
    val otherBytes: Long?
        get() = volume?.let { (it.totalBytes - it.availableBytes - nuvioBytes - orphanBytes).coerceAtLeast(0L) }
}

/** File names under the downloads folder look like `Title S01E02_<time>_<n>.mkv`. */
private val nuvioFileNamePattern = Regex(""".+_[0-9a-z]{6,}_[0-9a-z]+\.[A-Za-z0-9]{2,5}(\..+)?""")
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
        ownedNames.none { ownsDiskFile(it, file.name) } && nuvioFileNamePattern.matches(file.name)
    }
    return DownloadStorageSummary(
        nuvioBytes = owned.sumOf { it.sizeBytes },
        volume = volume,
        titles = titles,
        orphans = orphans.sortedByDescending { it.sizeBytes },
    )
}

/** Free space after the margin, or null when the platform cannot tell. */
internal fun usableFreeBytes(volume: VolumeSpace?): Long? =
    volume?.let { (it.availableBytes - DOWNLOAD_FREE_SPACE_MARGIN_BYTES).coerceAtLeast(0L) }

/** True when [remainingBytes] (or nothing, if unknown) would not fit above the margin. */
internal fun lacksSpaceFor(remainingBytes: Long?, volume: VolumeSpace?): Boolean {
    val usable = usableFreeBytes(volume) ?: return false
    return usable <= 0L || (remainingBytes != null && remainingBytes > usable)
}
