package com.nuvio.app.features.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.analysis.MediaAnalysisSettings
import com.nuvio.app.features.analysis.SkipLearning
import com.nuvio.app.features.player.skip.IntroSubmitService
import com.nuvio.app.features.player.skip.SkipFeedbackPrompt
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.defaultIntroSubmitService
import com.nuvio.app.features.player.skip.submitManualSkipSegment
import kotlinx.coroutines.launch

/** After a tap on Skip: ask Good / Wrong unless turned off (own marks are not asked about again). */
internal fun PlayerScreenRuntime.offerSkipFeedback(interval: SkipInterval) {
    if (!MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.SkipFeedbackPrompt)) return
    if (SkipLearning.isUserProvider(interval.provider)) return
    if (SkipLearning.canonicalType(interval.type) == null) return
    skipFeedbackNudging = false
    skipFeedbackInterval = null
    // The Skip button shows a little before the segment, so ask only once the user has watched
    // a few seconds from where the skip landed and can judge it.
    skipFeedbackDelayJob?.cancel()
    skipFeedbackDelayJob = scope.launch {
        kotlinx.coroutines.delay(SKIP_FEEDBACK_DELAY_MS)
        skipFeedbackInterval = interval
    }
}

/** How long after the skip lands the Good / Wrong prompt appears. */
internal const val SKIP_FEEDBACK_DELAY_MS = 4_000L

/**
 * Saves [type] from [startSec] to [endSec] as the user's own mark for this episode, lets the
 * season learn from it, uses it right away, and submits it when submitting is on. With
 * "TheIntroDB only" the submission goes to TheIntroDB.
 */
internal fun PlayerScreenRuntime.saveUserSkipMark(type: String, startSec: Double, endSec: Double, submit: Boolean) {
    val category = SkipLearning.canonicalType(type) ?: return
    if (endSec <= startSec) return
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    val metaId = parentMetaId
    val videoKey = activeVideoId.orEmpty()
    val durationMs = playbackSnapshot.durationMs
    val isMovie = !isSeries || parentMetaType.equals("movie", ignoreCase = true)
    val imdbId = activeVideoId?.split(":")?.firstOrNull()?.takeIf { it.startsWith("tt") }
        ?: metaId.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.id?.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.imdbId?.takeIf { it.startsWith("tt") }
    val alreadySubmitted = submittedSegmentTypesByVideoId[videoKey]?.contains(category) == true
    scope.launch {
        val taught = SkipLearning.recordMark(
            parentMetaId = metaId,
            season = season,
            episode = episode,
            type = category,
            startMs = (startSec * 1000).toLong(),
            endMs = (endSec * 1000).toLong(),
            durationMs = durationMs.takeIf {
                activeSeasonNumber == season && activeEpisodeNumber == episode && parentMetaId == metaId
            } ?: 0L,
        )
        // Use the mark at once in this episode.
        if (activeSeasonNumber == season && activeEpisodeNumber == episode && parentMetaId == metaId) {
            val mine = SkipInterval(startSec, endSec, category, SkipLearning.PROVIDER)
            skipIntervals = (skipIntervals.filter { SkipLearning.canonicalType(it.type) != category } + mine)
                .sortedBy { it.startTime }
        }
        var sent = false
        val settings = PlayerSettingsRepository.uiState.value
        if (submit && settings.introSubmitEnabled && !alreadySubmitted) {
            val service = if (MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.TheIntroDbOnly)) {
                IntroSubmitService.THE_INTRODB
            } else {
                defaultIntroSubmitService(settings.introDbApiKey, settings.theIntroDbApiKey)
            }
            sent = runCatching {
                submitManualSkipSegment(
                    imdbId = imdbId.orEmpty(),
                    season = season ?: 0,
                    episode = episode ?: 0,
                    startSec = startSec,
                    endSec = endSec,
                    segmentType = category,
                    durationMs = durationMs,
                    isMovie = isMovie,
                    videoId = activeVideoId ?: metaId,
                    parentMetaType = parentMetaType,
                    service = service,
                )
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                false
            }
            if (sent && videoKey.isNotBlank()) {
                submittedSegmentTypesByVideoId.getOrPut(videoKey) { mutableSetOf() }.add(category)
            }
            InAppLogger.info("Player/SkipFeedback", "submit type=$category service=$service sent=$sent")
        }
        val message = buildString {
            append(if (sent) "Saved and sent. Thanks!" else "Saved on this phone")
            if (taught > 0) append(" · updated $taught more episode${if (taught == 1) "" else "s"}")
        }
        NuvioToastController.show(message)
    }
}

@Composable
internal fun BoxScope.RenderSkipFeedbackPrompt(runtime: PlayerScreenRuntime) {
    runtime.run {
        val interval = skipFeedbackInterval ?: return
        SkipFeedbackPrompt(
            interval = interval,
            nudging = skipFeedbackNudging,
            durationSec = playbackSnapshot.durationMs / 1000.0,
            onGood = {
                skipFeedbackInterval = null
                saveUserSkipMark(interval.type, interval.startTime, interval.endTime, submit = true)
            },
            onWrong = { skipFeedbackNudging = true },
            onRewind = { seekBy(-5_000L) },
            onSave = { start, end ->
                skipFeedbackInterval = null
                skipFeedbackNudging = false
                saveUserSkipMark(interval.type, start, end, submit = true)
            },
            onDismiss = {
                skipFeedbackInterval = null
                skipFeedbackNudging = false
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = sliderEdgePadding, bottom = overlayBottomPadding + 8.dp),
        )
    }
}
