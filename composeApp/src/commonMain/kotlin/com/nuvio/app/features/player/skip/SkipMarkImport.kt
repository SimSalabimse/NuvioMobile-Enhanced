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
        val error = TheIntroDb.lastListError
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
        SkipImportStatus.record(found = submissions.size, imported = imported, error = error)
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

/**
 * The last TheIntroDB import, shown in the skip settings: "Imported 12 of your TheIntroDB
 * submissions · 5 min ago", or the error when the key was refused.
 */
internal object SkipImportStatus {
    private const val KEY = "skip_import_status"

    data class Status(
        val atMs: Long,
        val found: Int,
        val imported: Int,
        val totalImported: Int,
        val error: String?,
    )

    private val state = kotlinx.coroutines.flow.MutableStateFlow<Status?>(null)
    private var loaded = false

    val status: kotlinx.coroutines.flow.StateFlow<Status?>
        get() {
            ensureLoaded()
            return state
        }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        state.value = com.nuvio.app.features.downloads.DownloadsSettingsStorage.loadString(KEY)?.let(::decode)
    }

    fun record(found: Int, imported: Int, error: String?) {
        ensureLoaded()
        val total = (state.value?.totalImported ?: 0) + imported
        val next = Status(com.nuvio.app.features.streams.epochMs(), found, imported, total, error)
        state.value = next
        com.nuvio.app.features.downloads.DownloadsSettingsStorage.saveString(
            KEY,
            listOf(next.atMs, next.found, next.imported, next.totalImported, next.error.orEmpty()).joinToString("|"),
        )
    }

    private fun decode(raw: String): Status? {
        val parts = raw.split("|", limit = 5)
        if (parts.size < 5) return null
        return Status(
            atMs = parts[0].toLongOrNull() ?: return null,
            found = parts[1].toIntOrNull() ?: 0,
            imported = parts[2].toIntOrNull() ?: 0,
            totalImported = parts[3].toIntOrNull() ?: 0,
            error = parts[4].takeIf { it.isNotBlank() },
        )
    }

    fun describe(status: Status?, nowMs: Long): String {
        if (status == null) return "Not run yet. It runs when you play a show with your TheIntroDB key set."
        val ago = agoText(nowMs - status.atMs)
        status.error?.let { return "$it · $ago" }
        val last = when {
            status.imported > 0 -> "Imported ${status.imported} of your TheIntroDB submissions"
            status.found > 0 -> "${status.found} submissions found, all already on the phone"
            else -> "No submissions of yours for that title"
        }
        return "$last · $ago · ${status.totalImported} imported in total"
    }

    private fun agoText(ms: Long): String {
        val min = (ms / 60_000L).coerceAtLeast(0L)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }
}
