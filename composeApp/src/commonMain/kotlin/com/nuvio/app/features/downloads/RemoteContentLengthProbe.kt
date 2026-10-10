package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.cancel

internal object RemoteContentLengthProbe {
    private val client by lazy { createApiHttpClient() }

    suspend fun probe(target: SeasonProbeTarget): Long? {
        probeOnce(target, ranged = false)?.let { return it }
        return probeOnce(target, ranged = true)
    }

    private suspend fun probeOnce(target: SeasonProbeTarget, ranged: Boolean): Long? = runCatching {
        if (!ranged) {
            val response = client.head(target.url) {
                applyProbeTimeout()
                writeHeaders(target.headers)
            }
            return@runCatching remoteContentLengthBytes(
                statusCode = response.status.value,
                headers = response.headers.toMultiMap(),
                requestWasRanged = false,
            )
        }
        client.prepareGet(target.url) {
            applyProbeTimeout()
            header(HttpHeaders.Range, "bytes=0-0")
            writeHeaders(target.headers)
        }.execute { response ->
            val size = remoteContentLengthBytes(
                statusCode = response.status.value,
                headers = response.headers.toMultiMap(),
                requestWasRanged = true,
            )
            response.bodyAsChannel().cancel(null)
            size
        }
    }.getOrNull()
}

private fun io.ktor.client.request.HttpRequestBuilder.applyProbeTimeout() {
    timeout {
        requestTimeoutMillis = 8_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 8_000
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.writeHeaders(headers: Map<String, String>) {
    headers.forEach { (name, value) ->
        if (name.equals(HttpHeaders.Range, ignoreCase = true)) return@forEach
        if (name.isBlank() || value.isBlank()) return@forEach
        header(name, value)
    }
}

private fun Headers.toMultiMap(): Map<String, List<String>> =
    entries().associate { entry -> entry.key to entry.value }
