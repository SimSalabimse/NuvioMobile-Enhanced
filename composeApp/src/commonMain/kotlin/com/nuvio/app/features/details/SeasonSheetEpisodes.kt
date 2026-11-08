package com.nuvio.app.features.details

import com.nuvio.app.features.player.skip.OwnAccountMarks

internal data class LedgerEpisodeIdentity(
    val imdbId: String,
    val season: Int,
    val episode: Int,
    val contentKey: String,
)

/** A ledger key is an episode only when it is `tt…:season:episode`. Anything else is ignored. */
internal fun parseLedgerEpisodeKey(contentKey: String): LedgerEpisodeIdentity? {
    val parts = contentKey.trim().lowercase().split(":")
    if (parts.size != 3) return null
    val imdb = parts[0]
    if (!imdb.startsWith("tt")) return null
    val season = parts[1].toIntOrNull() ?: return null
    val episode = parts[2].toIntOrNull() ?: return null
    if (episode <= 0) return null
    return LedgerEpisodeIdentity(imdb, season, episode, "$imdb:$season:$episode")
}

/**
 * Rows come from the catalogue plus this account's ledger for the same show and season.
 * A download that is not in either source does not become a row. A key that is not an episode does not either.
 */
internal fun seasonSheetEpisodes(
    catalog: List<MetaVideo>,
    ledger: OwnAccountMarks,
    showImdbIds: Set<String>,
    season: Int,
): List<MetaVideo> {
    val showIds = showImdbIds.map { it.trim().lowercase() }.filter { it.startsWith("tt") }.toSet()
    val fromCatalog = catalog
        .filter { (it.season ?: 0) == season && it.episode != null }
        .sortedBy { it.episode }
    val seen = fromCatalog.mapNotNull { it.episode }.toMutableSet()
    val extras = mutableListOf<MetaVideo>()
    val keys = ledger.confirmed.keys + ledger.pending.keys
    keys.mapNotNull(::parseLedgerEpisodeKey)
        .filter { it.season == season && it.imdbId in showIds && it.episode !in seen }
        .sortedBy { it.episode }
        .forEach { identity ->
            seen += identity.episode
            extras += MetaVideo(
                id = identity.contentKey,
                title = "",
                season = identity.season,
                episode = identity.episode,
                imdbId = identity.imdbId,
            )
        }
    return fromCatalog + extras
}

internal fun showImdbIds(meta: MetaDetails): Set<String> = buildSet {
    meta.imdbId?.let { add(it) }
    if (meta.id.startsWith("tt", ignoreCase = true)) add(meta.id)
    meta.videos.forEach { video ->
        video.imdbId?.let { add(it) }
        video.id.split(":").firstOrNull()?.let { add(it) }
    }
}

internal fun episodeContentKeyFor(meta: MetaDetails, video: MetaVideo): String {
    val episode = video.episode ?: return ""
    return seasonEpisodeContentKey(
        metaId = meta.id,
        imdbId = meta.imdbId,
        videoId = video.id,
        videoImdbId = video.imdbId,
        season = video.season ?: 0,
        episode = episode,
    )
}

/** The sheet's swipe action is invisible until the row actually moves. */
internal fun seasonSwipeRevealsAction(currentSettled: Boolean, targetSettled: Boolean): Boolean =
    !currentSettled || !targetSettled
