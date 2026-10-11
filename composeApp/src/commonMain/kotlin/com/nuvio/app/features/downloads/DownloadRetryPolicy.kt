package com.nuvio.app.features.downloads

/**
 * A dropped or timed-out connection is not a failed download. NSURLSession reports a server or
 * Wi-Fi that drops a long transfer as "The network connection was lost." (-1005); that used to
 * mark the row Failed straight away. These codes are retried from the bytes already on disk.
 */
internal object DownloadRetryPolicy {
    /** NSURLErrorTimedOut, CannotFindHost, CannotConnectToHost, NetworkConnectionLost,
     *  DNSLookupFailed, NotConnectedToInternet, InternationalRoamingOff, CallIsActive, DataNotAllowed. */
    private val transientUrlErrorCodes = setOf(-1001L, -1003L, -1004L, -1005L, -1006L, -1009L, -1018L, -1019L, -1020L)

    /** Seconds before each retry. After the last one the download is marked Failed. */
    val backoffSeconds: List<Long> = listOf(2L, 5L, 10L, 20L, 40L, 60L, 120L, 300L)

    /** A retry that got this much further starts the count again. */
    const val PROGRESS_RESETS_ATTEMPTS_BYTES: Long = 4L * 1024L * 1024L

    fun isTransient(domain: String?, code: Long): Boolean =
        domain == "NSURLErrorDomain" && code in transientUrlErrorCodes
}

/** Retry bookkeeping per download id. */
internal class DownloadRetryTracker(
    private val backoffSeconds: List<Long> = DownloadRetryPolicy.backoffSeconds,
    private val resetAfterBytes: Long = DownloadRetryPolicy.PROGRESS_RESETS_ATTEMPTS_BYTES,
) {
    private class State(var attempts: Int, var bytesAtLastFailure: Long)

    private val states = mutableMapOf<String, State>()

    /**
     * A transient failure of [downloadId] with [bytesOnDisk] kept. Returns the delay in seconds
     * before the next attempt, or null when the retries are used up.
     */
    fun nextDelaySeconds(downloadId: String, bytesOnDisk: Long): Long? {
        val state = states.getOrPut(downloadId) { State(attempts = 0, bytesAtLastFailure = bytesOnDisk) }
        if (bytesOnDisk - state.bytesAtLastFailure >= resetAfterBytes) state.attempts = 0
        state.bytesAtLastFailure = bytesOnDisk
        val delay = backoffSeconds.getOrNull(state.attempts) ?: run {
            states.remove(downloadId)
            return null
        }
        state.attempts += 1
        return delay
    }

    fun forget(downloadId: String) {
        states.remove(downloadId)
    }
}
