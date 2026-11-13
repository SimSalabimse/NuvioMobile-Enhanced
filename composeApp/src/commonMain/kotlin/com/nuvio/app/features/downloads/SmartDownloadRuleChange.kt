package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo

/**
 * What changing the Smart Downloads count on a show's page decides, before any file is requested.
 * [startsNow] is the only path that tells the user episodes were queued. A blocked reason keeps
 * the request for the existing Wi-Fi, charging, space, or air-date retry and does not start a
 * transfer. Nothing here cancels a download, including one the user started.
 */
internal data class SmartDownloadRuleDecision(
    val message: String,
    val startsNow: Boolean,
    val keepPending: Boolean,
    val dropPlainPending: Boolean,
    val anchorSeason: Int? = null,
    val anchorEpisode: Int? = null,
    val episodeCodes: List<String> = emptyList(),
) {
    val cancelsDownloads: Boolean get() = false
}

internal data class SmartDownloadRuleInput(
    val globalEnabled: Boolean = true,
    val effectiveCount: Int = 1,
    val onlyOnWifi: Boolean = true,
    val onWifi: Boolean = true,
    val onlyWhileCharging: Boolean = false,
    val charging: Boolean = true,
    val analyserSupported: Boolean = true,
    val queueUnaired: Boolean = true,
    val hasShowDownload: Boolean = true,
    val watched: List<Pair<Int, Int>> = emptyList(),
    val completed: Set<Pair<Int, Int>> = emptySet(),
    val inProgress: Set<Pair<Int, Int>> = emptySet(),
    val videos: List<MetaVideo> = emptyList(),
    val unaired: Set<Pair<Int, Int>> = emptySet(),
    val freeBytes: Long? = null,
    val bytesPerEpisode: Long = SMART_DOWNLOAD_MIN_FREE_BYTES,
    val inProgressRemainingBytes: Long = 0L,
    val privateViewing: Boolean = false,
)

internal const val SMART_DOWNLOAD_RULE_OFF = "Smart downloads are off"
internal const val SMART_DOWNLOAD_RULE_COUNT_OFF = "Next episodes stay off for this show"
internal const val SMART_DOWNLOAD_RULE_NEEDS_DOWNLOAD = "Starts once an episode of this show is downloaded"
internal const val SMART_DOWNLOAD_RULE_NEEDS_FINISH = "Starts when you finish an episode of this show"
internal const val SMART_DOWNLOAD_RULE_NONE = "No episodes to queue"
internal const val SMART_DOWNLOAD_RULE_UNAIRED = "Next episode has not aired yet"
internal const val SMART_DOWNLOAD_RULE_PRIVATE = "Not queued while Private Viewing is on"
internal const val SMART_DOWNLOAD_RULE_LOAD_FAILED = "Couldn't load this show"

internal fun smartDownloadRuleQueued(count: Int): String = when (count) {
    0 -> "Queued 0 episodes"
    1 -> "Queued 1 episode"
    else -> "Queued $count episodes"
}

internal data class SmartDownloadSettingsFeedback(
    val message: String,
    val showsToStart: List<String>,
)

/** One line for a settings change. [decisions] are show id to that show's rule. */
internal fun smartDownloadSettingsFeedback(
    decisions: List<Pair<String, SmartDownloadRuleDecision>>,
): SmartDownloadSettingsFeedback {
    val starting = decisions.filter { (_, decision) -> decision.startsNow }
    return SmartDownloadSettingsFeedback(
        message = smartDownloadRuleQueued(starting.sumOf { it.second.episodeCodes.size }),
        showsToStart = starting.map { it.first },
    )
}

internal fun smartDownloadRuleSearching(count: Int): String =
    if (count == 1) "Looking for the next episode" else "Looking for the next $count episodes"

/** A plain next-episode request, not an exact unaired queue and not a Finish-a-series goal. */
internal fun isPlainSmartRequest(exact: Boolean, count: Int?): Boolean = !exact && count == null

/**
 * Fixture, automation, and the held goal fixture must not start a download from a details save.
 * Private Viewing is a separate, visible refusal handled by [evaluateSmartDownloadRuleChange].
 */
internal fun smartDownloadDetailsSaveProceeds(
    fixtureActive: Boolean,
    automationActive: Boolean,
    fixtureGoalHeld: Boolean,
): Boolean = !fixtureActive && !automationActive && !fixtureGoalHeld

