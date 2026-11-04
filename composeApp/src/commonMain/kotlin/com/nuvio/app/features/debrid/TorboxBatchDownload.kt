package com.nuvio.app.features.debrid

import com.nuvio.app.core.logging.InAppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * TorBox `requestdl/batch` (v9.3) documents the request, not the response.
 * The live OpenAPI schema for HTTP 200 is empty, and the public TorBox SDKs do not model this
 * route yet, so three bodies are accepted:
 * - `data` is a list of URL strings, paired with [requestedFileIds] in order
 * - `data` is a list of objects with a file id and a link field
 * - `data` is a map of file id to URL
 * Anything else is a failure (null) and the caller uses per-file `requestdl`.
 */
internal fun parseTorboxBatchDownloadLinks(
    rawBody: String,
    requestedFileIds: List<Int>,
): Map<Int, String>? {
    val root = try {
        DebridApiJson.json.parseToJsonElement(rawBody)
    } catch (_: Exception) {
        return null
    }
    if (root !is JsonObject) return null
    val success = root["success"]
    if (success is JsonPrimitive && success.content.equals("false", ignoreCase = true)) return null
    val links = when (val data = root["data"]) {
        is JsonArray -> parseBatchArray(data, requestedFileIds)
        is JsonObject -> parseBatchMap(data)
        else -> null
    } ?: return null
    return links.ifEmpty { null }
}

/**
 * Batch when several files share one torrent. A failed or partial batch falls back to per-file
 * `requestdl` for every id that still has no link.
 */
internal suspend fun torboxDownloadLinksForFiles(
    apiKey: String,
    torrentId: Int,
    fileIds: List<Int>,
): Map<Int, String> {
    val ids = fileIds.distinct()
    if (ids.isEmpty()) return emptyMap()
    if (ids.size == 1) return perFileDownloadLinks(apiKey, torrentId, ids)
    val batched = TorboxApiClient.requestDownloadLinksBatch(
        apiKey = apiKey,
        torrentId = torrentId,
        fileIds = ids,
    ) ?: return perFileDownloadLinks(apiKey, torrentId, ids)
    val missing = ids.filter { batched[it].isNullOrBlank() }
    if (missing.isEmpty()) return ids.associateWith { id -> batched.getValue(id) }
    val filled = perFileDownloadLinks(apiKey, torrentId, missing)
    return ids.mapNotNull { id ->
        val url = batched[id]?.takeIf { it.isNotBlank() } ?: filled[id]
        url?.let { id to it }
    }.toMap()
}

internal fun logTorboxBatchFailure(
    apiKey: String,
    status: Int? = null,
    error: Throwable? = null,
    malformed: Boolean = false,
) {
    val detail = when {
        error != null -> torboxSafeErrorMessage(error, apiKey)
        malformed -> "unexpected response"
        status != null -> "HTTP $status"
        else -> "failed"
    }
    // Status and exception type only. The token is a query parameter, so the URL and body stay out.
    InAppLogger.warn("TorBox", "Batch download links failed ($detail).")
}

internal fun torboxSafeErrorMessage(error: Throwable, apiKey: String): String {
    val type = error::class.simpleName ?: "Exception"
    val message = error.message?.let { redactTorboxCredential(it, apiKey) }?.trim().orEmpty()
    return if (message.isBlank()) type else "$type: $message"
}

internal fun redactTorboxCredential(text: String, apiKey: String): String {
    var redacted = if (apiKey.isNotBlank()) text.replace(apiKey, "[redacted]") else text
    redacted = TOKEN_QUERY.replace(redacted, "$1[redacted]")
    redacted = BEARER.replace(redacted, "$1[redacted]")
    return redacted
}

private suspend fun perFileDownloadLinks(
    apiKey: String,
    torrentId: Int,
    fileIds: List<Int>,
): Map<Int, String> {
    val links = linkedMapOf<Int, String>()
    for (fileId in fileIds) {
        val url = try {
            val response = TorboxApiClient.requestDownloadLink(
                apiKey = apiKey,
                torrentId = torrentId,
                fileId = fileId,
            )
            response.body?.data?.takeIf { response.isSuccessful && it.isNotBlank() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            InAppLogger.warn("TorBox", "Download link failed (${torboxSafeErrorMessage(error, apiKey)}).")
            null
        }
        if (url != null) links[fileId] = url
    }
    return links
}

private fun parseBatchArray(
    data: JsonArray,
    requestedFileIds: List<Int>,
): Map<Int, String>? {
    if (data.isEmpty()) return null
    val primitives = data.mapNotNull { it as? JsonPrimitive }
    if (primitives.size == data.size && primitives.all { it.isString }) {
        val urls = primitives.map { it.content }
        if (urls.any { it.isBlank() } || requestedFileIds.isEmpty()) return null
        return requestedFileIds.zip(urls).toMap().ifEmpty { null }
    }
    if (data.any { it !is JsonObject }) return null
    val links = linkedMapOf<Int, String>()
    for (element in data) {
        val obj = element as JsonObject
        val id = obj.fileId() ?: continue
        val url = obj.linkUrl() ?: continue
        links[id] = url
    }
    return links.ifEmpty { null }
}

private fun parseBatchMap(data: JsonObject): Map<Int, String>? {
    if (data.isEmpty()) return null
    val links = linkedMapOf<Int, String>()
    for ((key, value) in data) {
        val id = key.toIntOrNull() ?: continue
        val url = when (value) {
            is JsonPrimitive -> value.contentOrNull?.takeIf { value.isString && it.isNotBlank() }
            is JsonObject -> value.linkUrl()
            else -> null
        } ?: continue
        links[id] = url
    }
    return links.ifEmpty { null }
}

private fun JsonObject.fileId(): Int? {
    for (name in listOf("file_id", "fileId", "id")) {
        val id = (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        if (id != null) return id
    }
    return null
}

private fun JsonObject.linkUrl(): String? {
    for (name in listOf("link", "url", "download_url", "download")) {
        val url = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (url != null && (this[name] as JsonPrimitive).isString) return url
    }
    return null
}

private val TOKEN_QUERY = Regex("(?i)(token=)[^&\\s\"']+")
private val BEARER = Regex("(?i)(Bearer\\s+)\\S+")
