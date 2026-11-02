package com.nuvio.app.features.streams

import com.nuvio.app.core.network.NetworkCondition
import kotlinx.coroutines.TimeoutCancellationException

internal const val ADDON_STREAM_RETRY_DELAY_MS = 800L
internal const val ADDON_STREAM_ATTEMPT_TIMEOUT_MS = 60_000L

internal data class AddonFetchRow(
    val name: String,
    val status: AddonStreamFetchStatus,
    val retryUsed: Boolean,
)

internal enum class StreamListPhase {
    Loading,
    OfflineEmpty,
    Empty,
    Results,
}

/** Device has a path to the network, so one retry can still reach an addon. */
internal fun NetworkCondition.addonRetriesAllowed(): Boolean =
    this == NetworkCondition.Online || this == NetworkCondition.ServersUnreachable

/** No network at all. Addon requests cannot succeed until this clears. */
internal fun NetworkCondition.streamsDeviceOffline(): Boolean =
    this == NetworkCondition.NoInternet

internal fun AddonStreamGroup.resolvedFetchStatus(): AddonStreamFetchStatus =
    fetchStatus ?: if (isLoading) {
        AddonStreamFetchStatus.Loading
    } else {
        AddonStreamFetchStatus.Loaded(streams.size)
    }

internal fun AddonStreamGroup.toFetchRow(): AddonFetchRow =
    AddonFetchRow(
        name = addonName,
        status = resolvedFetchStatus(),
        retryUsed = retryUsed,
    )

/**
 * One retry for a failed addon.
 *
 * [screenOpenWhenConnectionReturns] is the gate for the offline-to-online retry.
 * The in-list automatic retry passes true, because that attempt only runs while
 * the streams screen is open. A 4xx and a real empty result are not retried.
 */
internal fun shouldRetryAddon(
    status: AddonStreamFetchStatus,
    online: Boolean,
    retryUsed: Boolean,
    screenOpenWhenConnectionReturns: Boolean,
): Boolean {
    if (!online || retryUsed || !screenOpenWhenConnectionReturns) return false
    return status.isAutoRetryableFailure()
}

internal fun AddonStreamFetchStatus.isAutoRetryableFailure(): Boolean {
    val failed = this as? AddonStreamFetchStatus.Failed ?: return false
    return when (failed.kind) {
        AddonStreamFailureKind.Timeout,
        AddonStreamFailureKind.Offline,
        -> true
        AddonStreamFailureKind.HttpError -> {
            val code = failed.httpStatus ?: return false
            code !in 400..499
        }
    }
}

/** Plain addon names for the failed row. URL-shaped or credential-shaped labels are dropped. */
internal fun failedAddonChipNames(rows: List<AddonFetchRow>): List<String> =
    rows.mapNotNull { row ->
        if (row.status !is AddonStreamFetchStatus.Failed) return@mapNotNull null
        addonChipLabel(row.name).takeIf { it.isNotEmpty() }
    }

internal fun addonChipLabel(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ""
    val lower = trimmed.lowercase()
    if (
        "://" in lower ||
        "token=" in lower ||
        "apikey=" in lower ||
        "api_key=" in lower ||
        "key=" in lower
    ) {
        return ""
    }
    return trimmed
}

internal fun resolveStreamListPhase(
    statuses: List<AddonStreamFetchStatus>,
    streamCounts: List<Int>,
    deviceOffline: Boolean,
): StreamListPhase {
    if (streamCounts.any { it > 0 }) return StreamListPhase.Results
    if (deviceOffline && statuses.isNotEmpty() && statuses.none { it is AddonStreamFetchStatus.Loaded }) {
        return StreamListPhase.OfflineEmpty
    }
    if (statuses.any { it is AddonStreamFetchStatus.Loading }) return StreamListPhase.Loading
    return StreamListPhase.Empty
}

internal fun classifyAddonFetchFailure(
    error: Throwable,
    deviceOffline: Boolean,
): AddonStreamFetchStatus.Failed {
    if (deviceOffline || error.isConnectivityFailure()) {
        return AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Offline)
    }
    if (error is TimeoutCancellationException || error.looksLikeTimeout()) {
        return AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Timeout)
    }
    val status = error.httpStatusCode()
    if (status != null) {
        return AddonStreamFetchStatus.Failed(AddonStreamFailureKind.HttpError, httpStatus = status)
    }
    return AddonStreamFetchStatus.Failed(AddonStreamFailureKind.HttpError)
}

private fun Throwable.isConnectivityFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val type = current::class.simpleName.orEmpty()
        if (
            type.contains("UnknownHost", ignoreCase = true) ||
            type.contains("UnresolvedAddress", ignoreCase = true) ||
            type.contains("NoRoute", ignoreCase = true) ||
            type.contains("ConnectException", ignoreCase = true)
        ) {
            return true
        }
        val message = current.message?.lowercase().orEmpty()
        if (
            "unable to resolve" in message ||
            "no address associated" in message ||
            "network is unreachable" in message ||
            "connection refused" in message ||
            "software caused connection abort" in message ||
            message == "offline" ||
            "device is offline" in message
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun Throwable.looksLikeTimeout(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val type = current::class.simpleName.orEmpty()
        if (type.contains("Timeout", ignoreCase = true)) return true
        val message = current.message?.lowercase().orEmpty()
        if ("timed out" in message || "timeout" in message) return true
        current = current.cause
    }
    return false
}

private fun Throwable.httpStatusCode(): Int? {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message.orEmpty()
        val match = HTTP_STATUS.find(message)
        val code = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (code != null) return code
        current = current.cause
    }
    return null
}

private val HTTP_STATUS = Regex("""HTTP\s+(\d{3})""", RegexOption.IGNORE_CASE)
