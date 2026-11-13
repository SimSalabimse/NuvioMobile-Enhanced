package com.nuvio.app.features.analyzedsync

import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.analysisWorkPending
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
import com.nuvio.app.features.analyzedsync.record.AliasRecord
import com.nuvio.app.features.analyzedsync.record.DetectionRecord
import com.nuvio.app.features.analyzedsync.record.MarkRecord
import com.nuvio.app.features.analyzedsync.record.TombstoneRecord
import com.nuvio.app.features.analyzedsync.record.canonicalImdbShowId
import com.nuvio.app.features.analyzedsync.record.detectionId
import com.nuvio.app.features.analyzedsync.record.noneId
import com.nuvio.app.features.analyzedsync.store.accountScope
import com.nuvio.app.features.analyzedsync.store.diskShowToSyncId
import com.nuvio.app.features.analyzedsync.store.AnalysisTextFiles
import com.nuvio.app.features.analyzedsync.session.normalizeSyncUrl
import com.nuvio.app.features.analyzedsync.store.EpisodeJsonSyncStore
import com.nuvio.app.features.analyzedsync.store.LocalRecord
import com.nuvio.app.features.analyzedsync.store.MemoryAnalysisSyncPort
import com.nuvio.app.features.analyzedsync.store.backupJson
import com.nuvio.app.features.analyzedsync.store.backupJsonIsSafe
import com.nuvio.app.features.analyzedsync.transport.BlobEnvelope
import com.nuvio.app.features.analyzedsync.transport.CreateSpaceBody
import com.nuvio.app.features.analyzedsync.transport.PullPage
import com.nuvio.app.features.analyzedsync.transport.PulledBlob
import com.nuvio.app.features.analyzedsync.transport.PushResult
import com.nuvio.app.features.analyzedsync.transport.SyncTransport
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

    @Test
    fun aStoredRangeBecomesATombstoneWhenThePersonRemovesItAndNoneIsNotInferred() {
        val files = MapFiles()
        val mark = rangeMark()
        files.write("tmdb_100__s1e8", episodeJson(sync = canonicalJson(com.nuvio.app.features.analyzedsync.json.JsonValue.Arr(listOf(mark.toWire())))))
        val read = EpisodeJsonSyncStore(files).readLocal()
        assertTrue(read.any { it.needsStamp && (it.record as? TombstoneRecord)?.id == mark.id })
        assertTrue(read.none { (it.record as? MarkRecord)?.presence == "none" })
        assertTrue(read.none { (it.record as? MarkRecord)?.presence == "range" && !it.record.deleted })
    }

    @Test
    fun noneHereAfterSyncReplacesTheRangeAndNotFoundStaysADetection() {
        val files = MapFiles()
        val mark = rangeMark()
        files.write(
            "tmdb_100__s1e8",
            episodeJson(
                userNone = true,
                sync = canonicalJson(com.nuvio.app.features.analyzedsync.json.JsonValue.Arr(listOf(mark.toWire()))),
            ),
        )
        val read = EpisodeJsonSyncStore(files).readLocal()
        assertTrue(read.any { it.needsStamp && (it.record as? MarkRecord)?.presence == "none" })
        assertTrue(read.any { (it.record as? MarkRecord)?.id == mark.id && !it.record.deleted })
        assertTrue(read.none { it.record is TombstoneRecord })
        assertTrue(read.none { (it.record as? DetectionRecord)?.outcome == "not_found" })
    }

    @Test
    fun aTombstoneDoesNotComeBackOutOfTheEpisodeFile() {
        val files = MapFiles()
        val found = foundDetection()
        files.write(
            "tmdb_100__s1e8",
            episodeJson(
                intro = "10000",
                introEnd = "20000",
                sync = canonicalJson(com.nuvio.app.features.analyzedsync.json.JsonValue.Arr(listOf(found.toWire()))),
            ),
        )
        val store = EpisodeJsonSyncStore(files)
        store.replaceRecords(listOf(TombstoneRecord("detection", found.id, Hlc(1_700_000_000_000L, 1, "device-a"))), 1_700_000_000_000L)
        val after = decodeEpisode(files.read("tmdb_100__s1e8") ?: error("missing"))
        assertEquals("AAAA", after.headFingerprint)
        assertNull(after.introStartMs)
        assertEquals("[]", after.syncRecordsJson)
        val again = store.readLocal()
        assertTrue(again.none { it.record is DetectionRecord && !it.record.deleted })
        assertTrue(again.any { (it.record as? TombstoneRecord)?.id == found.id })
    }

    @Test
    fun anEmptySyncLeavesTheAliasFileAndASyncedAliasKeepsTheOwner() {
        val files = MapFiles()
        files.write("show-aliases", """{"tmdb#100":"tmdb_100"}""")
        files.write("tmdb_100__s1e8", episodeJson())
        val store = EpisodeJsonSyncStore(files)
        store.replaceRecords(emptyList(), 1_700_000_000_000L)
        assertEquals("""{"tmdb#100":"tmdb_100"}""", files.read("show-aliases"))
        assertEquals("AAAA", decodeEpisode(files.read("tmdb_100__s1e8") ?: error("missing")).headFingerprint)

        store.replaceRecords(
            listOf(AliasRecord("alias/imdb:tt0000100", Hlc(1_700_000_000_000L, 0, "device-a"), "imdb:tt0000100", "tmdb:100")),
            1_700_000_000_000L,
        )
        val alias = files.read("show-aliases") ?: error("alias")
        assertTrue(alias.contains("\"tmdb#100\":\"tmdb_100\""))
        assertTrue(alias.contains("tt0000100"))
        assertEquals("AAAA", decodeEpisode(files.read("tmdb_100__s1e8") ?: error("missing")).headFingerprint)
    }

    @Test
    fun aMarkInTheEpisodeFileBeatsANewerDetection() {
        val files = MapFiles()
        val store = EpisodeJsonSyncStore(files)
        files.write("tmdb_100__s1e8", episodeJson())
        store.replaceRecords(listOf(rangeMark(), foundDetection(start = 10_000, end = 20_000, wall = 1_700_000_100_000L)), 1_700_000_200_000L)
        val after = decodeEpisode(files.read("tmdb_100__s1e8") ?: error("missing"))
        assertEquals(42_000L, after.userSegments["intro"]?.startMs)
        assertEquals("AAAA", after.headFingerprint)
        assertTrue(after.introStartMs != 10_000L)
        assertTrue(after.userNoneTypes.isEmpty())
    }

    @Test
    fun pullFailureDoesNotAdvanceTheCursorOrTouchLocalRecords() = runBlocking {
        val port = MemoryAnalysisSyncPort()
        val transport = object : SyncTransport by unusedTransport() {
            var pulls = 0
            override suspend fun pull(baseUrl: String, spaceId: String, bearerToken: String, cursor: Long, limit: Int): TransportResponse<PullPage> {
                pulls += 1
                if (pulls > 1) return TransportResponse.Failed(500, "internal")
                return TransportResponse.Ok(
                    PullPage(
                        blobs = listOf(PulledBlob(5, "22222222-2222-4222-8222-222222222222", 1, "mark", "device-b", "AAAA", "AAAA", 1)),
                        cursor = 5,
                        hasMore = true,
                    ),
                )
            }
        }
        val run = AnalyzedSyncEngine(transport, port, counterRandom(1)).sync(sampleConfig(), SyncSessionState(pullCursor = 4), 1_700_000_000_000L)
        assertEquals("Sync failed (500).", run.error)
        assertEquals(4L, run.state.pullCursor)
        assertTrue(port.readLocal().isEmpty())
    }

    @Test
    fun rateLimitWaitsForRetryAfterAndThenPulls() = runBlocking {
        val waits = mutableListOf<Long>()
        val transport = object : SyncTransport by unusedTransport() {
            var pulls = 0
            override suspend fun pull(baseUrl: String, spaceId: String, bearerToken: String, cursor: Long, limit: Int): TransportResponse<PullPage> {
                pulls += 1
                if (pulls == 1) return TransportResponse.Failed(429, "rate_limited", retryAfterSeconds = 2)
                return TransportResponse.Ok(PullPage(emptyList(), cursor, false))
            }
        }
        val run = AnalyzedSyncEngine(transport, MemoryAnalysisSyncPort(), counterRandom(1), sleep = { waits += it })
            .sync(sampleConfig(), SyncSessionState(), 1_700_000_000_000L)
        assertNull(run.error)
        assertEquals(listOf(2_000L), waits)
        assertEquals(2, transport.pulls)
    }

    @Test
    fun aNonceConflictIsRetriedWithANewNonce() = runBlocking {
        val crypto = Json.parseToJsonElement(vectorText("crypto.json")).jsonObject
        val encKey = decodeHex(crypto.string("encKeyHex")) ?: error("enc")
        val auth = decodeHex(crypto.string("authTokenHex")) ?: error("auth")
        val server = FakeSyncServer(enrollSecret = "enroll-secret-test")
        assertTrue(
            server.createSpace(
                "https://sync.example",
                "enroll-secret-test",
                bearer(auth),
                CreateSpaceBody(crypto.string("spaceId"), crypto.string("saltBase64"), crypto.string("keyCheckBase64")),
            ) is TransportResponse.Ok,
        )
        val nonces = mutableListOf<String>()
        val transport = object : SyncTransport by server {
            var pushes = 0
            override suspend fun push(baseUrl: String, spaceId: String, bearerToken: String, deviceId: String, blobs: List<BlobEnvelope>): TransportResponse<List<PushResult>> {
                pushes += 1
                nonces += blobs.map { it.nonce }
                if (pushes == 1) return TransportResponse.Failed(409, "conflict")
                return server.push(baseUrl, spaceId, bearerToken, deviceId, blobs)
            }
        }
        val port = MemoryAnalysisSyncPort().also { it.replaceRecords(listOf(rangeMark()), 1_700_000_200_000L) }
        val run = AnalyzedSyncEngine(transport, port, counterRandom(1)).sync(
            SyncConfig("https://sync.example", crypto.string("spaceId"), "device-a", encKey, auth),
            SyncSessionState(),
            1_700_000_200_000L,
        )
        assertNull(run.error)
        assertEquals(2, transport.pushes)
        assertEquals(2, nonces.distinct().size)
        val page = (server.pull("https://sync.example", crypto.string("spaceId"), bearer(auth), 0, 100) as TransportResponse.Ok).value
        assertEquals(1, page.blobs.size)
    }

    @Test
    fun backupJsonHasNoKeysAndHttpIsNotASyncUrl() {
        val json = backupJson(listOf(LocalRecord(rangeMark(), needsStamp = false)))
        assertTrue(backupJsonIsSafe(json))
        assertFalse(json.contains("passphrase"))
        assertFalse(json.contains("encKey"))
        assertFalse(json.contains("authToken"))
        assertFalse(backupJsonIsSafe("""{"passphrase":"secret"}"""))
        assertNull(normalizeSyncUrl("http://sync.example"))
        assertEquals("https://sync.example", normalizeSyncUrl("https://sync.example/"))
    }

    @Test
    fun imdbIdsArePaddedAndAliasesStayAvailableForDesktop() {
        val vector = Json.parseToJsonElement(vectorText("id-normalization.json")).jsonObject
        for (row in vector.array("imdb")) {
            val item = row.jsonObject
            val expected = item["showId"]?.jsonPrimitive?.contentOrNull
            assertEquals(expected, canonicalImdbShowId(item.string("input")))
            if (expected != null) assertEquals(expected, diskShowToSyncId(item.string("input")))
        }
        assertEquals("imdb:tt0000100", diskShowToSyncId("tt100"))
        assertEquals("imdb:tt0000100", diskShowToSyncId("imdb_tt100"))
        assertEquals("imdb:tt12345678", diskShowToSyncId("tt12345678"))
        assertNull(diskShowToSyncId("tt0000000"))
        assertEquals("tmdb:100", diskShowToSyncId("tmdb_100"))
        val detection = parseWire(vector["detection"] ?: error("detection")) as DetectionRecord
        assertEquals("det/imdb:tt0000100/s1e8/intro/0/3600000/a1", detection.id)
        assertEquals("imdb:tt0000100", detection.showId)
        assertEquals("none/imdb:tt0000100/s1e8/intro", parseWire(vector["none"] ?: error("none")).id)
        val aliasWire = parseWire(vector["alias"] ?: error("alias")) as AliasRecord
        assertEquals("alias/imdb:tt0000100", aliasWire.id)
        assertEquals("tmdb:100", aliasWire.to)
        val scope = vector["accountScope"]?.jsonObject ?: error("scope")
        assertEquals(scope.string("accountScope"), accountScope(scope.string("key")))
        assertEquals(accountScope(scope.string("trimmed")), accountScope(" \t${scope.string("trimmed")}\n"))
        assertEquals(accountScope("key"), accountScope("\u00A0key\u00A0"))
        assertNull(accountScope(" \u00A0\n"))

        val files = MapFiles()
        files.write("show-aliases", """{"tmdb#100":"imdb_tt100"}""")
        files.write("tt100__s1e8", episodeJson().replace("tmdb_100__s1e8", "tt100__s1e8"))
        val store = EpisodeJsonSyncStore(files)
        val aliases = store.readLocal().map { it.record }.filterIsInstance<AliasRecord>()
        assertEquals(listOf("imdb:tt0000100"), aliases.map { it.from })
        assertEquals("tmdb:100", aliases.single().to)
        assertEquals("alias/imdb:tt0000100", aliases.single().id)
        val detections = store.readLocal().map { it.record }.filterIsInstance<DetectionRecord>()
        assertTrue(detections.isNotEmpty())
        assertTrue(detections.all { it.showId == "imdb:tt0000100" && it.id.startsWith("det/imdb:tt0000100/") })
        store.replaceRecords(listOf(aliases.single()), 1_700_000_000_000L)
        assertTrue(files.list("tt0000100__").isEmpty())
        files.write("show-aliases", """{"tmdb#100":"tmdb_100","imdb_tt100":"tmdb#100","tt200":"tmdb#100"}""")
        val again = store.readLocal().map { it.record }.filterIsInstance<AliasRecord>()
        assertEquals(setOf("imdb:tt0000100", "imdb:tt0000200"), again.map { it.from }.toSet())
        assertTrue(again.single { it.from == "imdb:tt0000200" }.to == "tmdb:100")

        files.write(
            "aliases-sync",
            """[{"kind":"alias","id":"alias/imdb:tt0000100","deleted":true,"hlc":{"wallMs":1,"counter":0,"deviceId":"device-a"}}]""",
        )
        val afterDelete = store.readLocal()
        assertTrue(afterDelete.any { (it.record as? TombstoneRecord)?.id == "alias/imdb:tt0000100" })
        val afterTombstone = afterDelete.map { it.record }.filterIsInstance<AliasRecord>()
        assertTrue(afterTombstone.none { it.from == "imdb:tt0000100" })
        assertTrue(afterTombstone.any { it.from == "imdb:tt0000200" })
    }

    @Test
    fun aCoveredEpisodeIsNotAnalysedAgain() {
        val projected = MediaAnalysisResult(key = "tmdb_100__movie", analyzedAtMs = 0, durationMs = 0, fileName = null)
        assertFalse(analysisWorkPending(projected, "movie.mkv", covered = true, needsDerivedWork = true))
        val local = projected.copy(fileName = "movie.mkv", analyzedAtMs = 5, derivedVersion = 2)
        assertFalse(analysisWorkPending(local, "movie.mkv", covered = true, needsDerivedWork = false))
        assertTrue(analysisWorkPending(local, "movie.mkv", covered = true, needsDerivedWork = true))
        assertTrue(analysisWorkPending(null, "movie.mkv", covered = false, needsDerivedWork = false))
    }
}

