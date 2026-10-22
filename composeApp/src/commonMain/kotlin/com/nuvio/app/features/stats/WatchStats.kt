package com.nuvio.app.features.stats

import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.streams.epochMs
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import com.nuvio.app.core.serialization.LenientJson

@Serializable
internal data class ShowStat(
    val title: String,
    val poster: String? = null,
    val type: String = "series",
    val watchedMs: Long = 0L,
    val completed: Int = 0,
)

/** Everything local: per-day watch time, per-title totals, per-year totals and completions. */
@Serializable
internal data class WatchStatsData(
    val dayMs: Map<String, Long> = emptyMap(),
    val shows: Map<String, ShowStat> = emptyMap(),
    val yearShowMs: Map<String, Map<String, Long>> = emptyMap(),
    val yearCompleted: Map<String, Int> = emptyMap(),
    /** Time jumped over by intro/recap/credits skips, per day. */
    val daySkippedMs: Map<String, Long> = emptyMap(),
    /** Titles with an episode or movie completed that day (for "series finished this week"). */
    val dayCompletedShows: Map<String, Set<String>> = emptyMap(),
)

/** The last seven days: watch time, series finished and time saved by skipping. */
internal data class WeeklyRecap(
    val watchedMs: Long,
    val finishedSeries: List<String>,
    val skippedMs: Long,
)

internal data class WatchStatsSummary(
    val totalMs: Long,
    val yearMs: Long,
    val todayMs: Long,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val daysWatchedThisYear: Int,
    val topShows: List<Pair<String, ShowStat>>,
    val yearTopShows: List<Pair<ShowStat, Long>>,
    val yearCompleted: Int,
    val last7Days: List<Pair<String, Long>>,
    val year: String,
)

/**
 * Local watch statistics from the player's progress updates: the position moving forward at
 * about playback speed counts as watched time (seeks and jumps do not). Stored on this device only.
 */
internal object WatchStats {
    private const val KEY = "watch_stats_v1"
    private const val SAVE_EVERY_MS = 60_000L
    /** A day counts towards a streak with at least this much watched. */
    /** Any watching on a day counts for the streak (a watched day is a streak of at least 1). */
    const val STREAK_DAY_MIN_MS = 1L
    private const val MAX_SKIP_MS = 30 * 60_000L

