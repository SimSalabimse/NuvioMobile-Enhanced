package com.nuvio.app.features.analysis

import org.jetbrains.compose.resources.pluralStringResource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.analysis_stage_audio
import nuvio.composeapp.generated.resources.analysis_stage_matching
import nuvio.composeapp.generated.resources.analysis_stage_recap
import nuvio.composeapp.generated.resources.analysis_stage_previews
import nuvio.composeapp.generated.resources.analysis_status_queued
import nuvio.composeapp.generated.resources.analysis_status_running
import nuvio.composeapp.generated.resources.analysis_status_done
import nuvio.composeapp.generated.resources.analysis_status_partly
import nuvio.composeapp.generated.resources.analysis_status_failed
import nuvio.composeapp.generated.resources.analysis_part_intro_credits
import nuvio.composeapp.generated.resources.analysis_part_recap
import nuvio.composeapp.generated.resources.analysis_part_recap_none
import nuvio.composeapp.generated.resources.analysis_part_subtitle_sync
import nuvio.composeapp.generated.resources.analysis_part_loudness
import nuvio.composeapp.generated.resources.analysis_part_previews
import nuvio.composeapp.generated.resources.analysis_part_none
import nuvio.composeapp.generated.resources.analysis_part_needs_episodes
import nuvio.composeapp.generated.resources.analysis_ago_now
import nuvio.composeapp.generated.resources.analysis_ago_minutes
import nuvio.composeapp.generated.resources.analysis_ago_hours
import nuvio.composeapp.generated.resources.analysis_ago_days
import nuvio.composeapp.generated.resources.analysis_summary_title
import nuvio.composeapp.generated.resources.analysis_summary_count
import nuvio.composeapp.generated.resources.analysis_summary_failed
import nuvio.composeapp.generated.resources.analysis_summary_running
import nuvio.composeapp.generated.resources.analysis_summary_in_background
import nuvio.composeapp.generated.resources.analysis_summary_starting
import nuvio.composeapp.generated.resources.analysis_summary_waiting_charger
import nuvio.composeapp.generated.resources.analysis_summary_waiting_playback
import nuvio.composeapp.generated.resources.analysis_summary_idle_done
import nuvio.composeapp.generated.resources.analysis_summary_idle_charging
import nuvio.composeapp.generated.resources.analysis_summary_idle_waiting
import nuvio.composeapp.generated.resources.analysis_summary_bg_error
import nuvio.composeapp.generated.resources.analysis_summary_bg_last_finished
import nuvio.composeapp.generated.resources.analysis_summary_bg_last_stopped
import nuvio.composeapp.generated.resources.analysis_summary_bg_never_ran
import nuvio.composeapp.generated.resources.analysis_summary_bg_not_scheduled
import nuvio.composeapp.generated.resources.analysis_summary_queue
import nuvio.composeapp.generated.resources.analysis_summary_more
import nuvio.composeapp.generated.resources.analysis_action_now
import nuvio.composeapp.generated.resources.analysis_action_reanalyse_all

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.settings.SettingsClickableRow
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import org.jetbrains.compose.resources.stringResource

@Composable
private fun stageLabel(stage: AnalysisStage): String = when (stage) {
    AnalysisStage.Audio -> stringResource(Res.string.analysis_stage_audio)
    AnalysisStage.Matching -> stringResource(Res.string.analysis_stage_matching)
    AnalysisStage.Recap -> stringResource(Res.string.analysis_stage_recap)
    AnalysisStage.Previews -> stringResource(Res.string.analysis_stage_previews)
}

private fun percent(value: Double): Int = (value.coerceIn(0.0, 1.0) * 100).toInt()

