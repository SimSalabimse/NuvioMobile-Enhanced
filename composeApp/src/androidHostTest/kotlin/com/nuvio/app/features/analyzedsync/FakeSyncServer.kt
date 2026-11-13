package com.nuvio.app.features.analyzedsync

import com.nuvio.app.features.analyzedsync.crypto.constantTimeEquals
import com.nuvio.app.features.analyzedsync.crypto.decodeBase64
import com.nuvio.app.features.analyzedsync.crypto.decodeBase64Url
import com.nuvio.app.features.analyzedsync.crypto.sha256
import com.nuvio.app.features.analyzedsync.transport.BlobEnvelope
import com.nuvio.app.features.analyzedsync.transport.CreateSpaceBody
import com.nuvio.app.features.analyzedsync.transport.PullPage
import com.nuvio.app.features.analyzedsync.transport.PulledBlob
import com.nuvio.app.features.analyzedsync.transport.PushResult
import com.nuvio.app.features.analyzedsync.transport.SyncTransport
import com.nuvio.app.features.analyzedsync.transport.TransportResponse

/**
 * In-memory stand-in for the Worker. Tests must not call the live server.
 * Seq numbers start at 1. Pull returns rows with seq greater than the cursor.
 */
internal class FakeSyncServer(private val enrollSecret: String) : SyncTransport {
    val pullCursors = mutableListOf<Long>()
    private val spaces = linkedMapOf<String, Space>()

    override suspend fun createSpace(
        baseUrl: String,
        enrollSecret: String,
        bearerToken: String,
        body: CreateSpaceBody,
    ): TransportResponse<Unit> {
        if (this.enrollSecret.length < 16 || !constantTimeEquals(this.enrollSecret.encodeToByteArray(), enrollSecret.encodeToByteArray())) {
            return TransportResponse.Failed(401, "unauthorized")
        }
        val token = tokenHash(bearerToken) ?: return TransportResponse.Failed(401, "unauthorized")
        val existing = spaces[body.spaceId]
        if (existing == null) {
            spaces[body.spaceId] = Space(token, body.saltBase64, body.keyCheckBase64)
            return TransportResponse.Ok(Unit)
        }
        if (!constantTimeEquals(existing.tokenHash, token)) return TransportResponse.Failed(401, "unauthorized")
        if (existing.salt != body.saltBase64 || existing.keyCheck != body.keyCheckBase64) {
            return TransportResponse.Failed(409, "conflict")
        }
        return TransportResponse.Ok(Unit)
    }

    override suspend fun join(baseUrl: String, spaceId: String, bearerToken: String): TransportResponse<Unit> {
        val space = spaces[spaceId] ?: return TransportResponse.Failed(404, "not_found")
        val token = tokenHash(bearerToken) ?: return TransportResponse.Failed(401, "unauthorized")
        if (!constantTimeEquals(space.tokenHash, token)) return TransportResponse.Failed(401, "unauthorized")
        return TransportResponse.Ok(Unit)
    }

    override suspend fun push(
        baseUrl: String,
        spaceId: String,
        bearerToken: String,
        deviceId: String,
        blobs: List<BlobEnvelope>,
    ): TransportResponse<List<PushResult>> {
        val space = authorized(spaceId, bearerToken) ?: return TransportResponse.Failed(if (spaces.containsKey(spaceId)) 401 else 404, if (spaces.containsKey(spaceId)) "unauthorized" else "not_found")
        if (blobs.isEmpty() || blobs.size > 50 || blobs.distinctBy { it.id }.size != blobs.size) {
            return TransportResponse.Failed(400, "bad_request")
        }
        if (blobs.distinctBy { it.nonce }.size != blobs.size) return TransportResponse.Failed(409, "conflict")
        for (blob in blobs) {
            val existing = space.blobs.find { it.blob.id == blob.id }
            if (existing != null && existing.blob.ciphertext != blob.ciphertext) return TransportResponse.Failed(409, "conflict")
            if (space.blobs.any { it.blob.nonce == blob.nonce && it.blob.id != blob.id }) return TransportResponse.Failed(409, "conflict")
        }
        val newBlobs = blobs.count { blob -> space.blobs.none { it.blob.id == blob.id } }
        if (space.blobs.size + newBlobs > 20_000) return TransportResponse.Failed(409, "quota_exceeded")
        val results = blobs.map { blob ->
            val existing = space.blobs.find { it.blob.id == blob.id }
            if (existing != null) {
                PushResult(blob.id, existing.seq, "duplicate")
            } else {
                val seq = (space.blobs.maxOfOrNull { it.seq } ?: 0L) + 1L
                space.blobs += StoredBlob(seq, blob, deviceId, receivedAtMs = seq)
                space.nonces += blob.nonce
                PushResult(blob.id, seq, "stored")
            }
        }
        return TransportResponse.Ok(results)
    }

    override suspend fun pull(
        baseUrl: String,
        spaceId: String,
        bearerToken: String,
        cursor: Long,
        limit: Int,
    ): TransportResponse<PullPage> {
        pullCursors += cursor
        val space = authorized(spaceId, bearerToken) ?: return TransportResponse.Failed(if (spaces.containsKey(spaceId)) 401 else 404, if (spaces.containsKey(spaceId)) "unauthorized" else "not_found")
        if (limit !in 1..100) return TransportResponse.Failed(400, "bad_request")
        val rows = space.blobs.filter { it.seq > cursor }.sortedBy { it.seq }
        val page = rows.take(limit)
        val hasMore = rows.size > page.size
        val next = page.lastOrNull()?.seq ?: cursor
        return TransportResponse.Ok(
            PullPage(
                blobs = page.map {
                    PulledBlob(it.seq, it.blob.id, it.blob.v, it.blob.kind, it.deviceId, it.blob.nonce, it.blob.ciphertext, it.receivedAtMs)
                },
                cursor = next,
                hasMore = hasMore,
            ),
        )
    }

    private fun authorized(spaceId: String, bearerToken: String): Space? {
        val space = spaces[spaceId] ?: return null
        val token = tokenHash(bearerToken) ?: return null
        if (!constantTimeEquals(space.tokenHash, token)) return null
        return space
    }

    private fun tokenHash(bearerToken: String): ByteArray? {
        if (bearerToken.length != 43) return null
        val raw = decodeBase64Url(bearerToken) ?: return null
        if (raw.size != 32) return null
        return sha256(raw)
    }

    private class Space(
        val tokenHash: ByteArray,
        val salt: String,
        val keyCheck: String,
        val blobs: MutableList<StoredBlob> = mutableListOf(),
        val nonces: MutableSet<String> = mutableSetOf(),
    )

    private class StoredBlob(val seq: Long, val blob: BlobEnvelope, val deviceId: String, val receivedAtMs: Long)
}
