package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults

internal actual object DownloadsLiveStatusPlatform {
    private const val notificationName = "NuvioDownloadsLiveStatusUpdated"
    private const val userDefaultsPayloadKey = "nuvio.downloads.live_status.payload"

    private val json = Json {
        encodeDefaults = true
    }

    private var lastPayload: String? = null
    private var published = false

    actual fun onItemsChanged(items: List<DownloadItem>) {
        // Only a running transfer has a Live Activity. A paused or failed row left in the list used to
        // become the "primary" item after a cancel and got an activity that nothing ever updated or
        // ended, which is what showed up stuck on the Lock Screen once the app was left.
        val payload = liveStatusPayloadFor(items)?.let { json.encodeToString(it) }

        // The first call always writes: the stored payload may be left over from an earlier launch.
        if (published && payload == lastPayload) return
        published = true
        lastPayload = payload

        val defaults = NSUserDefaults.standardUserDefaults
        if (payload == null) {
            defaults.removeObjectForKey(userDefaultsPayloadKey)
        } else {
            defaults.setObject(payload, forKey = userDefaultsPayloadKey)
        }

        NSNotificationCenter.defaultCenter.postNotificationName(notificationName, null)
    }
}

/**
 * One Live Activity at most, still only for running transfers. The oldest running transfer picks
 * it. When that is an episode, every running episode of the same show and season shares it,
 * lowest first ("S1 · E3 + E4 · 2 downloading") with their bytes and percent combined, and the
 * activity is keyed by the season so E3+E4 becoming E4+E5 updates it instead of replacing it.
 */
internal fun liveStatusPayloadFor(items: List<DownloadItem>): DownloadsLiveStatusPayload? {
    val running = items.filter { it.status == DownloadStatus.Downloading }
    val anchor = running.minByOrNull { it.createdAtEpochMs } ?: return null
    val season = anchor.seasonNumber
    val members = if (anchor.isEpisode && season != null) {
        running
            .filter { it.isEpisode && it.parentMetaId == anchor.parentMetaId && it.seasonNumber == season }
            .sortedBy { it.episodeNumber ?: 0 }
    } else {
        listOf(anchor)
    }
    val sized = members.filter { (it.totalBytes ?: 0L) > 0L }
    val allSized = sized.size == members.size
    val downloaded = members.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
    val total = if (allSized) sized.sumOf { it.totalBytes ?: 0L } else null
    val percent = if (sized.isNotEmpty()) {
        val sizedTotal = sized.sumOf { it.totalBytes ?: 0L }
        val sizedDone = sized.sumOf { it.downloadedBytes.coerceIn(0L, it.totalBytes ?: 0L) }
        ((sizedDone.toDouble() / sizedTotal.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    } else {
        -1
    }
    val detail = if (anchor.isEpisode && season != null) {
        if (members.size >= 2) {
            "S$season · " + members.joinToString(" + ") { "E${it.episodeNumber}" } + " · ${members.size} downloading"
        } else {
            listOfNotNull(
                "S${season}E${anchor.episodeNumber}",
                anchor.displaySubtitle.trim().takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }
    } else {
        null
    }
    return DownloadsLiveStatusPayload(
        id = if (anchor.isEpisode && season != null) "season:${anchor.parentMetaId.trim()}|$season" else anchor.id,
        title = anchor.title,
        subtitle = if (anchor.isEpisode && season != null) "S$season" else anchor.displaySubtitle,
        status = anchor.status.name,
        downloadedBytes = downloaded,
        totalBytes = total,
        progressPercent = percent,
        detail = detail,
    )
}

@Serializable
internal data class DownloadsLiveStatusPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val progressPercent: Int,
    val detail: String? = null,
)
