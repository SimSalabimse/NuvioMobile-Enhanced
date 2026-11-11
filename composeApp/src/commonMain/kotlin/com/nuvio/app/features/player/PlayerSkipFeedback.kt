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
import com.nuvio.app.features.player.skip.ManualSkipSubmitOutcome
import com.nuvio.app.features.player.skip.OwnTheIntroDbLedger
import com.nuvio.app.features.player.skip.SkipFeedbackPrompt
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.TheIntroDbAlreadySubmittedMessage
import com.nuvio.app.features.player.skip.TheIntroDbRateLimitMessage
import com.nuvio.app.features.player.skip.defaultIntroSubmitService
import com.nuvio.app.features.player.skip.flagSubmitContentKey
import com.nuvio.app.features.player.skip.OwnAccountMarks
import com.nuvio.app.features.player.skip.OwnMarkStatus
import com.nuvio.app.features.player.skip.accountScopeForService
import com.nuvio.app.features.player.skip.shouldRecordOwnTheIntroDb
import com.nuvio.app.features.player.skip.withStatus
import com.nuvio.app.features.player.skip.recordSubmittedSegment
import com.nuvio.app.features.player.skip.resolvePlaybackSkips
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
internal fun PlayerScreenRuntime.saveUserSkipMark(
    type: String,
    startSec: Double,
    endSec: Double,
    submit: Boolean,
    additional: Boolean = false,
) {
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
    val contentKey = flagSubmitContentKey(imdbId.orEmpty(), season ?: 0, episode ?: 0, isMovie)
    scope.launch {
        val durationForMark = durationMs.takeIf {
            activeSeasonNumber == season && activeEpisodeNumber == episode && parentMetaId == metaId
        } ?: 0L
        val startMs = (startSec * 1000).toLong()
        val endMs = (endSec * 1000).toLong()
        val hasPrimary = SkipLearning.userMarks(metaId, season, episode)[category] != null
        val taught = if (additional && hasPrimary) {
            if (SkipLearning.addSegment(metaId, season, episode, category, startMs, endMs, durationForMark)) 0 else return@launch
        } else {
            SkipLearning.recordMark(
                parentMetaId = metaId,
                season = season,
                episode = episode,
                type = category,
                startMs = startMs,
                endMs = endMs,
                durationMs = durationForMark,
            )
        }
        // Use the mark at once. It wins only where it overlaps a TheIntroDB segment.
        if (activeSeasonNumber == season && activeEpisodeNumber == episode && parentMetaId == metaId) {
            val online = skipIntervals.filter { !SkipLearning.isUserProvider(it.provider) }
            skipIntervals = resolvePlaybackSkips(online, userMarkedSkipIntervals(), emptyList())
        }
        var outcome: ManualSkipSubmitOutcome? = null
        val settings = PlayerSettingsRepository.uiState.value
        val durationSec = durationMs.takeIf { it > 0L }?.div(1000.0)
        val maySend = skipFeedbackMaySubmit(category, startSec, endSec, durationSec)
        val service = if (MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.TheIntroDbOnly)) {
            IntroSubmitService.THE_INTRODB
        } else {
            defaultIntroSubmitService(settings.introDbApiKey, settings.theIntroDbApiKey)
        }
        val reserve = if (submit && maySend && settings.introSubmitEnabled && service == IntroSubmitService.THE_INTRODB) {
            OwnTheIntroDbLedger.reserveSegment(
                scope = accountScopeForService(service, settings.introDbApiKey, settings.theIntroDbApiKey),
                contentKey = contentKey,
                segmentType = category,
                status = OwnMarkStatus.Pending,
                startMs = startMs,
                endMs = endMs,
                remoteCount = 0,
            )
        } else {
            null
        }
        val capped = submit && maySend && settings.introSubmitEnabled && service == IntroSubmitService.THE_INTRODB && reserve == null
        val sameRange = reserve != null && !reserve.created
        if (submit && maySend && settings.introSubmitEnabled && !capped && (service != IntroSubmitService.THE_INTRODB || reserve?.created == true)) {
            logIntroLengthWarning(category, startSec, endSec, durationSec)
            val posted = runCatching {
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
                ManualSkipSubmitOutcome.FAILED
            }
            outcome = posted
            if (posted.recorded && videoKey.isNotBlank()) {
                submittedSegmentTypesByVideoId.getOrPut(videoKey) { mutableSetOf() }.add(category)
            }
            val reserved = reserve?.segment
            if (reserve?.created == true && reserved != null && service == IntroSubmitService.THE_INTRODB) {
                val scope = accountScopeForService(service, settings.introDbApiKey, settings.theIntroDbApiKey)
                when (posted) {
                    ManualSkipSubmitOutcome.FAILED -> OwnTheIntroDbLedger.dropSegment(scope, contentKey, reserved.id)
                    ManualSkipSubmitOutcome.ALREADY_SUBMITTED -> OwnTheIntroDbLedger.confirmSegment(scope, contentKey, reserved)
                    ManualSkipSubmitOutcome.SENT -> Unit
                }
            }
            InAppLogger.info(
                "Player/SkipFeedback",
                "submit type=$category service=$service sent=${posted.recorded}",
            )
        }
        val message = buildString {
            append(
                when {
                    capped -> "This episode already has 3 of that segment."
                    sameRange || outcome == ManualSkipSubmitOutcome.ALREADY_SUBMITTED -> TheIntroDbAlreadySubmittedMessage
                    outcome == ManualSkipSubmitOutcome.SENT -> "Saved and sent. Thanks!"
                    outcome == ManualSkipSubmitOutcome.FAILED ->
                        TheIntroDb.lastSubmitError
                            ?.takeIf { service == IntroSubmitService.THE_INTRODB && it == TheIntroDbRateLimitMessage }
                            ?: "Saved on this phone"
                    else -> "Saved on this phone"
                },
            )
            if (taught > 0 && outcome != ManualSkipSubmitOutcome.ALREADY_SUBMITTED) {
                append(" · updated $taught more episode${if (taught == 1) "" else "s"}")
            }
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

/** Good / Save uses the same length checks as the submit bar. A failing check is not sent. */
internal fun skipFeedbackMaySubmit(
    segmentType: String,
    startSec: Double,
    endSec: Double,
    durationSec: Double?,
): Boolean = markProblems(segmentType, startSec, endSec, durationSec).isEmpty()

/**
 * Ledger after a Good / Save send. A failed send leaves the marks unchanged.
 * A 200 is pending. A 409 duplicate from this account is confirmed. Nothing here submits a detection.
 */
internal fun skipFeedbackOwnLedger(
    marks: OwnAccountMarks,
    contentKey: String,
    segmentType: String,
    service: IntroSubmitService,
    sent: Boolean,
    duplicate: Boolean = false,
): OwnAccountMarks {
    if (!shouldRecordOwnTheIntroDb(service, sent)) return marks
    val status = if (duplicate) OwnMarkStatus.Confirmed else OwnMarkStatus.Pending
    return recordSubmittedSegment(
        marks = marks,
        contentKey = contentKey,
        segmentType = segmentType,
        status = status,
        startMs = null,
        endMs = null,
        remoteCount = 0,
    ).marks
}
