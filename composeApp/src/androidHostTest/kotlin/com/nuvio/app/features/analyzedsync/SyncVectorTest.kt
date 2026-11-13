package com.nuvio.app.features.analyzedsync

import com.nuvio.app.features.analyzedsync.crypto.decodeBase64
import com.nuvio.app.features.analyzedsync.crypto.deriveKeys
import com.nuvio.app.features.analyzedsync.crypto.encodeBase64
import com.nuvio.app.features.analyzedsync.crypto.encodeBase64Url
import com.nuvio.app.features.analyzedsync.crypto.encodeHex
import com.nuvio.app.features.analyzedsync.crypto.encryptBlob
import com.nuvio.app.features.analyzedsync.crypto.associatedData
import com.nuvio.app.features.analyzedsync.crypto.keyCheckMatches
import com.nuvio.app.features.analyzedsync.crypto.sha256
import com.nuvio.app.features.analyzedsync.crypto.RoutingAad
import com.nuvio.app.features.analyzedsync.crypto.SyncCryptoPlatform
import com.nuvio.app.features.analyzedsync.json.toSyncJson
import com.nuvio.app.features.analyzedsync.merge.AnalyseQuery
import com.nuvio.app.features.analyzedsync.merge.mergeRecords
import com.nuvio.app.features.analyzedsync.merge.mergedCanonical
import com.nuvio.app.features.analyzedsync.merge.shouldAnalyse
import com.nuvio.app.features.analyzedsync.plan.SyncMode
import com.nuvio.app.features.analyzedsync.plan.recordsToUpload
import com.nuvio.app.features.analyzedsync.plan.syncMode
import com.nuvio.app.features.analyzedsync.record.parseRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncVectorTest {
    @Test
    fun cryptoVectorMatchesPublishedBytes() {
        val vector = vector("crypto.json")
        val salt = decodeBase64(vector.string("saltBase64")) ?: error("salt")
        val nonce = decodeBase64(vector.string("nonceBase64")) ?: error("nonce")
        val keys = deriveKeys(vector.string("passphrase"), salt, vector.long("iterations").toInt())
        assertEquals(vector.string("masterKeyHex"), encodeHex(keys.master))
        assertEquals(vector.string("encKeyHex"), encodeHex(keys.encKey))
        assertEquals(vector.string("authTokenHex"), encodeHex(keys.authToken))
        assertEquals(vector.string("authTokenBase64Url"), encodeBase64Url(keys.authToken))
        assertEquals(vector.string("keyCheckBase64"), encodeBase64(keys.keyCheck))
        assertEquals(vector.string("tokenHashHex"), encodeHex(sha256(keys.authToken)))
        val routing = RoutingAad(
            formatVersion = vector.long("formatVersion").toInt(),
            spaceId = vector.string("spaceId"),
            blobId = vector.string("blobId"),
            deviceId = vector.string("deviceId"),
            kind = vector.string("kind"),
        )
        val cipher = encryptBlob(keys.encKey, nonce, associatedData(routing), vector.string("plaintext").encodeToByteArray())
        assertEquals(vector.string("ciphertextBase64"), encodeBase64(cipher))
        assertEquals(vector.string("aad"), com.nuvio.app.features.analyzedsync.crypto.associatedDataText(routing))
        assertNotEquals(vector.string("masterKeyHex"), vector.string("encKeyHex"))
        assertNotEquals(vector.string("encKeyHex"), vector.string("authTokenHex"))
        val wrong = deriveKeys(vector.string("wrongPassphrase"), salt, vector.long("iterations").toInt())
        assertEquals(vector.string("wrongKeyCheckBase64"), encodeBase64(wrong.keyCheck))
        assertFalse(keyCheckMatches(wrong.keyCheck, keys.keyCheck))
        assertEquals(vector.string("joinCode"), com.nuvio.app.features.analyzedsync.crypto.joinCode(vector.string("spaceId"), salt, keys.keyCheck))
    }

    @Test
    fun passphraseNfcVectorDerivesOneMaster() {
        val vector = vector("passphrase-nfc.json")
        val salt = decodeBase64(vector.string("saltBase64")) ?: error("salt")
        val nfd = vector.string("nfdPassphrase")
        val nfc = vector.string("nfcPassphrase")
        assertTrue(nfd.contains("\u0301"))
        assertEquals(nfc, SyncCryptoPlatform.normalizeNfc(nfd))
        assertTrue(nfd.length > nfc.length)
        val iterations = vector.long("iterations").toInt()
        assertEquals(vector.string("masterKeyHex"), encodeHex(deriveKeys(nfd, salt, iterations).master))
        assertEquals(vector.string("masterKeyHex"), encodeHex(deriveKeys(nfc, salt, iterations).master))
    }

    @Test
    fun mergeVectorsMatchCanonicalJson() {
        for (name in listOf(
            "mark-beats-detection.json",
            "two-cuts.json",
            "clock-tie.json",
            "clock-json-tie.json",
            "clock-skew.json",
            "rejected.json",
            "submission-scope.json",
            "zero-duration-mark.json",
        )) {
            expectScenario(vector(name), name)
        }
        val tombstone = vector("tombstone.json")
        val tombstoneRecords = tombstone.array("records").map { it.toSyncJson() ?: error("record") }
        for (item in tombstone.array("cases")) {
            val caseVector = item.jsonObject
            assertEquals(caseVector.string("mergedCanonical"), mergedCanonical(tombstoneRecords, caseVector.long("nowMs")), "tombstone")
        }
        for (item in vector("alias-merge.json").array("scenarios")) expectScenario(item.jsonObject, "alias")
        for (item in vector("not-found-vs-none.json").array("scenarios")) expectScenario(item.jsonObject, "not-found")
    }

    @Test
    fun notFoundDoesNotBecomeNone() {
        val scenarios = vector("not-found-vs-none.json").array("scenarios").map { it.jsonObject }
        val alone = mergeRecords(scenarios[0].records(), scenarios[0].long("nowMs"))
        assertEquals(emptyList(), alone.shows[0].episodes[0].userNoneTypes)
        assertEquals(emptyList(), alone.shows[0].episodes[0].marks)
        assertEquals(emptyList(), alone.shows[0].episodes[0].effective)
        assertEquals("not_found", alone.shows[0].episodes[0].detections[0].outcome)
        val none = mergeRecords(scenarios[1].records(), scenarios[1].long("nowMs"))
        assertEquals(listOf("intro"), none.shows[0].episodes[0].userNoneTypes)
        assertEquals("mark", none.shows[0].episodes[0].effective[0].source)
        assertEquals("none", none.shows[0].episodes[0].effective[0].presence)
        assertEquals("not_found", none.shows[0].episodes[0].detections[0].outcome)
        val missing = mergeRecords(scenarios[2].records(), scenarios[2].long("nowMs"))
        assertTrue(missing.shows.isEmpty())
        assertFalse(mergedCanonical(scenarios[2].records(), scenarios[2].long("nowMs")).contains("\"presence\":\"none\""))
    }

    @Test
    fun offlineVectorDoesNotUploadTheStaleMark() {
        val vector = vector("offline-resync.json")
        val now = vector.long("nowMs")
        val last = vector.long("lastSuccessfulSyncMs")
        assertEquals("full-resync", vector.string("mode"))
        assertEquals(SyncMode.FullResync, syncMode(last, now))
        assertEquals(SyncMode.Incremental, syncMode(last, 2_000_000))
        assertEquals("incremental", vector.string("withinWindowMode"))
        val local = vector.array("staleLocalRecords").map { parseRecord(it.toSyncJson() ?: error("record"))!!.record }
        val upload = recordsToUpload(local, last, now)
        assertEquals(1, upload.size)
        assertEquals(vector.array("pushLocal")[0].jsonObject.string("id"), upload[0].id)
        val remote = vector.array("remoteRecords").map { it.toSyncJson() ?: error("remote") }
        val fresh = vector.array("pushLocal").map { it.toSyncJson() ?: error("push") }
        assertEquals(vector.string("adoptedCanonical"), mergedCanonical(remote + fresh, now))
        assertEquals(vector.string("resurrectedCanonical"), mergedCanonical(listOf(vector.array("staleLocalRecords")[0].toSyncJson() ?: error("stale")), now))
    }

    private fun expectScenario(vector: JsonObject, label: String) {
        val canonical = mergedCanonical(vector.records(), vector.long("nowMs"))
        assertEquals(vector.string("mergedCanonical"), canonical, label)
        val state = mergeRecords(vector.records(), vector.long("nowMs"))
        for (query in vector.arrayOrEmpty("queries")) {
            val objectQuery = query.jsonObject
            val analysed = shouldAnalyse(
                state,
                AnalyseQuery(
                    showId = objectQuery.string("showId"),
                    season = objectQuery.nullableLong("season"),
                    episode = objectQuery.nullableLong("episode"),
                    segmentType = objectQuery.string("segmentType"),
                    segmentIndex = objectQuery.long("segmentIndex").toInt(),
                    fileDurationMs = objectQuery.long("fileDurationMs"),
                    localAlgorithm = objectQuery.long("localAlgorithm").toInt(),
                ),
            )
            assertEquals(objectQuery["expectAnalyse"]?.jsonPrimitive?.booleanOrNull, analysed, label)
        }
    }
}

private fun vector(name: String): JsonObject {
    val dir = listOf(
        File("docs/sync/test-vectors/v1"),
        File("../docs/sync/test-vectors/v1"),
        File("../../docs/sync/test-vectors/v1"),
    ).first { it.isDirectory }
    return Json.parseToJsonElement(dir.resolve(name).readText()).jsonObject
}

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: error(key)
private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: error(key)
private fun JsonObject.nullableLong(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
private fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArray ?: error(key)
private fun JsonObject.arrayOrEmpty(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())
private fun JsonObject.records() = array("records").map { it.toSyncJson() ?: error("record") }