internal fun smartDownloadRuleAnchor(
    watched: List<Pair<Int, Int>>,
    completed: Set<Pair<Int, Int>>,
    inProgress: Set<Pair<Int, Int>>,
): Pair<Int, Int>? {
    val watchedLatest = watched.maxWithOrNull(compareBy({ it.first }, { it.second }))
    if (watchedLatest != null) return watchedLatest
    return (completed + inProgress).maxWithOrNull(compareBy({ it.first }, { it.second }))
}

internal fun evaluateSmartDownloadRuleChange(input: SmartDownloadRuleInput): SmartDownloadRuleDecision {
    if (input.privateViewing) return held(SMART_DOWNLOAD_RULE_PRIVATE)
    if (!input.globalEnabled) return held(SMART_DOWNLOAD_RULE_OFF)
    if (input.effectiveCount <= 0) {
        return held(SMART_DOWNLOAD_RULE_COUNT_OFF).copy(dropPlainPending = true)
    }
    if (!input.hasShowDownload) return held(SMART_DOWNLOAD_RULE_NEEDS_DOWNLOAD)
    val anchor = smartDownloadRuleAnchor(input.watched, input.completed, input.inProgress)
        ?: return held(SMART_DOWNLOAD_RULE_NEEDS_FINISH)
    val wanted = nextEpisodes(input.videos, anchor.first, anchor.second, input.effectiveCount)
    if (wanted.isEmpty()) return held(SMART_DOWNLOAD_RULE_NONE, anchor)
    val (aired, upcoming) = wanted.partition { (it.season to it.episode) !in input.unaired }
    if (aired.isEmpty()) {
        return held(SMART_DOWNLOAD_RULE_UNAIRED, anchor).copy(keepPending = input.queueUnaired && upcoming.isNotEmpty())
    }
    val heldEpisodes = input.completed + input.inProgress
    val missing = aired.filter { (it.season to it.episode) !in heldEpisodes }
    if (missing.isEmpty()) {
        val waiting = aired.any { (it.season to it.episode) in input.inProgress }
        val one = aired.size == 1
        val message = when {
            waiting && one -> "The next episode is already queued"
            waiting -> "The next episodes are already queued"
            one -> "The next episode is already downloaded"
            else -> "The next episodes are already downloaded"
        }
        return held(message, anchor)
    }
    val codes = missing.map { "S${it.season}E${it.episode}" }
    val wait = smartDownloadWaitReason(
        onlyOnWifi = input.onlyOnWifi,
        onWifi = input.onWifi,
        onlyWhileCharging = input.onlyWhileCharging,
        charging = input.charging,
        analyserSupported = input.analyserSupported,
    )
    if (wait != null) return held(wait, anchor, codes).copy(keepPending = true)
    if (input.freeBytes != null && input.freeBytes < SMART_DOWNLOAD_MIN_FREE_BYTES) {
        return held(SMART_DOWNLOADS_NO_SPACE, anchor).copy(keepPending = true)
    }
    val fitting = smartDownloadsThatFit(
        rows = missing,
        sizeOf = { input.bytesPerEpisode.takeIf { size -> size > 0L } },
        fallbackBytes = input.bytesPerEpisode,
        freeBytes = input.freeBytes,
        inProgressRemainingBytes = input.inProgressRemainingBytes,
    )
    if (fitting.isEmpty()) return held(SMART_DOWNLOADS_NO_SPACE, anchor).copy(keepPending = true)
    return SmartDownloadRuleDecision(
        message = smartDownloadRuleQueued(fitting.size),
        startsNow = true,
        keepPending = true,
        dropPlainPending = false,
        anchorSeason = anchor.first,
        anchorEpisode = anchor.second,
        episodeCodes = fitting.map { "S${it.season}E${it.episode}" },
    )
}

private fun held(
    message: String,
    anchor: Pair<Int, Int>? = null,
    codes: List<String> = emptyList(),
): SmartDownloadRuleDecision = SmartDownloadRuleDecision(
    message = message,
    startsNow = false,
    keepPending = false,
    dropPlainPending = false,
    anchorSeason = anchor?.first,
    anchorEpisode = anchor?.second,
    episodeCodes = codes,
)
