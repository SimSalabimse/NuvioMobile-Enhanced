package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.perf.PerformanceCounter
import com.nuvio.app.core.perf.PerformanceMetrics
import com.nuvio.app.core.perf.PerformanceOptimization
import com.nuvio.app.core.perf.PerformanceSettings
import com.nuvio.app.core.perf.PerformanceSnapshot
import com.nuvio.app.core.perf.currentProcessMemoryBytes
import com.nuvio.app.core.perf.rememberAppBinarySizeBytes
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.formatDownloadBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.performance_item_cached_home
import nuvio.composeapp.generated.resources.performance_item_cached_home_desc
import nuvio.composeapp.generated.resources.performance_item_capped_caches
import nuvio.composeapp.generated.resources.performance_item_capped_caches_desc
import nuvio.composeapp.generated.resources.performance_item_capped_caches_readout
import nuvio.composeapp.generated.resources.performance_item_image_compression
import nuvio.composeapp.generated.resources.performance_item_image_compression_desc
import nuvio.composeapp.generated.resources.performance_item_image_compression_readout
import nuvio.composeapp.generated.resources.performance_item_next_episode
import nuvio.composeapp.generated.resources.performance_item_next_episode_desc
import nuvio.composeapp.generated.resources.performance_item_next_episode_readout
import nuvio.composeapp.generated.resources.performance_item_parallel_startup
import nuvio.composeapp.generated.resources.performance_item_parallel_startup_desc
import nuvio.composeapp.generated.resources.performance_item_poster_downsampling
import nuvio.composeapp.generated.resources.performance_item_poster_downsampling_desc
import nuvio.composeapp.generated.resources.performance_item_poster_downsampling_readout
import nuvio.composeapp.generated.resources.performance_item_quiet_playback
import nuvio.composeapp.generated.resources.performance_item_quiet_playback_desc
import nuvio.composeapp.generated.resources.performance_item_quiet_playback_readout
import nuvio.composeapp.generated.resources.performance_item_request_dedupe
import nuvio.composeapp.generated.resources.performance_item_request_dedupe_desc
import nuvio.composeapp.generated.resources.performance_item_request_dedupe_readout
import nuvio.composeapp.generated.resources.performance_item_strip_symbols
import nuvio.composeapp.generated.resources.performance_item_strip_symbols_note
import nuvio.composeapp.generated.resources.performance_readout_app_size
import nuvio.composeapp.generated.resources.performance_readout_image_cache
import nuvio.composeapp.generated.resources.performance_readout_launch
import nuvio.composeapp.generated.resources.performance_readout_memory
import nuvio.composeapp.generated.resources.performance_readout_not_measured
import nuvio.composeapp.generated.resources.performance_readout_startup_preload
import nuvio.composeapp.generated.resources.performance_readout_time_to_playback
import nuvio.composeapp.generated.resources.performance_readout_time_to_streams
import nuvio.composeapp.generated.resources.performance_section_optimizations
import nuvio.composeapp.generated.resources.performance_section_readouts
import nuvio.composeapp.generated.resources.performance_value_cache_usage
import nuvio.composeapp.generated.resources.performance_value_launch_cached
import nuvio.composeapp.generated.resources.performance_value_ms
import nuvio.composeapp.generated.resources.performance_value_preload_parallel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val MemoryPollIntervalMs = 2_000L

