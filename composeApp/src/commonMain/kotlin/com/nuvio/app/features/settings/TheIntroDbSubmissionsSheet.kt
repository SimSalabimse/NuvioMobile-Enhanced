package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.deeplink.AppDeepLinkRepository
import com.nuvio.app.core.deeplink.buildMetaDeepLinkUrl
import com.nuvio.app.core.ui.NuvioInfoBadge
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.player.formatPlaybackTime
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.TheIntroDbUserSubmission
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbTitleCard

internal enum class SubmissionStatus(val label: String, val color: Color) {
    Accepted("Accepted", Color(0xFF2E9E5B)),
    Pending("Pending", Color(0xFFE0A100)),
    Rejected("Rejected", Color(0xFFD64545)),
}

internal fun TheIntroDbUserSubmission.statusKind(): SubmissionStatus {
    val value = status?.lowercase().orEmpty()
    return when {
        "reject" in value || "denied" in value -> SubmissionStatus.Rejected
        "accept" in value || "approv" in value || "verified" in value -> SubmissionStatus.Accepted
        else -> SubmissionStatus.Pending
    }
}

internal data class EpisodeSubmissions(val season: Int?, val episode: Int?, val submissions: List<TheIntroDbUserSubmission>)

internal data class ShowSubmissions(
    val key: String,
    val tmdbId: Int?,
    val tvdbId: Int?,
    val mediaType: String,
    val episodes: List<EpisodeSubmissions>,
)

/** One group per show or movie (first seen first), episodes in order. */
internal fun groupSubmissions(rows: List<TheIntroDbUserSubmission>): List<ShowSubmissions> =
    rows.groupBy { row -> row.tmdbId?.let { "tmdb:$it" } ?: row.tvdbId?.let { "tvdb:$it" } ?: "unknown" }
        .map { (key, list) ->
            val first = list.first()
            ShowSubmissions(
                key = key,
                tmdbId = first.tmdbId,
                tvdbId = first.tvdbId,
                mediaType = if (first.type.equals("movie", ignoreCase = true)) "movie" else "tv",
                episodes = list.groupBy { it.season to it.episode }
                    .map { (at, subs) -> EpisodeSubmissions(at.first, at.second, subs) }
                    .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })),
            )
        }

internal fun statusTotals(rows: List<TheIntroDbUserSubmission>): Map<SubmissionStatus, Int> =
    rows.groupingBy { it.statusKind() }.eachCount()

/** "Intro 00:30–01:45" (no start: from 0, no end: to the end). */
internal fun segmentChipText(submission: TheIntroDbUserSubmission): String {
    val name = submission.segment.orEmpty().replaceFirstChar { it.uppercase() }
    val start = formatPlaybackTime(submission.startMs ?: 0L)
    val end = submission.endMs?.let(::formatPlaybackTime) ?: "end"
    return "$name $start–$end"
}

/**
 * Settings > Playback > Your TheIntroDB submissions: grouped by show (name and poster from TMDB),
 * a card per episode with its segments and review status. Pull down to refresh; tap to open the show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TheIntroDbSubmissionsSheet(apiKey: String, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf<List<TheIntroDbUserSubmission>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    val cards = remember { mutableStateMapOf<String, TmdbTitleCard?>() }
    LaunchedEffect(apiKey, reload) {
        loading = true
        rows = TheIntroDb.listMySubmissions(apiKey)
        error = TheIntroDb.lastListError
        loading = false
    }
    val groups = remember(rows) { groupSubmissions(rows.orEmpty()) }
    LaunchedEffect(groups) {
        groups.filter { it.key !in cards }.forEach { group ->
            cards[group.key] = runCatching { TmdbService.titleCard(group.tmdbId, group.tvdbId, group.mediaType) }.getOrNull()
        }
    }
    fun open(group: ShowSubmissions) {
        val card = cards[group.key]
        val id = card?.imdbId ?: (card?.tmdbId ?: group.tmdbId)?.let { "tmdb:$it" } ?: return
        onDismiss()
        AppDeepLinkRepository.handleUrl(buildMetaDeepLinkUrl(if (group.mediaType == "movie") "movie" else "series", id))
    }
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PullToRefreshBox(isRefreshing = loading && rows != null, onRefresh = { reload++ }) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text("Your TheIntroDB submissions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
                    val totals = statusTotals(rows.orEmpty())
                    if (totals.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SubmissionStatus.entries.forEach { kind ->
                                totals[kind]?.let { StatusPill(kind, "$it ${kind.label.lowercase()}") }
                            }
                        }
                    }
                }
                val loaded = rows
                when {
                    loaded == null -> item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { NuvioLoadingIndicator() }
                    }
                    error != null || loaded.isEmpty() -> item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = error ?: "No submissions yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (error != null) "Pull down or tap Retry." else "Mark a segment with the flag button in the player to send one.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.nuvio.colors.textSecondary,
                            )
                            if (error != null) TextButton(onClick = { reload++ }) { Text("Retry") }
                        }
                    }
                }
                groups.forEach { group ->
                    item(key = "show:" + group.key) { ShowHeader(group, cards[group.key]) { open(group) } }
                    items(group.episodes, key = { "ep:${group.key}:${it.season}:${it.episode}" }) { episode ->
                        EpisodeSubmissionCard(episode) { open(group) }
                    }
                }
                item { Spacer(Modifier.height(nuvioSafeBottomPadding(16.dp))) }
            }
        }
    }
}

@Composable
private fun ShowHeader(group: ShowSubmissions, card: TmdbTitleCard?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 40.dp, height = 60.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.nuvio.colors.surfaceCard)) {
            card?.posterUrl?.let { AsyncImage(model = it, contentDescription = card.name, contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = card?.name ?: group.tmdbId?.let { "TMDB $it" } ?: group.tvdbId?.let { "TVDB $it" } ?: "Unknown title",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val count = group.episodes.sumOf { it.submissions.size }
            Text("$count submission${if (count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
        }
    }
}

@Composable
private fun EpisodeSubmissionCard(episode: EpisodeSubmissions, onClick: () -> Unit) {
    NuvioSurfaceCard(modifier = Modifier.clickable(onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (episode.season != null && episode.episode != null) {
                NuvioInfoBadge("S${episode.season}E${episode.episode}")
            }
            episode.submissions.forEach { submission ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioInfoBadge(segmentChipText(submission), Modifier.weight(1f, fill = false).padding(end = 8.dp))
                    val kind = submission.statusKind()
                    StatusPill(kind, kind.label)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(kind: SubmissionStatus, text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(kind.color).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
