package com.nuvio.app.features.downloads

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Loopback host for the partial-file player. Nothing else is accepted. */
internal const val PARTIAL_PLAYBACK_LOOPBACK_HOST: String = "127.0.0.1"

internal enum class DownloadWriterKind { Foreground, Background }

/**
 * One file has one writer. Foreground streaming and the background session task
 * take turns; a second acquire fails until the owner releases.
 */
internal class SingleFileWriterGate {
    var owner: DownloadWriterKind? = null
        private set

    fun acquire(writer: DownloadWriterKind): Boolean {
        if (owner != null && owner != writer) return false
        owner = writer
        return true
    }

    fun release(writer: DownloadWriterKind) {
        if (owner == writer) owner = null
    }
}

internal object IosWriterOwnership : SynchronizedObject() {
    private val gates = mutableMapOf<String, SingleFileWriterGate>()

    fun acquire(downloadId: String, writer: DownloadWriterKind): Boolean = synchronized(this) {
        gates.getOrPut(downloadId) { SingleFileWriterGate() }.acquire(writer)
    }

    fun release(downloadId: String, writer: DownloadWriterKind) = synchronized(this) {
        gates[downloadId]?.release(writer)
    }
}

/** True while in-app playback, including Picture in Picture, is keeping the process alive. */
internal object InAppPlaybackKeepAlive {
    private val playing = atomic(false)

    fun setPlaying(value: Boolean) {
        playing.value = value
    }

    fun isPlaying(): Boolean = playing.value
}

/** Holds a `.part` path open until the in-app player closes it. */
internal object PartialPlaybackLease : SynchronizedObject() {
    private val counts = mutableMapOf<String, Int>()

    fun acquire(downloadId: String) = synchronized(this) {
        counts[downloadId] = (counts[downloadId] ?: 0) + 1
    }

    fun release(downloadId: String) = synchronized(this) {
        val next = (counts[downloadId] ?: 0) - 1
        if (next <= 0) counts.remove(downloadId) else counts[downloadId] = next
    }

    fun isHeld(downloadId: String): Boolean = synchronized(this) {
        (counts[downloadId] ?: 0) > 0
    }
}

internal fun loopbackPartialUrl(port: Int, downloadId: String): String {
    require(port in 1..65535)
    require(downloadId.isNotBlank() && downloadId.none { it == '/' || it == '\\' || it.isWhitespace() })
    return "http://$PARTIAL_PLAYBACK_LOOPBACK_HOST:$port/partial/$downloadId"
}

internal fun partialPlaybackDownloadId(url: String): String? {
    val trimmed = url.trim()
    val marker = "://$PARTIAL_PLAYBACK_LOOPBACK_HOST:"
    val scheme = trimmed.substringBefore(marker, missingDelimiterValue = "")
    if (scheme != "http") return null
    val afterHost = trimmed.substringAfter(marker, missingDelimiterValue = "")
    val portAndPath = afterHost.substringBefore('?')
    val slash = portAndPath.indexOf('/')
    if (slash <= 0) return null
    val port = portAndPath.substring(0, slash).toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return partialIdFromTarget(portAndPath.substring(slash))
}

internal fun isLoopbackPartialPlaybackUrl(url: String?): Boolean =
    !url.isNullOrBlank() && partialPlaybackDownloadId(url) != null
