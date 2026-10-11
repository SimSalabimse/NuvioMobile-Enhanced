package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults

internal actual object DownloadsLiveStatusPlatform {
    private const val notificationName = "NuvioDownloadsLiveStatusUpdated"
    private const val userDefaultsPayloadKey = "nuvio.downloads.live_status.payload"

    private val json = Json {
        encodeDefaults = true
    }

    private var lastPayload: String? = null
    private var published = false

    actual fun onItemsChanged(items: List<DownloadItem>) {
        // Only a running transfer has a Live Activity. A paused or failed row left in the list used to
        // become the "primary" item after a cancel and got an activity that nothing ever updated or
        // ended, which is what showed up stuck on the Lock Screen once the app was left.
        // The oldest running transfer is the one shown, so two parallel downloads do not swap the
        // activity on every progress tick.
        val primary = items
            .filter { item -> item.status == DownloadStatus.Downloading }
            .minByOrNull { it.createdAtEpochMs }

        val payload = primary?.let { item ->
            json.encodeToString(
                DownloadsLiveStatusPayload(
                    id = item.id,
                    title = item.title,
                    subtitle = item.displaySubtitle,
                    status = item.status.name,
                    downloadedBytes = item.downloadedBytes,
                    totalBytes = item.totalBytes,
                    progressPercent = if (item.totalBytes != null && item.totalBytes > 0L) {
                        ((item.downloadedBytes.toDouble() / item.totalBytes.toDouble()) * 100.0)
                            .toInt()
                            .coerceIn(0, 100)
                    } else {
                        -1
                    },
                ),
            )
        }

        // The first call always writes: the stored payload may be left over from an earlier launch.
        if (published && payload == lastPayload) return
        published = true
        lastPayload = payload

        val defaults = NSUserDefaults.standardUserDefaults
        if (payload == null) {
            defaults.removeObjectForKey(userDefaultsPayloadKey)
        } else {
            defaults.setObject(payload, forKey = userDefaultsPayloadKey)
        }

        NSNotificationCenter.defaultCenter.postNotificationName(notificationName, null)
    }
}

@Serializable
private data class DownloadsLiveStatusPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val progressPercent: Int,
)
