package com.nuvio.app.features.home.components

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.offline_home_title
import nuvio.composeapp.generated.resources.offline_home_message
import nuvio.composeapp.generated.resources.offline_home_open_downloads
import nuvio.composeapp.generated.resources.offline_home_show_home
import nuvio.composeapp.generated.resources.offline_home_movie
import nuvio.composeapp.generated.resources.offline_home_episodes
import nuvio.composeapp.generated.resources.offline_home_continue
import nuvio.composeapp.generated.resources.offline_home_downloaded

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import nuvio.composeapp.generated.resources.offline_home_episodes_count

private data class OfflineTitle(
    val key: String,
    val title: String,
    val poster: String?,
    val isShow: Boolean,
    val episodes: List<DownloadItem>,
    val movie: DownloadItem?,
    val updatedAt: Long,
)

private data class OfflineResume(val item: DownloadItem, val fraction: Float)

/**
 * Home while there is no internet: a header with actions, a "Continue watching" row from what is
 * downloaded and in progress, and every downloaded show or movie as a poster tile. Tapping a show
 * plays its next unwatched downloaded episode; tapping a movie plays it.
 */
@Composable
internal fun OfflineDownloadsHome(
    items: List<DownloadItem>,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenDownloads: () -> Unit,
    onShowHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val progress by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val progressByVideo = remember(progress.entries) {
        progress.entries.groupBy { it.videoId }.mapValues { (_, list) -> list.maxBy { it.lastUpdatedEpochMs } }
    }
    val titles = remember(items) { groupTitles(items) }
    val resume = remember(items, progressByVideo) { resumeItems(items, progressByVideo) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.background),
        contentPadding = PaddingValues(top = top + 20.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "header") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(Res.string.offline_home_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        Text(
                            stringResource(Res.string.offline_home_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NuvioPrimaryButton(
                        text = stringResource(Res.string.offline_home_open_downloads),
                        modifier = Modifier.weight(1f),
                        onClick = onOpenDownloads,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, colors.textSecondary.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                            .clickable(onClick = onShowHome),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(Res.string.offline_home_show_home),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }

        if (resume.isNotEmpty()) {
            item(key = "resume") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(stringResource(Res.string.offline_home_continue))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(resume, key = { it.item.id }) { entry -> ResumeCard(entry, onClick = { onOpenDownload(entry.item) }) }
                    }
                }
            }
        }

        item(key = "downloaded-header") { SectionHeader(stringResource(Res.string.offline_home_downloaded)) }
        val rows = titles.chunked(3)
        items(rows.size, key = { "row-$it" }) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rows[index].forEach { title ->
                    PosterTile(
                        title = title,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val target = title.movie ?: nextEpisode(title.episodes, progressByVideo)
                            if (target != null && target.isPlayable) onOpenDownload(target) else onOpenDownloads()
                        },
                    )
                }
                repeat(3 - rows[index].size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.nuvio.colors.textPrimary,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

@Composable
private fun PosterTile(title: OfflineTitle, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(modifier = modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null, tint = colors.textSecondary.copy(alpha = 0.4f))
            AsyncImage(
                model = title.poster,
                contentDescription = title.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            title.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (title.isShow) pluralStringResource(Res.plurals.offline_home_episodes_count, title.episodes.size, title.episodes.size)
            else stringResource(Res.string.offline_home_movie),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ResumeCard(entry: OfflineResume, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val item = entry.item
    Column(modifier = Modifier.width(220.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface),
        ) {
            AsyncImage(
                model = item.episodeThumbnail ?: item.background ?: item.poster,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
            )
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(entry.fraction.coerceIn(0f, 1f)).fillMaxHeight().background(colors.accent))
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.seasonNumber != null && item.episodeNumber != null) {
            Text(
                "S${item.seasonNumber} E${item.episodeNumber}" + (item.episodeTitle?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun groupTitles(items: List<DownloadItem>): List<OfflineTitle> =
    items.groupBy { it.parentMetaId.trim() + if (it.isEpisode) "|show" else "|movie:" + it.id }
        .map { (key, group) ->
            val first = group.first()
            val episodes = group.filter { it.isEpisode }
                .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
            OfflineTitle(
                key = key,
                title = first.title,
                // Show tiles use the poster, never an episode still.
                poster = group.firstNotNullOfOrNull { it.poster } ?: first.background,
                isShow = episodes.isNotEmpty(),
                episodes = episodes,
                movie = first.takeIf { !it.isEpisode },
                updatedAt = group.maxOf { it.updatedAtEpochMs },
            )
        }
        .sortedByDescending { it.updatedAt }

private fun WatchProgressEntry.fraction(): Float =
    if (durationMs > 0L) (lastPositionMs.toFloat() / durationMs.toFloat()) else 0f

private fun resumeItems(items: List<DownloadItem>, progress: Map<String, WatchProgressEntry>): List<OfflineResume> =
    items.mapNotNull { item ->
        val entry = progress[item.videoId] ?: return@mapNotNull null
        if (entry.isCompleted) return@mapNotNull null
        val fraction = entry.fraction()
        if (fraction < 0.02f || fraction > 0.95f) return@mapNotNull null
        OfflineResume(item, fraction) to entry.lastUpdatedEpochMs
    }.sortedByDescending { it.second }.map { it.first }.take(10)

private fun nextEpisode(episodes: List<DownloadItem>, progress: Map<String, WatchProgressEntry>): DownloadItem? =
    episodes.firstOrNull { progress[it.videoId]?.isCompleted != true } ?: episodes.firstOrNull()
