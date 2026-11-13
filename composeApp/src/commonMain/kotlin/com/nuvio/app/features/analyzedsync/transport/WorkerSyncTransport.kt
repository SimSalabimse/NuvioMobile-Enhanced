package com.nuvio.app.features.analyzedsync.transport

import com.nuvio.app.core.network.readBoundedResponseBody
import com.nuvio.app.features.analyzedsync.SYNC_KDF_ITERATIONS
import com.nuvio.app.features.analyzedsync.json.canonicalJson
import com.nuvio.app.features.analyzedsync.json.jNum
import com.nuvio.app.features.analyzedsync.json.jObj
import com.nuvio.app.features.analyzedsync.json.jStr
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class WorkerSyncTransport(private val client: HttpClient) : SyncTransport {
    override suspend fun createSpace(
        baseUrl: String,
        enrollSecret: String,
        bearerToken: String,
        body: CreateSpaceBody,
    ): TransportResponse<Unit> {
        val payload = canonicalJson(
            jObj(
                "iterations" to jNum(SYNC_KDF_ITERATIONS.toLong()),
                "kdf" to jStr("pbkdf2-sha256"),
                "keyCheck" to jStr(body.keyCheckBase64),
                "salt" to jStr(body.saltBase64),
                "spaceId" to jStr(body.spaceId),
            ),
        )
        val response = send(
            method = HttpMethod.Post,
            url = joinUrl(baseUrl, "/v1/spaces"),
            bearerToken = bearerToken,
            enrollSecret = enrollSecret,
            body = payload,
        )
        return when (response.status) {
            200, 201 -> TransportResponse.Ok(Unit)
            else -> failed(response)
        }
    }

    override suspend fun join(baseUrl: String, spaceId: String, bearerToken: String): TransportResponse<Unit> {
        val response = send(
            method = HttpMethod.Post,
            url = joinUrl(baseUrl, "/v1/spaces/$spaceId/join"),
            bearerToken = bearerToken,
            enrollSecret = null,
            body = null,
        )
        return if (response.status == 200) TransportResponse.Ok(Unit) else failed(response)
    }

    override suspend fun push(
        baseUrl: String,
        spaceId: String,
        bearerToken: String,
        deviceId: String,
        blobs: List<BlobEnvelope>,
    ): TransportResponse<List<PushResult>> {
        val payload = buildString {
            append("{\"blobs\":[")
            blobs.forEachIndexed { index, blob ->
                if (index > 0) append(',')
                append("{\"ciphertext\":")
                append(jsonString(blob.ciphertext))
                append(",\"id\":")
                append(jsonString(blob.id))
                append(",\"kind\":")
                append(jsonString(blob.kind))
                append(",\"nonce\":")
                append(jsonString(blob.nonce))
                append(",\"v\":1}")
            }
            append("],\"deviceId\":")
            append(jsonString(deviceId))
            append('}')
        }
        val response = send(
            method = HttpMethod.Post,
            url = joinUrl(baseUrl, "/v1/spaces/$spaceId/batches"),
            bearerToken = bearerToken,
            enrollSecret = null,
            body = payload,
        )
        if (response.status != 200) return failed(response)
        val root = parseObject(response.body) ?: return TransportResponse.Failed(response.status, "bad_request")
        val results = root["results"] as? JsonArray ?: return TransportResponse.Failed(response.status, "bad_request")
        val parsed = results.map { element ->
            val item = element as? JsonObject ?: return TransportResponse.Failed(response.status, "bad_request")
            PushResult(
                id = item.string("id") ?: return TransportResponse.Failed(response.status, "bad_request"),
                seq = item.long("seq") ?: return TransportResponse.Failed(response.status, "bad_request"),
                status = item.string("status") ?: return TransportResponse.Failed(response.status, "bad_request"),
            )
        }
        return TransportResponse.Ok(parsed)
    }

    override suspend fun pull(
        baseUrl: String,
        spaceId: String,
        bearerToken: String,
        cursor: Long,
        limit: Int,
    ): TransportResponse<PullPage> {
        val response = send(
            method = HttpMethod.Get,
            url = joinUrl(baseUrl, "/v1/spaces/$spaceId/blobs?cursor=$cursor&limit=$limit"),
            bearerToken = bearerToken,
            enrollSecret = null,
            body = null,
        )
        if (response.status != 200) return failed(response)
        val root = parseObject(response.body) ?: return TransportResponse.Failed(response.status, "bad_request")
        val blobs = root["blobs"] as? JsonArray ?: return TransportResponse.Failed(response.status, "bad_request")
        val pageCursor = root.long("cursor") ?: return TransportResponse.Failed(response.status, "bad_request")
        val hasMore = root["hasMore"]?.jsonPrimitive?.booleanOrNull ?: return TransportResponse.Failed(response.status, "bad_request")
        val parsed = blobs.map { element ->
            val item = element as? JsonObject ?: return TransportResponse.Failed(response.status, "bad_request")
            PulledBlob(
                seq = item.long("seq") ?: return TransportResponse.Failed(response.status, "bad_request"),
                id = item.string("id") ?: return TransportResponse.Failed(response.status, "bad_request"),
                v = item.long("v")?.toInt() ?: return TransportResponse.Failed(response.status, "bad_request"),
                kind = item.string("kind") ?: return TransportResponse.Failed(response.status, "bad_request"),
                deviceId = item.string("deviceId") ?: return TransportResponse.Failed(response.status, "bad_request"),
                nonce = item.string("nonce") ?: return TransportResponse.Failed(response.status, "bad_request"),
                ciphertext = item.string("ciphertext") ?: return TransportResponse.Failed(response.status, "bad_request"),
                receivedAtMs = item.long("receivedAtMs") ?: 0L,
            )
        }
        return TransportResponse.Ok(PullPage(parsed, pageCursor, hasMore))
    }

    private suspend fun send(
        method: HttpMethod,
        url: String,
        bearerToken: String?,
        enrollSecret: String?,
        body: String?,
    ): RawResponse = try {
        client.prepareRequest(url) {
            this.method = method
            header("Accept", "application/json")
            if (bearerToken != null) header("Authorization", "Bearer $bearerToken")
            if (enrollSecret != null) header("X-Nuvio-Enroll", enrollSecret)
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
        }.execute { response ->
            RawResponse(
                status = response.status.value,
                body = readBoundedResponseBody(response.bodyAsChannel(), response.contentLength(), maxBytes = 8 * 1024 * 1024),
                retryAfter = response.headers["retry-after"],
            )
        }
    } catch (_: Exception) {
        RawResponse(status = 0, body = "", retryAfter = null)
    }

    private fun failed(response: RawResponse): TransportResponse.Failed {
        val code = parseObject(response.body)?.string("error") ?: when (response.status) {
            0 -> "unreachable"
            401 -> "unauthorized"
            404 -> "not_found"
            409 -> "conflict"
            413 -> "payload_too_large"
            429 -> "rate_limited"
            else -> "internal"
        }
        val retryAfterSeconds = if (response.status == 429) retryAfterSeconds(response.retryAfter) else null
        return TransportResponse.Failed(response.status, code, retryAfterSeconds)
    }

    private fun retryAfterSeconds(header: String?): Int {
        val parsed = header?.trim()?.toIntOrNull()
        return (parsed ?: 1).coerceIn(1, 600)
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun joinUrl(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path

    private data class RawResponse(val status: Int, val body: String, val retryAfter: String?)
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> append(ch)
        }
    }
    append('"')
}
