package com.nuvio.app.features.player.skip

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.analysis.MediaAnalysisStatus
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.analysis.SkipLearning
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.tmdb.TmdbService

/**
 * Brings the user's earlier TheIntroDB submissions for a show onto the phone as their own marks,
 * so they are used for skipping and as trusted references for the on-device detector.
 * Runs at most once per show per app session. Rejected submissions and ones without times
 * are skipped; marks already on the phone are kept.
 */
internal object SkipMarkImport {
    private val done = mutableSetOf<String>()

    suspend fun importForShow(parentMetaId: String, videoId: String?, isMovie: Boolean): Int {
        if (parentMetaId.isBlank() || !done.add(parentMetaId)) return 0
        val settings = PlayerSettingsRepository.uiState.value
        val key = savedKeyForService(IntroSubmitService.THE_INTRODB, settings.introDbApiKey, settings.theIntroDbApiKey)
        if (key.isBlank()) return 0
        val mediaType = if (isMovie) "movie" else "tv"
        val tmdbId = runCatching {
            TmdbService.ensureTmdbId(
                videoId = videoId?.takeIf { it.isNotBlank() } ?: parentMetaId,
                mediaType = mediaType,
                fallbackImdbId = parentMetaId.takeIf { it.startsWith("tt", ignoreCase = true) },
            )?.toIntOrNull()
        }.getOrNull() ?: return 0
        val submissions = TheIntroDb.listMySubmissions(key, tmdbId, mediaType)
        var imported = 0
        val seasons = mutableSetOf<Int>()
        for (submission in submissions) {
            if (submission.status.equals("rejected", ignoreCase = true)) continue
            val segment = submission.segment ?: continue
            val start = submission.startMs ?: 0L
            val end = submission.endMs ?: submission.videoDurationMs ?: continue
            val season = if (isMovie) null else submission.season ?: continue
            val episode = if (isMovie) null else submission.episode ?: continue
            if (SkipLearning.importMark(parentMetaId, season, episode, segment, start, end)) {
                imported++
                season?.let { seasons += it }
            }
        }
        InAppLogger.info("SkipLearning", "import tmdb=$tmdbId submissions=${submissions.size} imported=$imported")
        if (imported == 0) return 0
        // Each newly imported episode with fingerprints teaches its season.
        for (season in seasons) {
            submissions.filter { it.season == season }.mapNotNull { it.episode }.toSet().forEach { episode ->
                SkipLearning.teachSeason(parentMetaId, season, MediaAnalysisStore.keyFor(parentMetaId, season, episode))
            }
        }
        MediaAnalysisStatus.resultsChanged()
        return imported
    }
}
