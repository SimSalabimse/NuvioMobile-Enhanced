package com.nuvio.app.features.debrid

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TorboxBatchDownloadTest {
    @Test
    fun oneBatchCallReturnsEveryFile() = runTest {
        val token = "tb-secret-token-batch-9f3a"
        val fileIds = (1..25).toList()
        val calls = mutableListOf<String>()
        val restore = stub { url, body ->
            calls += route(url)
            requireToken(url, token)
            assertFalse(token in body, "request body contained the API token")
            assertTrue(body.contains("\"file_ids\""))
            fileIds.forEach { id -> assertTrue(body.contains(id.toString())) }
            ok(batchMap(fileIds))
        }
        try {
            val links = TorboxApiClient.requestDownloadLinksBatch(token, torrentId = 55, fileIds = fileIds)
            assertEquals(fileIds.associateWith { "https://cdn.example/$it" }, links)
            assertEquals(listOf("torrents/requestdl/batch"), calls)
        } finally {
            restore()
        }
    }

    @Test
    fun parsesUrlListObjectListAndMap() = runTest {
        assertEquals(
            mapOf(4 to "https://cdn.example/4", 5 to "https://cdn.example/5"),
            linksFor(listOf(4, 5), """{"success":true,"data":["https://cdn.example/4","https://cdn.example/5"]}"""),
        )
        assertEquals(
            mapOf(4 to "https://cdn.example/4", 5 to "https://cdn.example/5"),
            linksFor(
                listOf(4, 5),
                """{"success":true,"data":[{"file_id":4,"url":"https://cdn.example/4"},{"id":5,"link":"https://cdn.example/5"}]}""",
            ),
        )
        assertEquals(
            mapOf(4 to "https://cdn.example/4", 5 to "https://cdn.example/5"),
            linksFor(listOf(4, 5), """{"success":true,"data":{"4":"https://cdn.example/4","5":"https://cdn.example/5"}}"""),
        )
    }

    @Test
    fun fallsBackToPerFileOn404() = runTest {
        assertFallback(status = 404, body = """{"success":false,"detail":"tb-secret-token-batch-9f3a"}""")
    }

    @Test
    fun fallsBackToPerFileOn422() = runTest {
        assertFallback(status = 422, body = """{"detail":"validation"}""")
    }

    @Test
    fun fallsBackToPerFileOnMalformedBody() = runTest {
        assertFallback(status = 200, body = """{"success":true,"data":{"nested":true}}""")
    }

    @Test
    fun partialBatchFetchesOnlyMissingIds() = runTest {
        val token = "tb-secret-token-batch-9f3a"
        val singleIds = mutableListOf<String>()
        val routes = mutableListOf<String>()
        val restore = stub { url, _ ->
            val path = route(url)
            routes += path
            when (path) {
                "torrents/requestdl/batch" -> ok(
                    """{"success":true,"data":{"1":"https://cdn.example/1","3":"https://cdn.example/3"}}""",
                )
                "torrents/requestdl" -> {
                    val fileId = queryParam(url, "file_id") ?: error("per-file request has no file id")
                    singleIds += fileId
                    ok("""{"success":true,"data":"https://cdn.example/$fileId"}""")
                }
                else -> error("unexpected TorBox route")
            }
        }
        try {
            val links = torboxDownloadLinksForFiles(token, torrentId = 55, fileIds = listOf(1, 2, 3))
            assertEquals(
                mapOf(1 to "https://cdn.example/1", 2 to "https://cdn.example/2", 3 to "https://cdn.example/3"),
                links,
            )
            assertEquals(listOf("2"), singleIds)
            assertEquals(listOf("torrents/requestdl/batch", "torrents/requestdl"), routes)
        } finally {
            restore()
        }
    }

    @Test
    fun logAndExceptionMessagesNeverContainTheToken() = runTest {
        val token = "tb-secret-token-batch-9f3a"
        InAppLogger.clear()
        val restore = stub { url, _ ->
            val path = route(url)
            if (path.endsWith("requestdl/batch")) {
                throw IllegalStateException(
                    "GET $url Authorization: Bearer $token",
                )
            }
            throw IllegalStateException("GET $url token=$token")
        }
        var escaped: Throwable? = null
        try {
            try {
                torboxDownloadLinksForFiles(token, torrentId = 7, fileIds = listOf(1, 2, 3))
            } catch (error: Throwable) {
                escaped = error
            }
            assertNull(escaped)
            val logged = InAppLogger.entries.value.joinToString("\n") { "${it.tag} ${it.message} ${it.line}" }
            assertTrue(logged.contains("Batch download links failed"))
            assertTrue(logged.contains("[redacted]"))
            assertFalse(logged.contains(token), "log output contained the API token")
        } finally {
            restore()
            InAppLogger.clear()
        }
    }

    @Test
    fun seasonPackUsesOneBatchCallForTwentyFiveEpisodes() = runTest {
        val token = "tb-secret-token-batch-9f3a"
        val routes = mutableListOf<String>()
        val restore = stub { url, _ ->
            routes += route(url)
            when (route(url)) {
                "torrents/createtorrent" -> ok("""{"success":true,"data":{"torrent_id":55}}""")
                "torrents/mylist" -> ok(mylist((1..25).toList()))
                "torrents/requestdl/batch" -> ok(batchMap((1..25).toList()))
                "torrents/requestdl" -> error("per-file requestdl was called for a complete batch")
                else -> error("unexpected TorBox route")
            }
        }
        try {
            val episodes = (1..25).map { episode -> packEpisode(hash = PACK_HASH, episode = episode) }
            val streams = resolveTorboxSeasonPackEpisodes(episodes, token)
            assertEquals(25, streams.size)
            streams.forEachIndexed { index, stream ->
                assertEquals("https://cdn.example/${index + 1}", stream.playableDirectUrl)
            }
            assertEquals(
                listOf("torrents/createtorrent", "torrents/mylist", "torrents/requestdl/batch"),
                routes,
            )
        } finally {
            restore()
        }
    }

    private suspend fun linksFor(fileIds: List<Int>, body: String): Map<Int, String>? {
        val restore = stub { _, _ -> ok(body) }
        return try {
            TorboxApiClient.requestDownloadLinksBatch("tb-secret-token-batch-9f3a", 55, fileIds)
        } finally {
            restore()
        }
    }

    private suspend fun assertFallback(status: Int, body: String) {
        InAppLogger.clear()
        val singleIds = mutableListOf<String>()
        val routes = mutableListOf<String>()
        val restore = stub { url, _ ->
            val path = route(url)
            routes += path
            when (path) {
                "torrents/requestdl/batch" -> RawHttpResponse(status, "no", "redacted", body, emptyMap())
                "torrents/requestdl" -> {
                    val fileId = queryParam(url, "file_id") ?: error("per-file request has no file id")
                    singleIds += fileId
                    ok("""{"success":true,"data":"https://cdn.example/$fileId"}""")
                }
                else -> error("unexpected TorBox route")
            }
        }
        try {
            val links = torboxDownloadLinksForFiles("tb-secret-token-batch-9f3a", 55, listOf(1, 2, 3))
            assertEquals(
                mapOf(1 to "https://cdn.example/1", 2 to "https://cdn.example/2", 3 to "https://cdn.example/3"),
                links,
            )
            assertEquals(listOf("1", "2", "3"), singleIds)
            assertEquals(
                listOf(
                    "torrents/requestdl/batch",
                    "torrents/requestdl",
                    "torrents/requestdl",
                    "torrents/requestdl",
                ),
                routes,
            )
            val logged = InAppLogger.entries.value.joinToString("\n") { "${it.message}\n${it.line}" }
            assertTrue(logged.contains("Batch download links failed"))
            assertFalse(logged.contains(TOKEN), "log output contained the API token")
        } finally {
            restore()
            InAppLogger.clear()
        }
    }

    private fun stub(
        handler: suspend (url: String, body: String) -> RawHttpResponse,
    ): () -> Unit {
        val previous = TorboxApiClient.rawHttp
        TorboxApiClient.rawHttp = { _, url, headers, body, _ ->
            val path = route(url)
            if (path.startsWith("torrents/requestdl")) {
                requireToken(url, TOKEN)
            } else {
                check(headers["Authorization"] == "Bearer $TOKEN") {
                    "TorBox request is missing its authorization header"
                }
            }
            handler(url, body)
        }
        return { TorboxApiClient.rawHttp = previous }
    }
}

