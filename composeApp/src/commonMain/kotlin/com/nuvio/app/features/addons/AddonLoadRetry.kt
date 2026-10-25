package com.nuvio.app.features.addons

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException

/** One retry, long enough to miss a blip and short enough that a healthy load is unchanged. */
internal const val TRANSIENT_LOAD_RETRY_DELAY_MS = 1_750L

internal sealed interface AddonLoadResult {
    data class Loaded(val manifest: AddonManifest) : AddonLoadResult
    data class Failed(val errorMessage: String, val cause: Throwable) : AddonLoadResult
}

/**
 * True when a sync should start a fetch for this addon.
 * [existing] is the addon from before state was rebuilt. A failed addon has no manifest and no
 * active job; rebuilding it marks it refreshing and would hide that it still needs a fetch.
 */
internal fun shouldLaunchAddonRefresh(existing: ManagedAddon?, refreshJobActive: Boolean): Boolean =
    existing == null || (existing.manifest == null && !refreshJobActive)

/**
 * Network errors, timeouts, and HTTP 5xx are retried once. HTTP 4xx and manifest/parse failures
 * are not. [CancellationException] is always rethrown and never becomes [AddonLoadResult.Failed].
 */
internal suspend fun loadAddonWithRetry(
    delayMs: Long = TRANSIENT_LOAD_RETRY_DELAY_MS,
    failureMessage: (Throwable) -> String,
    load: suspend () -> AddonManifest,
): AddonLoadResult {
    return try {
        AddonLoadResult.Loaded(withTransientRetry(delayMs = delayMs, block = load))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        AddonLoadResult.Failed(failureMessage(error), error)
    }
}

internal suspend fun <T> withTransientRetry(
    delayMs: Long = TRANSIENT_LOAD_RETRY_DELAY_MS,
    block: suspend () -> T,
): T {
    try {
        return block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        if (!isTransientLoadFailure(error)) throw error
        delay(delayMs)
        return block()
    }
}

internal fun isTransientLoadFailure(error: Throwable): Boolean {
    if (error is CancellationException) return false
    httpStatusCode(error)?.let { code -> return code in 500..599 }
    if (error is IllegalArgumentException || error is SerializationException) return false
    if (error is IllegalStateException) {
        val message = error.message.orEmpty()
        return message.contains("timed out", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true)
    }
    return true
}

private val httpStatusInMessage = Regex("""HTTP\D{0,20}(\d{3})""")

internal fun httpStatusCode(error: Throwable): Int? {
    val message = error.message ?: return null
    return httpStatusInMessage.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
