package com.nuvio.app.features.player.skip

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.analysis.MediaAnalysisStatus
import com.nuvio.app.features.analysis.SkipLearning
import com.nuvio.app.features.player.formatPlaybackTime
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

/** One skip mark that travels in a link: show or movie id, episode, segment type and times. */
internal data class SharedSkipMark(
    val parentMetaId: String,
    val season: Int?,
    val episode: Int?,
    val type: String,
    val startMs: Long,
    val endMs: Long,
)

/** nuvio://skip?id=…&s=1&e=2&type=intro&from=…&to=… (times in ms); another Nuvio imports it, no TheIntroDB needed. */
internal fun SharedSkipMark.toLink(): String = buildString {
    append("nuvio://skip?id=").append(parentMetaId.encodeURLParameter())
    season?.let { append("&s=").append(it) }
    episode?.let { append("&e=").append(it) }
    append("&type=").append(type.encodeURLParameter())
    append("&from=").append(startMs)
    append("&to=").append(endMs)
}

/** The mark in a nuvio://skip link, or null when the link is not one or makes no sense. */
internal fun parseSkipMarkLink(url: Url): SharedSkipMark? {
    if (url.protocol.name.lowercase() != "nuvio" || url.host.lowercase() != "skip") return null
    val p = url.parameters
    val id = p["id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val type = p["type"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val start = p["from"]?.toLongOrNull() ?: return null
    val end = p["to"]?.toLongOrNull() ?: return null
    if (start < 0L || end <= start) return null
    return SharedSkipMark(id, p["s"]?.toIntOrNull(), p["e"]?.toIntOrNull(), type, start, end)
}

/** The question before a nuvio://skip link is imported. */
internal fun describeSharedSkipMark(mark: SharedSkipMark): String {
    val where = if (mark.season != null && mark.episode != null) " for S${mark.season}E${mark.episode}" else ""
    return "${mark.type.replaceFirstChar { it.uppercase() }} mark$where (${formatPlaybackTime(mark.startMs)}–${formatPlaybackTime(mark.endMs)}) of ${mark.parentMetaId}. " +
        "It is saved on this device as your own mark; one you already have is kept."
}

/**
 * Saves a shared mark as this episode's mark unless one of that type is already here (own marks
 * win), then lets the season learn from it like an imported TheIntroDB submission.
 */
internal suspend fun importSharedSkipMark(mark: SharedSkipMark) {
    val saved = SkipLearning.importMark(mark.parentMetaId, mark.season, mark.episode, mark.type, mark.startMs, mark.endMs, fromTheIntroDb = false)
    val where = if (mark.season != null && mark.episode != null) " for S${mark.season}E${mark.episode}" else ""
    if (!saved) {
        NuvioToastController.show("You already have a ${mark.type} mark$where")
        return
    }
    if (mark.season != null) {
        val key = com.nuvio.app.features.analysis.MediaAnalysisStore.keyFor(mark.parentMetaId, mark.season, mark.episode)
        runCatching { SkipLearning.teachSeason(mark.parentMetaId, mark.season, key) }
    }
    MediaAnalysisStatus.resultsChanged()
    NuvioToastController.show("Imported the ${mark.type} mark$where")
}
