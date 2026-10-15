package com.nuvio.app.features.settings

import nuvio.composeapp.generated.resources.analysis_scrub_previews_title
import nuvio.composeapp.generated.resources.analysis_scrub_previews_description
import nuvio.composeapp.generated.resources.analysis_recap_detection_title
import nuvio.composeapp.generated.resources.analysis_recap_detection_description
import nuvio.composeapp.generated.resources.smart_downloads_title
import nuvio.composeapp.generated.resources.smart_downloads_description
import nuvio.composeapp.generated.resources.smart_downloads_count_title
import nuvio.composeapp.generated.resources.smart_downloads_count_value
import nuvio.composeapp.generated.resources.smart_downloads_charging_title
import nuvio.composeapp.generated.resources.smart_downloads_charging_description
import nuvio.composeapp.generated.resources.download_quality_title

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.analysis.MediaAnalysisSettings
import com.nuvio.app.features.downloads.DownloadQualityPreference
import com.nuvio.app.features.downloads.SmartDownloadSettings
import com.nuvio.app.features.downloads.downloadQualityLabel
import com.nuvio.app.features.downloads.DownloadsSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.analysis_intro_detection_description
import nuvio.composeapp.generated.resources.analysis_intro_detection_title
import nuvio.composeapp.generated.resources.analysis_loudness_description
import nuvio.composeapp.generated.resources.analysis_loudness_title
import nuvio.composeapp.generated.resources.analysis_only_while_charging_description
import nuvio.composeapp.generated.resources.analysis_only_while_charging_title
import nuvio.composeapp.generated.resources.analysis_subtitle_sync_description
import nuvio.composeapp.generated.resources.analysis_subtitle_sync_title
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import nuvio.composeapp.generated.resources.settings_downloads_location_private
import nuvio.composeapp.generated.resources.settings_downloads_location_reset
import nuvio.composeapp.generated.resources.downloads_allow_mobile_data_description
import nuvio.composeapp.generated.resources.downloads_allow_mobile_data_title
import nuvio.composeapp.generated.resources.downloads_delete_when_finished_description
import nuvio.composeapp.generated.resources.downloads_delete_when_finished_title
import nuvio.composeapp.generated.resources.downloads_show_download_button_description
import nuvio.composeapp.generated.resources.downloads_show_download_button_title
import nuvio.composeapp.generated.resources.downloads_switch_while_watching_description
import nuvio.composeapp.generated.resources.downloads_switch_while_watching_title
import nuvio.composeapp.generated.resources.settings_downloads_location_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.downloadsSettingsContent(
    isTablet: Boolean,
) {
    item {
        val downloadLocationUri by DownloadsSettingsRepository.downloadLocationUri.collectAsStateWithLifecycle()
        val allowMobileData by DownloadsSettingsRepository.allowMobileDataDownloads.collectAsStateWithLifecycle()
        val showDownloadButton by DownloadsSettingsRepository.showDownloadButton.collectAsStateWithLifecycle()
        val deleteWhenFinished by DownloadsSettingsRepository.deleteWhenFinished.collectAsStateWithLifecycle()
        val switchToDownload by DownloadsSettingsRepository.switchToDownloadWhileWatching.collectAsStateWithLifecycle()
        var showPicker by remember { mutableStateOf(false) }

        SettingsSection(
            title = stringResource(Res.string.compose_settings_root_downloads_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.downloads_allow_mobile_data_title),
                    description = stringResource(Res.string.downloads_allow_mobile_data_description),
                    checked = allowMobileData,
                    isTablet = isTablet,
                    onCheckedChange = DownloadsSettingsRepository::setAllowMobileDataDownloads,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.downloads_show_download_button_title),
                    description = stringResource(Res.string.downloads_show_download_button_description),
                    checked = showDownloadButton,
                    isTablet = isTablet,
                    onCheckedChange = DownloadsSettingsRepository::setShowDownloadButton,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.downloads_delete_when_finished_title),
                    description = stringResource(Res.string.downloads_delete_when_finished_description),
                    checked = deleteWhenFinished,
                    isTablet = isTablet,
                    onCheckedChange = DownloadsSettingsRepository::setDeleteWhenFinished,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.downloads_switch_while_watching_title),
                    description = stringResource(Res.string.downloads_switch_while_watching_description),
                    checked = switchToDownload,
                    isTablet = isTablet,
                    onCheckedChange = DownloadsSettingsRepository::setSwitchToDownloadWhileWatching,
                )
                val smart by SmartDownloadSettings.state.collectAsStateWithLifecycle()
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.smart_downloads_title),
                    description = stringResource(Res.string.smart_downloads_description),
                    checked = smart.enabled,
                    isTablet = isTablet,
                    onCheckedChange = SmartDownloadSettings::setEnabled,
                )
                if (smart.enabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.smart_downloads_count_title),
                        description = stringResource(Res.string.smart_downloads_count_value, smart.count),
                        isTablet = isTablet,
                        onClick = { SmartDownloadSettings.setCount(if (smart.count >= 3) 1 else smart.count + 1) },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.smart_downloads_charging_title),
                        description = stringResource(Res.string.smart_downloads_charging_description),
                        checked = smart.onlyWhileCharging,
                        isTablet = isTablet,
                        onCheckedChange = SmartDownloadSettings::setOnlyWhileCharging,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.download_quality_title),
                    description = downloadQualityLabel(smart.quality),
                    isTablet = isTablet,
                    onClick = {
                        val all = DownloadQualityPreference.entries
                        SmartDownloadSettings.setQuality(all[(all.indexOf(smart.quality) + 1) % all.size])
                    },
                )
                val analysisFlags by MediaAnalysisSettings.flags.collectAsStateWithLifecycle()
                fun flagOn(flag: MediaAnalysisSettings.Flag) = analysisFlags[flag] ?: MediaAnalysisSettings.isEnabled(flag)
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.analysis_subtitle_sync_title),
                    description = stringResource(Res.string.analysis_subtitle_sync_description),
                    checked = flagOn(MediaAnalysisSettings.Flag.SubtitleSync),
                    isTablet = isTablet,
                    onCheckedChange = { MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.SubtitleSync, it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.analysis_intro_detection_title),
                    description = stringResource(Res.string.analysis_intro_detection_description),
                    checked = flagOn(MediaAnalysisSettings.Flag.IntroDetection),
                    isTablet = isTablet,
                    onCheckedChange = { MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.analysis_loudness_title),
                    description = stringResource(Res.string.analysis_loudness_description),
                    checked = flagOn(MediaAnalysisSettings.Flag.Loudness),
                    isTablet = isTablet,
                    onCheckedChange = { MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.Loudness, it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.analysis_scrub_previews_title),
                    description = stringResource(Res.string.analysis_scrub_previews_description),
                    checked = flagOn(MediaAnalysisSettings.Flag.ScrubPreviews),
                    isTablet = isTablet,
                    onCheckedChange = { MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.ScrubPreviews, it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.analysis_recap_detection_title),
                    description = stringResource(Res.string.analysis_recap_detection_description),
                    checked = flagOn(MediaAnalysisSettings.Flag.RecapDetection),
                    isTablet = isTablet,
                    onCheckedChange = { MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.RecapDetection, it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.analysis_only_while_charging_title),
                    description = stringResource(Res.string.analysis_only_while_charging_description),
                    checked = flagOn(MediaAnalysisSettings.Flag.OnlyWhileCharging),
                    isTablet = isTablet,
                    onCheckedChange = { MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.OnlyWhileCharging, it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_downloads_location_title),
                    description = downloadLocationUri?.let { formatUriForDisplay(it) } ?: stringResource(Res.string.settings_downloads_location_private),
                    isTablet = isTablet,
                    onClick = { showPicker = true },
                )
                if (downloadLocationUri != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsClickableRow(
                        title = stringResource(Res.string.settings_downloads_location_reset),
                        isTablet = isTablet,
                        onClick = { DownloadsSettingsRepository.setDownloadLocationUri(null) },
                    )
                }
            }
        }

        if (showPicker) {
            DownloadLocationPicker(
                onLocationSelected = { uri ->
                    DownloadsSettingsRepository.setDownloadLocationUri(uri)
                    showPicker = false
                },
                onDismiss = { showPicker = false },
            )
        }
    }
    item {
        com.nuvio.app.features.analysis.AnalysisSummarySection(isTablet = isTablet)
    }
}

@Composable
internal expect fun DownloadLocationPicker(
    onLocationSelected: (uri: String) -> Unit,
    onDismiss: () -> Unit,
)

internal expect fun formatUriForDisplay(uri: String): String