internal fun LazyListScope.performanceSettingsContent(
    isTablet: Boolean,
) {
    item {
        val metrics by PerformanceMetrics.snapshot.collectAsStateWithLifecycle()
        SettingsSection(
            title = stringResource(Res.string.performance_section_readouts),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                PerformanceReadouts(metrics = metrics, isTablet = isTablet)
            }
        }
    }

    item {
        val metrics by PerformanceMetrics.snapshot.collectAsStateWithLifecycle()
        val state by remember {
            PerformanceSettings.ensureLoaded()
            PerformanceSettings.state
        }.collectAsStateWithLifecycle()

        SettingsSection(
            title = stringResource(Res.string.performance_section_optimizations),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                PerformanceReadoutRow(
                    title = stringResource(Res.string.performance_item_strip_symbols),
                    value = stringResource(Res.string.performance_item_strip_symbols_note),
                    isTablet = isTablet,
                    stacked = true,
                )
                PerformanceOptimization.entries.forEach { optimization ->
                    SettingsGroupDivider(isTablet = isTablet)
                    val copy = optimization.copyText()
                    val readout = optimization.readout(metrics)
                    val description = stringResource(copy.description)
                    SettingsSwitchRow(
                        title = stringResource(copy.title),
                        description = if (readout == null) description else "$description\n$readout",
                        checked = state[optimization] ?: true,
                        isTablet = isTablet,
                        onCheckedChange = { PerformanceSettings.setEnabled(optimization, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PerformanceReadouts(
    metrics: PerformanceSnapshot,
    isTablet: Boolean,
) {
    val notMeasured = stringResource(Res.string.performance_readout_not_measured)

    val launch = metrics.launchMs?.let { ms ->
        if (metrics.launchFromCachedHome) {
            stringResource(Res.string.performance_value_launch_cached, ms.toInt())
        } else {
            stringResource(Res.string.performance_value_ms, ms.toInt())
        }
    } ?: notMeasured
    val preload = metrics.startupPreloadMs?.let { ms ->
        if (metrics.startupPreloadParallel) {
            stringResource(Res.string.performance_value_preload_parallel, ms.toInt())
        } else {
            stringResource(Res.string.performance_value_ms, ms.toInt())
        }
    } ?: notMeasured
    val streams = metrics.lastTimeToStreamsMs?.let { stringResource(Res.string.performance_value_ms, it.toInt()) } ?: notMeasured
    val playback = metrics.lastTimeToPlaybackMs?.let { stringResource(Res.string.performance_value_ms, it.toInt()) } ?: notMeasured

    var memoryBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        while (isActive) {
            memoryBytes = withContext(Dispatchers.Default) { currentProcessMemoryBytes() }
            delay(MemoryPollIntervalMs)
        }
    }

    val memoryCache = SingletonImageLoader.get(LocalPlatformContext.current).memoryCache
    var imageCacheUsage by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(memoryCache) {
        while (isActive) {
            imageCacheUsage = memoryCache?.let { it.size to it.maxSize }
            delay(MemoryPollIntervalMs)
        }
    }
    val imageCache = imageCacheUsage?.let { (size, max) ->
        stringResource(Res.string.performance_value_cache_usage, formatDownloadBytes(size), formatDownloadBytes(max))
    } ?: notMeasured

    val appSize = rememberAppBinarySizeBytes()?.let(::formatDownloadBytes) ?: notMeasured

    PerformanceReadoutRow(stringResource(Res.string.performance_readout_launch), launch, isTablet)
    SettingsGroupDivider(isTablet = isTablet)
    PerformanceReadoutRow(stringResource(Res.string.performance_readout_startup_preload), preload, isTablet)
    SettingsGroupDivider(isTablet = isTablet)
    PerformanceReadoutRow(stringResource(Res.string.performance_readout_time_to_streams), streams, isTablet)
    SettingsGroupDivider(isTablet = isTablet)
    PerformanceReadoutRow(stringResource(Res.string.performance_readout_time_to_playback), playback, isTablet)
    SettingsGroupDivider(isTablet = isTablet)
    PerformanceReadoutRow(
        stringResource(Res.string.performance_readout_memory),
        memoryBytes?.let(::formatDownloadBytes) ?: notMeasured,
        isTablet,
    )
    SettingsGroupDivider(isTablet = isTablet)
    PerformanceReadoutRow(stringResource(Res.string.performance_readout_image_cache), imageCache, isTablet)
    SettingsGroupDivider(isTablet = isTablet)
    PerformanceReadoutRow(stringResource(Res.string.performance_readout_app_size), appSize, isTablet)
}

@Composable
private fun PerformanceReadoutRow(
    title: String,
    value: String,
    isTablet: Boolean,
    stacked: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val modifier = Modifier
        .fillMaxWidth()
        .padding(
            horizontal = if (isTablet) 20.dp else 16.dp,
            vertical = if (isTablet) 16.dp else 14.dp,
        )
    val titleText: @Composable (Modifier) -> Unit = { textModifier ->
        Text(
            text = title,
            modifier = textModifier,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
    }
    val valueText: @Composable () -> Unit = {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
    }
    if (stacked) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            titleText(Modifier)
            valueText()
        }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            titleText(Modifier.weight(1f))
            valueText()
        }
    }
}

private class OptimizationCopy(
    val title: StringResource,
    val description: StringResource,
)

private fun PerformanceOptimization.copyText(): OptimizationCopy = when (this) {
    PerformanceOptimization.PosterDownsampling -> OptimizationCopy(
        Res.string.performance_item_poster_downsampling,
        Res.string.performance_item_poster_downsampling_desc,
    )
    PerformanceOptimization.ParallelStartup -> OptimizationCopy(
        Res.string.performance_item_parallel_startup,
        Res.string.performance_item_parallel_startup_desc,
    )
    PerformanceOptimization.CachedHomeFirst -> OptimizationCopy(
        Res.string.performance_item_cached_home,
        Res.string.performance_item_cached_home_desc,
    )
    PerformanceOptimization.RequestDedupe -> OptimizationCopy(
        Res.string.performance_item_request_dedupe,
        Res.string.performance_item_request_dedupe_desc,
    )
    PerformanceOptimization.CappedCaches -> OptimizationCopy(
        Res.string.performance_item_capped_caches,
        Res.string.performance_item_capped_caches_desc,
    )
    PerformanceOptimization.QuietPlayback -> OptimizationCopy(
        Res.string.performance_item_quiet_playback,
        Res.string.performance_item_quiet_playback_desc,
    )
    PerformanceOptimization.NextEpisodePrefetch -> OptimizationCopy(
        Res.string.performance_item_next_episode,
        Res.string.performance_item_next_episode_desc,
    )
    PerformanceOptimization.ImageCompression -> OptimizationCopy(
        Res.string.performance_item_image_compression,
        Res.string.performance_item_image_compression_desc,
    )
}

/** Parallel startup and cached home report through the launch and preload readouts above. */
@Composable
private fun PerformanceOptimization.readout(metrics: PerformanceSnapshot): String? {
    fun counter(counter: PerformanceCounter) = metrics.counters[counter] ?: 0
    return when (this) {
        PerformanceOptimization.PosterDownsampling -> stringResource(
            Res.string.performance_item_poster_downsampling_readout,
            counter(PerformanceCounter.PostersDownsampled),
        )
        PerformanceOptimization.RequestDedupe -> stringResource(
            Res.string.performance_item_request_dedupe_readout,
            counter(PerformanceCounter.AddonCacheHits),
            counter(PerformanceCounter.AddonRequestsShared),
        )
        PerformanceOptimization.CappedCaches -> stringResource(
            Res.string.performance_item_capped_caches_readout,
            counter(PerformanceCounter.CacheEvictions),
        )
        PerformanceOptimization.QuietPlayback -> stringResource(
            Res.string.performance_item_quiet_playback_readout,
            counter(PerformanceCounter.PlaybackWorkDeferred),
        )
        PerformanceOptimization.NextEpisodePrefetch -> stringResource(
            Res.string.performance_item_next_episode_readout,
            counter(PerformanceCounter.NextEpisodePrefetches),
            counter(PerformanceCounter.NextEpisodePrefetchHits),
        )
        PerformanceOptimization.ImageCompression -> stringResource(
            Res.string.performance_item_image_compression_readout,
            counter(PerformanceCounter.ImagesCompressed),
        )
        PerformanceOptimization.ParallelStartup,
        PerformanceOptimization.CachedHomeFirst -> null
    }
}