private fun packEpisode(hash: String, episode: Int): TorboxSeasonPackEpisode {
    val name = "Show.S01E${episode.toString().padStart(2, '0')}.mkv"
    return TorboxSeasonPackEpisode(
        stream = StreamItem(
            addonName = "Addon",
            addonId = "addon:season",
            infoHash = hash,
            behaviorHints = StreamBehaviorHints(filename = name),
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = "torbox",
                providerName = "Torbox",
                state = StreamDebridCacheState.CACHED,
            ),
        ),
        season = 1,
        episode = episode,
    )
}

private fun mylist(fileIds: List<Int>): String {
    val files = fileIds.joinToString(",") { id ->
        val name = "Show.S01E${id.toString().padStart(2, '0')}.mkv"
        """{"id":$id,"name":"$name","size":1000}"""
    }
    return """{"success":true,"data":{"id":55,"files":[$files]}}"""
}

private fun batchMap(fileIds: List<Int>): String {
    val entries = fileIds.joinToString(",") { id -> """"$id":"https://cdn.example/$id"""" }
    return """{"success":true,"data":{$entries}}"""
}

private fun ok(body: String) = RawHttpResponse(
    status = 200,
    statusText = "OK",
    url = "redacted",
    body = body,
    headers = emptyMap(),
)

private fun route(url: String): String = url.substringBefore('?').substringAfter("/v1/api/")

private fun queryParam(url: String, name: String): String? =
    url.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=')

private fun requireToken(url: String, token: String) {
    val present = url.substringAfter('?', "")
        .split('&')
        .any { part -> part.substringBefore('=') == "token" && part.substringAfter('=') == token }
    check(present) { "TorBox request is missing the token query parameter" }
}

private const val PACK_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val TOKEN = "tb-secret-token-batch-9f3a"
