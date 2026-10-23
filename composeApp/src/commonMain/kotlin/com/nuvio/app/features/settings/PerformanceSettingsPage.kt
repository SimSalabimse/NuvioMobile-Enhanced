package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_performance
import nuvio.composeapp.generated.resources.performance_header
import nuvio.composeapp.generated.resources.performance_description
import nuvio.composeapp.generated.resources.performance_metrics_header
import nuvio.composeapp.generated.resources.performance_launch_time
import nuvio.composeapp.generated.resources.performance_time_to_playback
import nuvio.composeapp.generated.resources.performance_memory_usage
import nuvio.composeapp.generated.resources.performance_optimization_1
import nuvio.composeapp.generated.resources.performance_optimization_1_desc
import nuvio.composeapp.generated.resources.performance_optimization_1_note
import nuvio.composeapp.generated.resources.performance_optimization_2
import nuvio.composeapp.generated.resources.performance_optimization_2_desc
import nuvio.composeapp.generated.resources.performance_optimization_3
import nuvio.composeapp.generated.resources.performance_optimization_3_desc
import nuvio.composeapp.generated.resources.performance_optimization_4
import nuvio.composeapp.generated.resources.performance_optimization_4_desc
import nuvio.composeapp.generated.resources.performance_optimization_5
import nuvio.composeapp.generated.resources.performance_optimization_5_desc
import nuvio.composeapp.generated.resources.performance_optimization_6
import nuvio.composeapp.generated.resources.performance_optimization_6_desc
import nuvio.composeapp.generated.resources.performance_optimization_7
import nuvio.composeapp.generated.resources.performance_optimization_7_desc
import nuvio.composeapp.generated.resources.performance_optimization_8
import nuvio.composeapp.generated.resources.performance_optimization_8_desc
import nuvio.composeapp.generated.resources.performance_optimization_9
import nuvio.composeapp.generated.resources.performance_optimization_9_desc
import nuvio.composeapp.generated.resources.performance_optimization_10
import nuvio.composeapp.generated.resources.performance_optimization_10_desc
import org.jetbrains.compose.resources.stringResource

@Composable
fun PerformanceSettingsScreen(
    onBack: () -> Unit,
) {
    PerformanceSettingsRepository.ensureLoaded()

    NuvioScreen(
        modifier = Modifier.fillMaxSize(),
    ) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_performance),
                onBack = onBack,
            )
        }
        performanceSettingsContent(isTablet = false)
    }
}

internal fun LazyListScope.performanceSettingsContent(
    isTablet: Boolean,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.performance_metrics_header),
            subtitle = null,
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsInfoRow(
                    title = stringResource(Res.string.performance_launch_time),
                    value = PerformanceMetrics.getFormattedLaunchTime(),
                    isTablet = isTablet,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsInfoRow(
                    title = stringResource(Res.string.performance_time_to_playback),
                    value = PerformanceMetrics.getFormattedTimeToPlayback(),
                    isTablet = isTablet,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsInfoRow(
                    title = stringResource(Res.string.performance_memory_usage),
                    value = PerformanceMetrics.getFormattedMemory(),
                    isTablet = isTablet,
                )
            }
        }
    }
    
    item {
        val symbolStripping by PerformanceSettingsRepository.symbolStrippingEnabled.collectAsStateWithLifecycle()
        val posterDownsampling by PerformanceSettingsRepository.posterDownsamplingEnabled.collectAsStateWithLifecycle()
        val parallelStartup by PerformanceSettingsRepository.parallelStartupEnabled.collectAsStateWithLifecycle()
        val cachedHomeFirst by PerformanceSettingsRepository.cachedHomeFirstEnabled.collectAsStateWithLifecycle()
        val requestDedupe by PerformanceSettingsRepository.requestDedupeEnabled.collectAsStateWithLifecycle()
        val shortHttpCaches by PerformanceSettingsRepository.shortHttpCachesEnabled.collectAsStateWithLifecycle()
        val cappedLruCaches by PerformanceSettingsRepository.cappedLruCachesEnabled.collectAsStateWithLifecycle()
        val pausePrefetchPlayback by PerformanceSettingsRepository.pausePrefetchDuringPlaybackEnabled.collectAsStateWithLifecycle()
        val nextEpisodePrefetch by PerformanceSettingsRepository.nextEpisodePrefetchEnabled.collectAsStateWithLifecycle()
        val imageCompression by PerformanceSettingsRepository.imageCompressionEnabled.collectAsStateWithLifecycle()

        SettingsSection(
            title = stringResource(Res.string.performance_header),
            subtitle = stringResource(Res.string.performance_description),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.performance_optimization_1),
                    description = stringResource(Res.string.performance_optimization_1_desc),
                    checked = symbolStripping,
                    isTablet = isTablet,
                    onCheckedChange = PerformanceSettingsRepository::setSymbolStrippingEnabled,
                )
                SettingsNoteRow(
                    note = stringResource(Res.string.performance_optimization_1_note),
                    isTablet = isTablet,
                )
                
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.performance_optimization_2),
                    description = stringResource(Res.string.performance_optimization_2_desc),
                    checked = posterDownsampling,
                    isTablet = isTablet,
                    onCheckedChange = PerformanceSettingsRepository::setPosterDownsamplingEnabled,
                )
                
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.performance_optimization_3),
                    description = stringResource(Res.string.performance_optimization_3_desc),
                    checked = parallelStartup,
                    isTablet = isTablet,
                    onCheckedChange = PerformanceSettingsRepository::setParallelStartupEnabled,
                )
                
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.performance_optimization_4),
                    description = stringResource(Res.string.performance_optimization_4_desc),
                    checked = cachedHomeFirst,
                    isTablet = isTablet,
                    onCheckedChange = PerformanceSettingsRepository::setCachedHomeFirstEnabled,
                )
                
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.performance_optimization_5),
                    description = stringResource(Res.string.performance_optimization_5_desc),
                    checked = requestDedupe,
                    isTablet = isTablet,
                    onCheckedChange = PerformanceSettingsRepository::setRequestDedupeEnabled,
                )
                
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.performance_optimization_6),
                    description = stringResource(Res.string.performance_optimization_6_desc),
                    checked = shortHttpCaches,
                    isTablet = isTablet,
                    onCheckedChange = PerformanceSettingsRepository::setShortHttpCachesEnabled,
                )
                
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.performance_optimization_7),
                    description = stringResource(Res.string.performance_optimization_7_desc),
                    checked = cappedLruCaches,
                    isTablet = isTablet,
                    onCheckedChange = PerformanceSettingsRepository::setCappedLruCachesEnabled,
                )
                
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.performance_optimization_8),
                    description = stringResource(Res.string.performance_optimization_8_desc),
                    checked = pausePrefetchPlayback,
                    isTablet = isTablet,
                    onCheckedChange = PerformanceSettingsRepository::setPausePrefetchDuringPlaybackEnabled,
                )
                
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.performance_optimization_9),
                    description = stringResource(Res.string.performance_optimization_9_desc),
                    checked = nextEpisodePrefetch,
                    isTablet = isTablet,
                    onCheckedChange = PerformanceSettingsRepository::setNextEpisodePrefetchEnabled,
                )
                
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.performance_optimization_10),
                    description = stringResource(Res.string.performance_optimization_10_desc),
                    checked = imageCompression,
                    isTablet = isTablet,
                    onCheckedChange = PerformanceSettingsRepository::setImageCompressionEnabled,
                )
            }
        }
    }
}
