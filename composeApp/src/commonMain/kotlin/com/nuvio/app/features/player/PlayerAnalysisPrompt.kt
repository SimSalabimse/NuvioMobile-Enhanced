package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.analysis.AnalysisJobState
import com.nuvio.app.features.analysis.AnalysisPlayChoice
import com.nuvio.app.features.analysis.ItemAnalysisStatus
import com.nuvio.app.features.analysis.MediaAnalysisScheduler
import com.nuvio.app.features.analysis.MediaAnalysisStatus
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.PlaybackPriority

/**
 * Playing a downloaded episode whose on-device analysis is not finished: a small card (playback
 * keeps going underneath) offers Play now, Finish first (pause, analyse this file at full speed
 * with a progress bar, then play) or Finish in background (keep playing, analyse this file next
 * at playback-friendly speed). Skip times found meanwhile are applied as soon as they are saved.
 */
@Composable
internal fun BoxScope.RenderAnalysisPlayPrompt(runtime: PlayerScreenRuntime) {
    runtime.run {
        val sourceUrl = activeSourceUrl
        val item = remember(sourceUrl, parentMetaId, activeSeasonNumber, activeEpisodeNumber) {
            playingDownload(sourceUrl, parentMetaId, activeSeasonNumber, activeEpisodeNumber)
        } ?: return
        val revision by MediaAnalysisStatus.revision.collectAsState()
        val job by MediaAnalysisStatus.job.collectAsState()

        // New results for this file (skip times found in the background): use them at once.
        LaunchedEffect(revision, item.id) {
            if (revision == 0) return@LaunchedEffect
            skipIntervals = withOnDeviceSkips(skipIntervals.filter { it.provider != ON_DEVICE_SKIP_PROVIDER })
        }

        var decided by remember(item.id) { mutableStateOf(false) }
        var finishing by remember(item.id) { mutableStateOf(false) }
        var dontAsk by remember(item.id) { mutableStateOf(false) }

        fun choose(choice: AnalysisPlayChoice) {
            decided = true
            if (dontAsk) AnalysisPlayChoice.set(choice)
            when (choice) {
                AnalysisPlayChoice.FinishFirst -> {
                    finishing = true
                    shouldPlay = false
                    PlaybackPriority.boostAnalysis(true)
                    MediaAnalysisScheduler.prioritise(item)
                }
                AnalysisPlayChoice.Background -> MediaAnalysisScheduler.prioritise(item)
                else -> Unit
            }
        }

        val needsAnalysis = remember(item.id, revision, job) {
            when (val status = MediaAnalysisStatus.statusFor(item)) {
                is ItemAnalysisStatus.Queued, is ItemAnalysisStatus.Running -> true
                is ItemAnalysisStatus.Done -> !status.complete
                else -> false
            }
        }

        // The prompt is only for starting an episode: decide once, from the status when this file
        // started playing. Later changes (a Good/Wrong mark re-queues derived analysis, a season
        // re-match, a background pass) must never bring it up mid-playback.
        val askedAtStart = remember(item.id) { needsAnalysis }

        LaunchedEffect(item.id) {
            if (decided || !askedAtStart) return@LaunchedEffect
            when (val saved = AnalysisPlayChoice.get()) {
                AnalysisPlayChoice.Ask -> Unit
                else -> choose(saved)
            }
        }

        if (finishing) {
            if (!needsAnalysis) {
                LaunchedEffect(Unit) {
                    finishing = false
                    PlaybackPriority.boostAnalysis(false)
                    shouldPlay = true
                }
                return
            }
            val running = (job as? AnalysisJobState.Running)?.takeIf { it.itemId == item.id }
            PromptCard(Modifier.align(Alignment.TopCenter).padding(top = 72.dp)) {
                Text("Analysing this episode…", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val progress = running?.overall?.takeIf { it >= 0 }
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                PromptButton("Play now") {
                    finishing = false
                    PlaybackPriority.boostAnalysis(false)
                    shouldPlay = true
                }
            }
            return
        }

        if (decided || !askedAtStart || !needsAnalysis || AnalysisPlayChoice.get() != AnalysisPlayChoice.Ask) return
        PromptCard(Modifier.align(Alignment.TopCenter).padding(top = 72.dp)) {
            Text("This episode isn't fully analysed yet", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Skip times and subtitle sync may be missing.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PromptButton("Play now") { choose(AnalysisPlayChoice.PlayNow) }
                PromptButton("Finish first") { choose(AnalysisPlayChoice.FinishFirst) }
                PromptButton("In background") { choose(AnalysisPlayChoice.Background) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it })
                Text("Don't ask again", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PromptCard(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E1E1E).copy(alpha = 0.9f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun PromptButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun playingDownload(sourceUrl: String?, parentMetaId: String, season: Int?, episode: Int?): DownloadItem? {
    val url = sourceUrl?.trim().orEmpty()
    if (!url.startsWith("file://") && !url.startsWith("/")) return null
    return DownloadsRepository.uiState.value.items.firstOrNull {
        it.status == DownloadStatus.Completed && it.localFileUri == url
    } ?: DownloadsRepository.uiState.value.items.firstOrNull {
        it.status == DownloadStatus.Completed && it.parentMetaId == parentMetaId &&
            it.seasonNumber == season && it.episodeNumber == episode
    }
}
