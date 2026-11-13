package com.nuvio.app.features.analyzedsync

import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analyzedsync.clock.Hlc
import com.nuvio.app.features.analyzedsync.crypto.RoutingAad
import com.nuvio.app.features.analyzedsync.crypto.associatedData
import com.nuvio.app.features.analyzedsync.crypto.decodeBase64
import com.nuvio.app.features.analyzedsync.crypto.decodeHex
import com.nuvio.app.features.analyzedsync.crypto.decryptBlob
import com.nuvio.app.features.analyzedsync.engine.AnalyzedSyncEngine
import com.nuvio.app.features.analyzedsync.engine.SyncConfig
import com.nuvio.app.features.analyzedsync.engine.SyncSessionState
import com.nuvio.app.features.analyzedsync.engine.bearer
import com.nuvio.app.features.analyzedsync.json.canonicalJson
import com.nuvio.app.features.analyzedsync.json.toSyncJson
import com.nuvio.app.features.analyzedsync.merge.mergeRecords
import com.nuvio.app.features.analyzedsync.record.DetectionRecord
import com.nuvio.app.features.analyzedsync.record.MarkRecord
import com.nuvio.app.features.analyzedsync.record.detectionId
import com.nuvio.app.features.analyzedsync.record.noneId
import com.nuvio.app.features.analyzedsync.store.AnalysisTextFiles
import com.nuvio.app.features.analyzedsync.store.EpisodeJsonSyncStore
import com.nuvio.app.features.analyzedsync.store.MemoryAnalysisSyncPort
import com.nuvio.app.features.analyzedsync.transport.CreateSpaceBody
import com.nuvio.app.features.analyzedsync.transport.TransportResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncScenarioTest {
    @Test
    fun twoDevicesMergeOnAFakeServerAndTheMarkWins() = runBlocking {
        val crypto = Json.parseToJsonElement(vectorText("crypto.json")).jsonObject
        val encKey = decodeHex(crypto.string("encKeyHex")) ?: error("enc")
        val auth = decodeHex(crypto.string("authTokenHex")) ?: error("auth")
        val spaceId = crypto.string("spaceId")
        val server = FakeSyncServer(enrollSecret = "enroll-secret-test")
        val created = server.createSpace(
            "https://sync.example",
            "enroll-secret-test",
            bearer(auth),
            CreateSpaceBody(spaceId, crypto.string("saltBase64"), crypto.string("keyCheckBase64")),
        )
        assertTrue(created is TransportResponse.Ok)
        val now = 1_700_000_200_000L
        val mark = MarkRecord(
            id = "mark/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa01",
            hlc = Hlc(1_700_000_000_000L, 0, "device-a"),
            showId = "tmdb:100",
            season = 1,
            episode = 8,
            segmentType = "intro",
            presence = "range",
            segmentIndex = 0,
            startMs = 42_000,
            endMs = 92_000,
            durationMs = 3_600_000,
        )
        val detection = DetectionRecord(
            id = detectionId("tmdb:100", "s1e8", "intro", 0, 3_600_000, 1),
            hlc = Hlc(1_700_000_100_000L, 0, "device-b"),
            showId = "tmdb:100",
            season = 1,
            episode = 8,
            segmentType = "intro",
            segmentIndex = 0,
            outcome = "found",
            startMs = 10_000,
            endMs = 20_000,
            durationMs = 3_600_000,
            algorithm = "nuvio-analysis-1",
            algorithmVersion = 1,
            releaseId = null,
        )
        val phoneA = MemoryAnalysisSyncPort().also { it.replaceRecords(listOf(mark), now) }
        val phoneB = MemoryAnalysisSyncPort().also { it.replaceRecords(listOf(detection), now) }
        val engineA = AnalyzedSyncEngine(server, phoneA, counterRandom(1))
        val engineB = AnalyzedSyncEngine(server, phoneB, counterRandom(400))
        val configA = SyncConfig("https://sync.example", spaceId, "device-a", encKey, auth)
        val configB = SyncConfig("https://sync.example", spaceId, "device-b", encKey, auth)

        val first = engineA.sync(configA, SyncSessionState(), now)
        assertNull(first.error)
        assertEquals(0L, first.state.pullCursor)
        assertEquals(listOf(0L), server.pullCursors.toList())

        val second = engineB.sync(configB, SyncSessionState(), now)
        assertNull(second.error)
        assertEquals(1L, second.state.pullCursor)
        assertEquals(listOf(0L, 0L), server.pullCursors.toList())

        val third = engineA.sync(configA, first.state, now)
        assertNull(third.error)
        assertEquals(2L, third.state.pullCursor)
        assertEquals(0L, server.pullCursors.last())
        val merged = mergeRecords(phoneA.readLocal().map { it.record.toWire() }, now)
        val intro = merged.shows.single().episodes.single().effective.single { it.segmentType == "intro" }
        assertEquals("mark", intro.source)
        assertEquals(42_000L, intro.startMs)
        assertEquals("mark", phoneB.readLocal().let { mergeRecords(it.map { row -> row.record.toWire() }, now) }
            .shows.single().episodes.single().effective.single { it.segmentType == "intro" }.source)
    }

    @Test
    fun aPhoneOfflineFor90DaysDoesNotUploadTheStaleMark() = runBlocking {
        val crypto = Json.parseToJsonElement(vectorText("crypto.json")).jsonObject
        val vector = Json.parseToJsonElement(vectorText("offline-resync.json")).jsonObject
        val encKey = decodeHex(crypto.string("encKeyHex")) ?: error("enc")
        val auth = decodeHex(crypto.string("authTokenHex")) ?: error("auth")
        val spaceId = crypto.string("spaceId")
        val server = FakeSyncServer(enrollSecret = "enroll-secret-test")
        assertTrue(
            server.createSpace(
                "https://sync.example",
                "enroll-secret-test",
                bearer(auth),
                CreateSpaceBody(spaceId, crypto.string("saltBase64"), crypto.string("keyCheckBase64")),
            ) is TransportResponse.Ok,
        )
        val now = vector.long("nowMs")
        val local = vector.array("staleLocalRecords").map { parseWire(it) }
        val phone = MemoryAnalysisSyncPort().also { it.replaceRecords(local, now) }
        val run = AnalyzedSyncEngine(server, phone, counterRandom(1)).sync(
            SyncConfig("https://sync.example", spaceId, "device-a", encKey, auth),
            SyncSessionState(pullCursor = 9, lastSuccessfulSyncMs = vector.long("lastSuccessfulSyncMs")),
            now,
        )
        assertNull(run.error)
        assertEquals(0L, server.pullCursors.first())
        val page = (server.pull("https://sync.example", spaceId, bearer(auth), 0, 100) as TransportResponse.Ok).value
        assertEquals(1, page.blobs.size)
        val blob = page.blobs.single()
        val plain = decryptBlob(
            encKey,
            decodeBase64(blob.nonce) ?: error("nonce"),
            associatedData(RoutingAad(1, spaceId, blob.id, "device-a", blob.kind)),
            decodeBase64(blob.ciphertext) ?: error("cipher"),
        ).decodeToString()
        assertTrue(plain.contains("aaaaaaaaaa12"))
        assertFalse(plain.contains("aaaaaaaaaa11"))
        val kept = phone.readLocal().map { it.record.id }
        assertEquals(listOf("mark/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa12"), kept)
    }

    @Test
    fun episodeJsonKeepsFingerprintsAndDoesNotTurnNotFoundIntoNone() {
        val files = MapFiles()
        files.write(
            "tmdb_100__s1e8",
            """{"key":"tmdb_100__s1e8","analyzedAtMs":1,"durationMs":3600000,"headFingerprint":"AAAA","version":1}""",
        )
        val store = EpisodeJsonSyncStore(files)
        val read = store.readLocal()
        assertTrue(read.none { (it.record as? MarkRecord)?.presence == "none" })
        val detections = read.map { it.record }.filterIsInstance<DetectionRecord>()
        assertTrue(detections.isNotEmpty())
        assertTrue(detections.all { it.outcome == "not_found" })
        assertFalse(read.joinToString { canonicalJson(it.record.toWire()) }.contains("AAAA"))
        assertFalse(read.joinToString { canonicalJson(it.record.toWire()) }.contains("headFingerprint"))

        store.replaceRecords(read.map { it.record }, 1_700_000_000_000L)
        val afterDetection = decodeEpisode(files.read("tmdb_100__s1e8") ?: error("missing"))
        assertEquals("AAAA", afterDetection.headFingerprint)
        assertTrue(afterDetection.userNoneTypes.isEmpty())
        assertFalse(afterDetection.syncRecordsJson.contains("\"presence\":\"none\""))
        assertTrue(afterDetection.syncRecordsJson.contains("\"outcome\":\"not_found\""))

        val none = MarkRecord(
            id = noneId("tmdb:100", "s1e8", "intro"),
            hlc = Hlc(1_700_000_000_000L, 0, "device-a"),
            showId = "tmdb:100",
            season = 1,
            episode = 8,
            segmentType = "intro",
            presence = "none",
            segmentIndex = null,
            startMs = null,
            endMs = null,
            durationMs = 0,
        )
        store.replaceRecords(listOf(none), 1_700_000_000_000L)
        val afterNone = decodeEpisode(files.read("tmdb_100__s1e8") ?: error("missing"))
        assertEquals(setOf("intro"), afterNone.userNoneTypes)
        assertEquals("AAAA", afterNone.headFingerprint)
    }
}

