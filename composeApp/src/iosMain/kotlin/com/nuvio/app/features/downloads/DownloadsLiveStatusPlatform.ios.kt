package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * Writes the Live Activity payload and pings the app's Swift side, at most once a second while
 * only progress changes (status, episode set or size changes go at once), always ending on the
 * latest state. Each payload carries a smoothed speed and the time it was sampled, so the Swift
 * side can hand the widget a self-animating estimated bar for while the app is suspended.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsLiveStatusPlatform {
    private const val notificationName = "NuvioDownloadsLiveStatusUpdated"
    private const val userDefaultsPayloadKey = "nuvio.downloads.live_status.payload"

    private val json = Json {
        encodeDefaults = true
    }

    private val throttle = LiveStatusThrottle()
    private val speed = TransferSpeedEstimator()
    private var pending: DownloadsLiveStatusPayload? = null
    private var hasPending = false
    private var flushScheduled = false
    private var lastWritten: String? = null
    private var written = false
    private var lifecycleObservers: List<Any>? = null
    /** The running payload last built and the rows behind it, to tell how it stopped. */
    private var lastRunning: DownloadsLiveStatusPayload? = null
    private var lastMemberIds: Set<String> = emptySet()

    actual fun onItemsChanged(items: List<DownloadItem>) {
        val snapshot = items.toList()
        if (NSThread.isMainThread) {
            handle(snapshot)
        } else {
            dispatch_async(dispatch_get_main_queue()) { handle(snapshot) }
        }
    }

    private fun handle(items: List<DownloadItem>) {
        observeLifecycle()
        val now = nowMs()
        // Only a running transfer has a Live Activity. A paused or failed row left in the list used to
        // become the "primary" item after a cancel and got an activity that nothing ever updated or
        // ended, which is what showed up stuck on the Lock Screen once the app was left.
        val base = liveStatusPayloadFor(items)
        val bytesPerSecond = speed.sample(base?.id, base?.downloadedBytes ?: 0L, now)
        pending = if (base != null) {
            lastRunning = base
            lastMemberIds = liveStatusMembers(items).mapTo(mutableSetOf()) { it.id }
            base.copy(bytesPerSecond = bytesPerSecond, sampledAtEpochMs = now)
        } else {
            stoppedPayload(items)
        }
        hasPending = true
        val wait = throttle.waitBeforeSend(pending.structuralKey(), now)
        if (wait <= 0L) flush() else scheduleFlush(wait)
    }

    /**
     * Nothing is running any more. When the transfer behind the activity failed, or is waiting for
     * the app to refresh its link, the activity gets that as its final state ("Failed" or
     * "Waiting" with the row's message) instead of being left estimating. A user pause or a
     * finished file still ends it (null).
     */
    private fun stoppedPayload(items: List<DownloadItem>): DownloadsLiveStatusPayload? {
        val last = lastRunning ?: return null
        val stopped = items.filter { it.id in lastMemberIds }
        val failed = stopped.firstOrNull { it.status == DownloadStatus.Failed }
        val waiting = stopped.firstOrNull { it.status == DownloadStatus.Paused && it.waitingForAppOpen }
        val reason = failed ?: waiting
        if (reason == null) {
            lastRunning = null
            lastMemberIds = emptySet()
            return null
        }
        return last.copy(
            status = if (failed != null) "Failed" else "Waiting",
            message = reason.errorMessage,
            bytesPerSecond = null,
            sampledAtEpochMs = null,
        )
    }

    /**
     * Writes the latest state now, skipping the once-a-second pacing. Called on the main queue
     * before a background wake's completion handler, so a failure reaches the activity before iOS
     * suspends the app again.
     */
    fun flushNow() {
        hasPending = true
        flush()
    }

    private fun scheduleFlush(waitMs: Long) {
        if (flushScheduled) return
        flushScheduled = true
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, waitMs * 1_000_000L), dispatch_get_main_queue()) {
            flushScheduled = false
            flush()
        }
    }

    /** Leaving the app: the latest state goes out now, so the estimate starts from it. */
    private fun observeLifecycle() {
        if (lifecycleObservers != null) return
        val center = NSNotificationCenter.defaultCenter
        lifecycleObservers = listOf(UIApplicationWillResignActiveNotification, UIApplicationDidEnterBackgroundNotification)
            .map { name ->
                center.addObserverForName(name, null, NSOperationQueue.mainQueue) { _ ->
                    hasPending = true
                    flush()
                }
            }
    }

    private fun flush() {
        if (!hasPending) return
        hasPending = false
        val payload = pending
        val now = nowMs()
        throttle.markSent(payload.structuralKey(), now)
        val encoded = payload?.let { json.encodeToString(it) }
        // The first call always writes: the stored payload may be left over from an earlier launch.
        if (written && encoded == lastWritten && encoded == null) return
        written = true
        lastWritten = encoded

        val defaults = NSUserDefaults.standardUserDefaults
        if (encoded == null) {
            defaults.removeObjectForKey(userDefaultsPayloadKey)
        } else {
            defaults.setObject(encoded, forKey = userDefaultsPayloadKey)
        }
        NSNotificationCenter.defaultCenter.postNotificationName(notificationName, null)
    }

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
}

