package com.nuvio.app.features.home.components

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.offline_home_title
import nuvio.composeapp.generated.resources.offline_home_message
import nuvio.composeapp.generated.resources.offline_home_open_downloads
import nuvio.composeapp.generated.resources.offline_home_show_home
import nuvio.composeapp.generated.resources.offline_home_movie
import nuvio.composeapp.generated.resources.offline_home_episodes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.features.downloads.DownloadItem
import org.jetbrains.compose.resources.stringResource

/**
 * Home while there is no network: what is downloaded comes first, grouped by title, newest first,
 * with artwork from the offline copies (see OfflineArtwork). Tapping an item plays it.
 */
@Composable
internal fun OfflineDownloadsHome(
    items: List<DownloadItem>,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenDownloads: () -> Unit,
    onShowHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = items
        .groupBy { it.parentMetaId }
        .values
        .sortedByDescending { group -> group.maxOf { it.updatedAtEpochMs } }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = top + 16.dp, bottom = 120.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(Res.string.offline_home_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(Res.string.offline_home_message), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenDownloads) { Text(stringResource(Res.string.offline_home_open_downloads)) }
                    TextButton(onClick = onShowHome) { Text(stringResource(Res.string.offline_home_show_home)) }
                }
            }
        }
        items(groups, key = { it.first().parentMetaId }) { group ->
            val first = group.first()
            val episodes = group.filter { it.isEpisode }.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = episodes.isEmpty() && first.isPlayable) { onOpenDownload(first) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AsyncImage(
                        model = first.poster ?: first.background,
                        contentDescription = first.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(72.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            first.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (episodes.isEmpty()) stringResource(Res.string.offline_home_movie)
                            else stringResource(Res.string.offline_home_episodes, episodes.size),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                episodes.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = item.isPlayable) { onOpenDownload(item) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(112.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            AsyncImage(
                                model = item.episodeThumbnail ?: item.background ?: item.poster,
                                contentDescription = item.episodeTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                localizedSeasonEpisodeCode(item.seasonNumber, item.episodeNumber).orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                item.episodeTitle.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