private class MapFiles : AnalysisTextFiles {
    private val files = linkedMapOf<String, String>()
    override fun read(name: String): String? = files[name]
    override fun write(name: String, text: String) { files[name] = text }
    override fun list(prefix: String): List<String> = files.keys.filter { it.startsWith(prefix) }
}

private fun counterRandom(seed: Int): (Int) -> ByteArray {
    var next = seed
    return { size ->
        ByteArray(size) {
            next += 1
            (next and 0xff).toByte()
        }
    }
}

private fun vectorText(name: String): String {
    val dir = listOf(
        java.io.File("docs/sync/test-vectors/v1"),
        java.io.File("../docs/sync/test-vectors/v1"),
        java.io.File("../../docs/sync/test-vectors/v1"),
    ).first { it.isDirectory }
    return dir.resolve(name).readText()
}

private fun parseWire(element: JsonElement) =
    com.nuvio.app.features.analyzedsync.record.parseRecord(element.toSyncJson() ?: error("record"))!!.record

private fun decodeEpisode(text: String): MediaAnalysisResult =
    com.nuvio.app.core.serialization.LenientDefaultsJson.decodeFromString(MediaAnalysisResult.serializer(), text)

private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull ?: error(key)
private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: error(key)
private fun JsonObject.array(key: String) = this[key]?.jsonArray ?: error(key)
