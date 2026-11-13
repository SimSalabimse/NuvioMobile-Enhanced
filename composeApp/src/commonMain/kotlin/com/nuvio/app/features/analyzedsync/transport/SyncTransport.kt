package com.nuvio.app.features.analyzedsync.transport

internal data class BlobEnvelope(
    val id: String,
    val v: Int,
    val kind: String,
    val nonce: String,
    val ciphertext: String,
)

internal data class PulledBlob(
    val seq: Long,
    val id: String,
    val v: Int,
    val kind: String,
    val deviceId: String,
    val nonce: String,
    val ciphertext: String,
    val receivedAtMs: Long,
)

internal data class PullPage(
    val blobs: List<PulledBlob>,
    val cursor: Long,
    val hasMore: Boolean,
)

internal data class PushResult(val id: String, val seq: Long, val status: String)

internal data class CreateSpaceBody(
    val spaceId: String,
    val saltBase64: String,
    val keyCheckBase64: String,
)

internal sealed interface TransportResponse<out T> {
    data class Ok<T>(val value: T) : TransportResponse<T>
    data class Failed(
        val status: Int,
        val code: String,
        val retryAfterSeconds: Int? = null,
    ) : TransportResponse<Nothing>
}

internal interface SyncTransport {
    suspend fun createSpace(baseUrl: String, enrollSecret: String, bearerToken: String, body: CreateSpaceBody): TransportResponse<Unit>
    suspend fun join(baseUrl: String, spaceId: String, bearerToken: String): TransportResponse<Unit>
    suspend fun push(
        baseUrl: String,
        spaceId: String,
        bearerToken: String,
        deviceId: String,
        blobs: List<BlobEnvelope>,
    ): TransportResponse<List<PushResult>>
    suspend fun pull(
        baseUrl: String,
        spaceId: String,
        bearerToken: String,
        cursor: Long,
        limit: Int,
    ): TransportResponse<PullPage>
}

internal fun honestSyncStatus(failure: TransportResponse.Failed): String = when (failure.code) {
    "unauthorized" -> "The server refused this phone."
    "not_found" -> "That sync space does not exist."
    "quota_exceeded" -> "This sync space is full."
    "conflict" -> "The server rejected a change. Sync again."
    "rate_limited" -> "The server asked this phone to wait."
    "payload_too_large" -> "That change was too large to send."
    "bad_request" -> "The server did not accept that request."
    else -> if (failure.status == 0) "Could not reach the server." else "Sync failed (${failure.status})."
}