private fun episodeJson(
    intro: String? = null,
    introEnd: String? = null,
    userNone: Boolean = false,
    sync: String = "",
): String {
    val result = MediaAnalysisResult(
        key = "tmdb_100__s1e8",
        analyzedAtMs = 1,
        durationMs = 3_600_000,
        headFingerprint = "AAAA",
        introStartMs = intro?.toLong(),
        introEndMs = introEnd?.toLong(),
        userNoneTypes = if (userNone) setOf("intro") else emptySet(),
        syncRecordsJson = sync,
    )
    return com.nuvio.app.core.serialization.LenientDefaultsJson.encodeToString(MediaAnalysisResult.serializer(), result)
}

private fun rangeMark() = MarkRecord(
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

private fun foundDetection(start: Long = 10_000, end: Long = 20_000, wall: Long = 1_700_000_000_000L) = DetectionRecord(
    id = detectionId("tmdb:100", "s1e8", "intro", 0, 3_600_000, 1),
    hlc = Hlc(wall, 0, "device-b"),
    showId = "tmdb:100",
    season = 1,
    episode = 8,
    segmentType = "intro",
    segmentIndex = 0,
    outcome = "found",
    startMs = start,
    endMs = end,
    durationMs = 3_600_000,
    algorithm = "nuvio-analysis-1",
    algorithmVersion = 1,
    releaseId = null,
)

private fun sampleConfig() = SyncConfig(
    baseUrl = "https://sync.example",
    spaceId = "11111111-1111-4111-8111-111111111111",
    deviceId = "device-a",
    encKey = ByteArray(32),
    authToken = ByteArray(32),
)

private fun unusedTransport(): SyncTransport = object : SyncTransport {
    override suspend fun createSpace(baseUrl: String, enrollSecret: String, bearerToken: String, body: CreateSpaceBody) =
        TransportResponse.Failed(500, "internal")
    override suspend fun join(baseUrl: String, spaceId: String, bearerToken: String) = TransportResponse.Failed(500, "internal")
    override suspend fun push(baseUrl: String, spaceId: String, bearerToken: String, deviceId: String, blobs: List<BlobEnvelope>) =
        TransportResponse.Failed(500, "internal")
    override suspend fun pull(baseUrl: String, spaceId: String, bearerToken: String, cursor: Long, limit: Int) =
        TransportResponse.Failed(500, "internal")
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
