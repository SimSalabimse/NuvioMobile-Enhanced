package com.nuvio.app.features.home.components

import com.nuvio.app.core.a11y.LongPressSite
import com.nuvio.app.core.a11y.continueWatchingLongPressActions
import com.nuvio.app.core.a11y.installLongPressAccessibility
import com.nuvio.app.core.a11y.offlineTitleLongPressActions
import com.nuvio.app.features.watched.WatchedRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.offline_home_open_downloads
import nuvio.composeapp.generated.resources.offline_home_show_home
import nuvio.composeapp.generated.resources.offline_home_movie
import nuvio.composeapp.generated.resources.offline_home_episodes
import nuvio.composeapp.generated.resources.offline_home_continue
import nuvio.composeapp.generated.resources.offline_home_downloaded
import nuvio.composeapp.generated.resources.offline_home_needs_connection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.network.offlineDownloadsHomeTopPaddingDp
import com.nuvio.app.core.network.offlineHomeActionMinHeightDp
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.platformPhysicalTopInset
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
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

/**
 * Home while there is no internet: a header with actions, Continue Watching from the on-disk cache
 * (downloaded cards play, the rest are dimmed), and every downloaded show or movie as a poster tile.
 * Tapping a show plays its next unwatched downloaded episode; tapping a movie plays it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OfflineDownloadsHome(
    items: List<DownloadItem>,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenDownloads: () -> Unit,
    onShowHome: () -> Unit,
    onTitleLongPress: (DownloadItem) -> Unit = {},
    onResumeLongPress: (OfflineContinueCard) -> Unit = {},
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
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val profileId = profileState.activeProfile?.profileIndex ?: 1
    val cacheGeneration by ContinueWatchingEnrichmentCache.generation.collectAsStateWithLifecycle()
    val continueCache = remember(profileId, progress.source, cacheGeneration) {
        val (nextUp, inProgress) = ContinueWatchingEnrichmentCache.getSnapshots(
            profileId = profileId,
            source = progress.source,
        )
        offlineContinueCacheFromSnapshots(nextUp, inProgress)
    }
    val titles = remember(items) { groupTitles(items) }
    val resume = remember(items, progressByVideo, continueCache) {
        offlineContinueWatchingCards(
            cache = continueCache,
            downloads = items,
            progressByVideoId = progressByVideo,
        )
    }
    val fontScale = LocalDensity.current.fontScale
    val statusTop = platformPhysicalTopInset()
    val actionMin = offlineHomeActionMinHeightDp(fontScale).dp
    val top = offlineDownloadsHomeTopPaddingDp(statusTop.value, fontScale).dp

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.background),
        contentPadding = PaddingValues(top = top, bottom = nuvioSafeBottomPadding(16.dp)),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                    NuvioPrimaryButton(
                        text = stringResource(Res.string.offline_home_open_downloads),
                        modifier = Modifier.weight(1f),
                        minHeight = actionMin,
                        onClick = onOpenDownloads,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = actionMin)
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, colors.textSecondary.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                            .clickable(onClick = onShowHome)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(Res.string.offline_home_show_home),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                        items(resume, key = { it.key }) { entry ->
                            ResumeCard(
                                entry,
                                onClick = { entry.download?.let(onOpenDownload) },
                                onLongClick = { onResumeLongPress(entry) },
                            )
                        }
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
                        onLongClick = {
                            val sample = title.movie ?: title.episodes.firstOrNull()
                            if (sample != null) onTitleLongPress(sample)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterTile(
    title: OfflineTitle,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val sample = title.movie ?: title.episodes.firstOrNull()
    val longPressActions = if (sample == null) {
        emptyList()
    } else {
        offlineTitleLongPressActions(
            id = sample.parentMetaId,
            type = sample.parentMetaType.ifBlank { if (sample.isEpisode) "series" else "movie" },
            watched = WatchedRepository.isWatched(
                id = sample.parentMetaId,
                type = sample.parentMetaType.ifBlank { if (sample.isEpisode) "series" else "movie" },
            ),
            enabled = true,
        )
    }
    Column(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .installLongPressAccessibility(longPressActions, onLongClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResumeCard(
    entry: OfflineContinueCard,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val needsConnection = stringResource(Res.string.offline_home_needs_connection, entry.title)
    val resumeItem = entry.toContinueWatchingItem()
    val longPressActions = if (resumeItem == null) {
        emptyList()
    } else {
        continueWatchingLongPressActions(
            item = resumeItem,
            enabled = true,
            site = LongPressSite.OfflineHomeResume,
        )
    }
    val cardModifier = Modifier
        .width(220.dp)
        .alpha(if (entry.dimmed) 0.4f else 1f)
        .combinedClickable(
            onClick = { if (entry.tappable) onClick() },
            onLongClick = onLongClick,
        )
        .then(
            if (entry.tappable) {
                Modifier.installLongPressAccessibility(longPressActions, onLongClick)
            } else {
                Modifier.clearAndSetSemantics {
                    contentDescription = needsConnection
                    customActions = longPressActions.map { action ->
                        CustomAccessibilityAction(action.label) {
                            onLongClick()
                            true
                        }
                    }
                }
            },
        )
    Column(modifier = cardModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface),
        ) {
            AsyncImage(
                model = entry.imageUrl,
                contentDescription = if (entry.tappable) entry.title else null,
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
            entry.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.seasonNumber != null && entry.episodeNumber != null) {
            Text(
                "S${entry.seasonNumber} E${entry.episodeNumber}" + (entry.episodeTitle?.let { " · $it" } ?: ""),
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

private fun nextEpisode(episodes: List<DownloadItem>, progress: Map<String, WatchProgressEntry>): DownloadItem? =
    episodes.firstOrNull { progress[it.videoId]?.isCompleted != true } ?: episodes.firstOrNull()