    private val json = LenientJson
    private val _data = MutableStateFlow(WatchStatsData())
    val data: StateFlow<WatchStatsData> = _data.asStateFlow()
    private var loaded = false
    private var lastSavedAt = 0L
    private var lastVideoId: String? = null
    private var lastPositionMs = 0L
    private var lastWallMs = 0L
    private val completedVideoIds = mutableSetOf<String>()

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _data.value = DownloadsSettingsStorage.loadString(KEY)
            ?.let { runCatching { json.decodeFromString(WatchStatsData.serializer(), it) }.getOrNull() }
            ?: WatchStatsData()
    }

    fun record(entry: WatchProgressEntry) {
        ensureLoaded()
        val now = epochMs()
        val position = entry.lastPositionMs
        var watched = 0L
        if (lastVideoId == entry.videoId && lastWallMs > 0L) {
            val wall = now - lastWallMs
            val moved = position - lastPositionMs
            // Forward at up to 2x speed (plus slack) is watching; anything else is a seek.
            if (moved > 0L && wall > 0L && moved <= wall * 2 + 5_000L) watched = minOf(moved, wall + 2_000L)
        }
        lastVideoId = entry.videoId
        lastPositionMs = position
        lastWallMs = now
        val completedNow = entry.isCompleted && completedVideoIds.add(entry.videoId)
        if (watched <= 0L && !completedNow) return
        val day = CurrentDateProvider.todayIsoDate()
        val year = day.take(4)
        val showId = entry.parentMetaId
        val current = _data.value
        val show = current.shows[showId] ?: ShowStat(title = entry.title, poster = entry.poster, type = entry.parentMetaType)
        val yearShows = current.yearShowMs[year].orEmpty()
        _data.value = current.copy(
            dayMs = current.dayMs + (day to (current.dayMs[day] ?: 0L) + watched),
            shows = current.shows + (
                showId to show.copy(
                    title = entry.title.ifBlank { show.title },
                    poster = entry.poster ?: show.poster,
                    watchedMs = show.watchedMs + watched,
                    completed = show.completed + if (completedNow) 1 else 0,
                )
            ),
            yearShowMs = current.yearShowMs + (year to yearShows + (showId to (yearShows[showId] ?: 0L) + watched)),
            yearCompleted = if (completedNow) current.yearCompleted + (year to (current.yearCompleted[year] ?: 0) + 1) else current.yearCompleted,
            dayCompletedShows = if (completedNow) {
                current.dayCompletedShows + (day to current.dayCompletedShows[day].orEmpty() + showId)
            } else current.dayCompletedShows,
        )
        if (completedNow || now - lastSavedAt >= SAVE_EVERY_MS) save(now)
    }

    /** A skip jumped [savedMs] ahead; anything over [MAX_SKIP_MS] is a seek, not a skip. */
    fun recordSkip(savedMs: Long) {
        if (savedMs !in 1L..MAX_SKIP_MS) return
        ensureLoaded()
        val day = CurrentDateProvider.todayIsoDate()
        val current = _data.value
        _data.value = current.copy(daySkippedMs = current.daySkippedMs + (day to (current.daySkippedMs[day] ?: 0L) + savedMs))
        save(epochMs())
    }

    /** [isSeriesFinished] tells whether a show id (with its type) is now fully watched. */
    fun weeklyRecap(
        data: WatchStatsData,
        isSeriesFinished: (id: String, type: String) -> Boolean,
        today: String = CurrentDateProvider.todayIsoDate(),
    ): WeeklyRecap {
        val todayNumber = isoDayNumber(today) ?: 0
        val days = (0..6).map { dayFromNumber(todayNumber - it) }
        val finished = days.flatMap { data.dayCompletedShows[it].orEmpty() }.distinct().mapNotNull { id ->
            data.shows[id]?.takeIf { it.type != "movie" && isSeriesFinished(id, it.type) }?.title
        }
        return WeeklyRecap(
            watchedMs = days.sumOf { data.dayMs[it] ?: 0L },
            finishedSeries = finished,
            skippedMs = days.sumOf { data.daySkippedMs[it] ?: 0L },
        )
    }

    fun flush() {
        if (loaded) save(epochMs())
    }

    private fun save(now: Long) {
        lastSavedAt = now
        DownloadsSettingsStorage.saveString(KEY, json.encodeToString(WatchStatsData.serializer(), _data.value))
    }

    fun summarize(data: WatchStatsData, today: String = CurrentDateProvider.todayIsoDate()): WatchStatsSummary {
        val year = today.take(4)
        val streakDays = data.dayMs.filterValues { it >= STREAK_DAY_MIN_MS }.keys
        val dayNumbers = streakDays.mapNotNull { isoDayNumber(it) }.distinct().sorted()
        val todayNumber = isoDayNumber(today) ?: 0
        var current = 0
        // Today without watching yet does not break a streak that ran until yesterday.
        var cursor = if (todayNumber in dayNumbers) todayNumber else todayNumber - 1
        while (cursor in dayNumbers) {
            current++
            cursor--
        }
        var longest = 0
        var run = 0
        var previous: Int? = null
        for (n in dayNumbers) {
            run = if (previous != null && n == previous + 1) run + 1 else 1
            longest = maxOf(longest, run)
            previous = n
        }
        val yearShows = data.yearShowMs[year].orEmpty()
        return WatchStatsSummary(
            totalMs = data.dayMs.values.sum(),
            yearMs = data.dayMs.filterKeys { it.startsWith(year) }.values.sum(),
            todayMs = data.dayMs[today] ?: 0L,
            currentStreakDays = current,
            longestStreakDays = longest,
            daysWatchedThisYear = data.dayMs.count { (day, ms) -> day.startsWith(year) && ms > 0L },
            topShows = data.shows.entries.sortedByDescending { it.value.watchedMs }.take(5).map { it.key to it.value },
            yearTopShows = yearShows.entries.sortedByDescending { it.value }.take(3)
                .mapNotNull { (id, ms) -> data.shows[id]?.let { it to ms } },
            yearCompleted = data.yearCompleted[year] ?: 0,
            last7Days = (6 downTo 0).map { back ->
                val label = dayFromNumber(todayNumber - back)
                label to (data.dayMs[label] ?: 0L)
            },
            year = year,
        )
    }
}

/** Days since 1970-01-01 for an ISO date (proleptic Gregorian), null when it does not parse. */
internal fun isoDayNumber(iso: String): Int? {
    val parts = iso.take(10).split('-')
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    val yy = if (m <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val mp = (m + 9) % 12
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

internal fun dayFromNumber(days: Int): String {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