/**
 * One Live Activity at most, still only for running transfers. The oldest running transfer picks
 * it. When that is an episode, every running episode of the same show and season shares it,
 * lowest first ("S1 · E3 + E4 · 2 downloading") with their bytes and percent combined, and the
 * activity is keyed by the season so E3+E4 becoming E4+E5 updates it instead of replacing it.
 */
/** The running rows one activity covers: the oldest running one and its running season mates. */
internal fun liveStatusMembers(items: List<DownloadItem>): List<DownloadItem> {
    val running = items.filter { it.status == DownloadStatus.Downloading }
    val anchor = running.minByOrNull { it.createdAtEpochMs } ?: return emptyList()
    val season = anchor.seasonNumber
    return if (anchor.isEpisode && season != null) {
        running
            .filter { it.isEpisode && it.parentMetaId == anchor.parentMetaId && it.seasonNumber == season }
            .sortedBy { it.episodeNumber ?: 0 }
    } else {
        listOf(anchor)
    }
}

internal fun liveStatusPayloadFor(items: List<DownloadItem>): DownloadsLiveStatusPayload? {
    val running = items.filter { it.status == DownloadStatus.Downloading }
    val anchor = running.minByOrNull { it.createdAtEpochMs } ?: return null
    val season = anchor.seasonNumber
    val members = liveStatusMembers(items)
    val sized = members.filter { (it.totalBytes ?: 0L) > 0L }
    val allSized = sized.size == members.size
    val downloaded = members.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
    val total = if (allSized) sized.sumOf { it.totalBytes ?: 0L } else null
    val percent = if (sized.isNotEmpty()) {
        val sizedTotal = sized.sumOf { it.totalBytes ?: 0L }
        val sizedDone = sized.sumOf { it.downloadedBytes.coerceIn(0L, it.totalBytes ?: 0L) }
        ((sizedDone.toDouble() / sizedTotal.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    } else {
        -1
    }
    val detail = if (anchor.isEpisode && season != null) {
        if (members.size >= 2) {
            "S$season · " + members.joinToString(" + ") { "E${it.episodeNumber}" } + " · ${members.size} downloading"
        } else {
            listOfNotNull(
                "S${season}E${anchor.episodeNumber}",
                anchor.displaySubtitle.trim().takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }
    } else {
        null
    }
    return DownloadsLiveStatusPayload(
        id = if (anchor.isEpisode && season != null) "season:${anchor.parentMetaId.trim()}|$season" else anchor.id,
        title = anchor.title,
        subtitle = if (anchor.isEpisode && season != null) "S$season" else anchor.displaySubtitle,
        status = anchor.status.name,
        downloadedBytes = downloaded,
        totalBytes = total,
        progressPercent = percent,
        detail = detail,
    )
}

@Serializable
internal data class DownloadsLiveStatusPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val progressPercent: Int,
    val detail: String? = null,
    /** Smoothed bytes per second over the last ~10–20 s, null until known. */
    val bytesPerSecond: Double? = null,
    /** When [downloadedBytes] and [bytesPerSecond] were taken. */
    val sampledAtEpochMs: Long? = null,
    /** "Failed" / "Waiting" only: why it stopped, shown in place of the size line. */
    val message: String? = null,
)

/** A change here (activity, status, episode set, known size) skips the once-a-second pacing. */
internal fun DownloadsLiveStatusPayload?.structuralKey(): String? =
    this?.let { "$id|$status|$detail|${totalBytes != null}" }