/** Small analysis status under a finished download in Downloads. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisStatusRow(item: DownloadItem) {
    if (item.status != DownloadStatus.Completed || !MediaAnalysisStatus.supported) return
    val job by MediaAnalysisStatus.job.collectAsStateWithLifecycle()
    val failures by MediaAnalysisStatus.failures.collectAsStateWithLifecycle()
    val revision by MediaAnalysisStatus.revision.collectAsStateWithLifecycle()
    val flags by MediaAnalysisSettings.flags.collectAsStateWithLifecycle()
    val status = remember(item.id, item.fileName, job, failures, revision, flags) { MediaAnalysisStatus.statusFor(item) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (status) {
        ItemAnalysisStatus.Unsupported -> Unit
        ItemAnalysisStatus.Queued -> StatusLine(Icons.Rounded.Schedule, stringResource(Res.string.analysis_status_queued), muted)
        is ItemAnalysisStatus.Running -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusLine(
                Icons.Rounded.GraphicEq,
                stringResource(Res.string.analysis_status_running, percent(status.overall), stageLabel(status.stage)),
                MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(
                progress = { status.overall.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is ItemAnalysisStatus.Done -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusLine(
                if (status.complete) Icons.Rounded.CheckCircle else Icons.Rounded.Schedule,
                stringResource(if (status.complete) Res.string.analysis_status_done else Res.string.analysis_status_partly),
                if (status.complete) MaterialTheme.colorScheme.primary else muted,
            )
            PartChips(item, status.parts)
        }
        is ItemAnalysisStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusLine(
                Icons.Rounded.ErrorOutline,
                stringResource(Res.string.analysis_status_failed, status.reason),
                MaterialTheme.colorScheme.error,
            )
            status.parts?.let { PartChips(item, it) }
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartChips(item: DownloadItem, parts: AnalysisParts) {
    val isEpisode = item.seasonNumber != null && item.episodeNumber != null
    val entries = buildList {
        if (isEpisode) add(stringResource(Res.string.analysis_part_intro_credits) to parts.introCredits)
        if (isEpisode) add(stringResource(Res.string.analysis_part_recap) to parts.recap)
        add(stringResource(Res.string.analysis_part_subtitle_sync) to parts.subtitleSync)
        add(stringResource(Res.string.analysis_part_loudness) to parts.loudness)
        add(stringResource(Res.string.analysis_part_previews) to parts.previews)
    }.filter { it.second != AnalysisPart.Off }
    if (entries.isEmpty()) return
    val ledger by SkipSourceLedger.revision.collectAsStateWithLifecycle()
    val external = remember(item.id, item.fileName, parts, ledger) { externalSkipSources(item) }
    val introCreditsLabel = stringResource(Res.string.analysis_part_intro_credits)
    val recapLabel = stringResource(Res.string.analysis_part_recap)
    val noRecapText = stringResource(Res.string.analysis_part_recap_none)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEach { (label, part) ->
            // Times from TheIntroDB, IntroDB or your marks count even when this file found none.
            val externalSource = when (label) {
                introCreditsLabel -> external.introCredits
                recapLabel -> external.recap
                else -> null
            }?.takeIf { part != AnalysisPart.Ready }
            if (externalSource != null) {
                PartChip("$label · $externalSource ✓", ready = true)
                return@forEach
            }
            if (label == recapLabel && part == AnalysisPart.NotFound) {
                Text(
                    text = noRecapText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                return@forEach
            }
            val (suffix, ready) = when (part) {
                AnalysisPart.Ready -> "✓" to true
                AnalysisPart.NotFound -> stringResource(Res.string.analysis_part_none) to false
                AnalysisPart.NeedsMoreEpisodes -> stringResource(Res.string.analysis_part_needs_episodes) to false
                AnalysisPart.Pending -> "…" to false
                AnalysisPart.Off -> "" to false
            }
            PartChip("$label $suffix", ready)
        }
    }
}

@Composable
private fun PartChip(text: String, ready: Boolean) {
    val bg = if (ready) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val fg = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun agoText(atMs: Long): String {
    val minutes = ((com.nuvio.app.features.streams.epochMs() - atMs) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> stringResource(Res.string.analysis_ago_now)
        minutes < 60 -> stringResource(Res.string.analysis_ago_minutes, minutes.toInt())
        minutes < 48 * 60 -> stringResource(Res.string.analysis_ago_hours, (minutes / 60).toInt())
        else -> stringResource(Res.string.analysis_ago_days, (minutes / (24 * 60)).toInt())
    }
}

/** Settings > Downloads: how far the on-device analysis is, what it waits for, and controls. */
@Composable
internal fun AnalysisSummarySection(isTablet: Boolean) {
    if (!MediaAnalysisStatus.supported) return
    val downloads by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val job by MediaAnalysisStatus.job.collectAsStateWithLifecycle()
    val failures by MediaAnalysisStatus.failures.collectAsStateWithLifecycle()
    val revision by MediaAnalysisStatus.revision.collectAsStateWithLifecycle()
    val background by MediaAnalysisStatus.background.collectAsStateWithLifecycle()
    val flags by MediaAnalysisSettings.flags.collectAsStateWithLifecycle()
    val summary = remember(downloads, job, failures, revision, flags) { MediaAnalysisStatus.summarize(downloads.items) }
    val onlyCharging = flags[MediaAnalysisSettings.Flag.OnlyWhileCharging] ?: MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.OnlyWhileCharging)
    val textColor = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    SettingsSection(title = stringResource(Res.string.analysis_summary_title), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = pluralStringResource(Res.plurals.analysis_summary_count, summary.total, summary.analysed, summary.total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                )
                if (summary.total > 0) {
                    LinearProgressIndicator(
                        progress = { summary.analysed.toFloat() / summary.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (summary.failed > 0) {
                    Text(stringResource(Res.string.analysis_summary_failed, summary.failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                val stateText = when (val j = job) {
                    is AnalysisJobState.Running -> stringResource(
                        Res.string.analysis_summary_running,
                        j.title,
                        stageLabel(j.stage),
                        percent(if (j.stageProgress >= 0) j.stageProgress else 0.0),
                    ) + if (j.inBackground) " · " + stringResource(Res.string.analysis_summary_in_background) else ""
                    AnalysisJobState.Starting -> stringResource(Res.string.analysis_summary_starting)
                    AnalysisJobState.WaitingForCharger -> stringResource(Res.string.analysis_summary_waiting_charger)
                    AnalysisJobState.WaitingForPlayback -> stringResource(Res.string.analysis_summary_waiting_playback)
                    AnalysisJobState.Idle -> if (summary.queued.isEmpty()) {
                        stringResource(Res.string.analysis_summary_idle_done)
                    } else if (onlyCharging) {
                        stringResource(Res.string.analysis_summary_idle_charging, summary.queued.size)
                    } else {
                        stringResource(Res.string.analysis_summary_idle_waiting, summary.queued.size)
                    }
                }
                Text(stateText, style = MaterialTheme.typography.bodyMedium, color = textColor)
                (job as? AnalysisJobState.Running)?.let { j ->
                    LinearProgressIndicator(
                        progress = { j.overall.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val bgText = when {
                    background.scheduleError != null -> stringResource(Res.string.analysis_summary_bg_error, background.scheduleError.orEmpty())
                    background.lastRunAtMs != null -> stringResource(
                        if (background.lastRunFinished == true) Res.string.analysis_summary_bg_last_finished else Res.string.analysis_summary_bg_last_stopped,
                        agoText(background.lastRunAtMs!!),
                    )
                    background.scheduledAtMs != null -> stringResource(Res.string.analysis_summary_bg_never_ran)
                    else -> stringResource(Res.string.analysis_summary_bg_not_scheduled)
                }
                Text(bgText, style = MaterialTheme.typography.bodySmall, color = muted)
                if (summary.queued.isNotEmpty()) {
                    Text(stringResource(Res.string.analysis_summary_queue), style = MaterialTheme.typography.labelMedium, color = muted)
                    summary.queued.take(5).forEach { item ->
                        val name = if (item.seasonNumber != null && item.episodeNumber != null) {
                            "${item.title} S${item.seasonNumber}E${item.episodeNumber}"
                        } else item.title
                        Text("• $name", style = MaterialTheme.typography.bodySmall, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (summary.queued.size > 5) {
                        Text(stringResource(Res.string.analysis_summary_more, summary.queued.size - 5), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
            SettingsGroupDivider(isTablet = isTablet)
            SettingsClickableRow(
                title = stringResource(Res.string.analysis_action_now),
                isTablet = isTablet,
                onClick = { MediaAnalysisScheduler.analyseNow() },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsClickableRow(
                title = stringResource(Res.string.analysis_action_reanalyse_all),
                enabled = summary.total > 0,
                isTablet = isTablet,
                onClick = {
                    MediaAnalysisScheduler.reanalyse(downloads.items.filter { it.status == DownloadStatus.Completed })
                },
            )
        }
    }
}
