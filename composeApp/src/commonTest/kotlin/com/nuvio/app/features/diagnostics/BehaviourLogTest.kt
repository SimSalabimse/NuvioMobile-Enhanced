package com.nuvio.app.features.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BehaviourLogTest {
    @Test
    fun tenThousandAppendsOnThisHost() {
        val line = "{\"kind\":\"playback\",\"state\":\"start\"}"
        val samples = LongArray(5)
        repeat(samples.size) { run ->
            val ring = BehaviourRing()
            val start = System.nanoTime()
            repeat(10_000) { ring.append(line) }
            samples[run] = System.nanoTime() - start
            assertTrue(ring.bytes() <= BEHAVIOUR_LOG_MAX_BYTES)
        }
        val ordered = samples.sorted()
        println("behaviour-log host appends=10000 runs=5 elapsedNs=${samples.joinToString(",")} medianNs=${ordered[2]}")
    }

    @Test
    fun oneOversizedLineDoesNotBreakTheCap() {
        val ring = BehaviourRing(maxBytes = 64)
        ring.append("x".repeat(200))
        assertEquals(0, ring.bytes())
        ring.append("ok")
        ring.append("y".repeat(200))
        assertTrue(ring.bytes() <= 64)
        assertTrue(ring.active.contains("ok"))
    }

    @Test
    fun aMetricPayloadKeepsItsBodyAndRedactsPathsAndUrls() {
        val raw = """{"ok":1,"file":"/var/mobile/Containers/Data/show.mkv","page":"https://cdn.example/a","who":"user@host"}"""
        val line = behaviourRawLine(raw)
        assertNotNull(line)
        assertTrue(line.contains("\"ok\":1"))
        assertTrue(line.contains("<path>"))
        assertTrue(line.contains("<url>"))
        assertTrue(line.contains("<addr>"))
        assertFalse(line.contains("/var/mobile"))
        assertFalse(line.contains("https://"))
        assertFalse(line.contains("user@host"))
        assertEquals("{\"kind\":\"metric\"}", behaviourRawLine("{\"kind\":\"metric\"}\n"))
        assertEquals(null, behaviourRawLine("x".repeat(BEHAVIOUR_METRIC_MAX_BYTES + 1)))
    }

    @Test
    fun reloadAfterRelaunchKeepsTheMarker() {
        val disk = MemoryBehaviourDisk()
        val first = BehaviourLogSession(disk, nowMs = { 1_700_000_000_000L }, launchId = "launch-a")
        first.mark(null)
        val second = BehaviourLogSession(disk, nowMs = { 1_700_000_005_000L }, launchId = "launch-b")
        assertTrue(second.text().contains("\"kind\":\"marker\""))
        assertTrue(second.text().contains("launch-a"))
        second.record("foreground")
        assertTrue(second.text().contains("launch-b"))
        assertTrue(second.text().contains("\"kind\":\"marker\""))
    }

    @Test
    fun everyLineIsJsonWithTimeAndSequence() {
        val session = BehaviourLogSession(MemoryBehaviourDisk(), nowMs = { 42L }, launchId = "launch-a")
        session.record("launch")
        session.record("playback", mapOf("state" to "start"))
        val lines = session.text().lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(2, lines.size)
        val parsed = lines.map { Json.parseToJsonElement(it).jsonObject }
        parsed.forEach { obj ->
            assertEquals(42L, obj.getValue("t").jsonPrimitive.long)
            assertEquals("launch-a", obj.getValue("launch").jsonPrimitive.content)
            assertTrue(obj.getValue("seq").jsonPrimitive.long > 0L)
            assertFalse(obj.getValue("kind").jsonPrimitive.content.isEmpty())
        }
        assertEquals(listOf(1L, 2L), parsed.map { it.getValue("seq").jsonPrimitive.long })
        assertEquals("launch", parsed[0].getValue("kind").jsonPrimitive.content)
        assertFalse(lines[0].contains(",}"))
    }

    @Test
    fun aStaleSnapshotUploadsThenTheLiveRingIsNext() {
        val disk = MemoryBehaviourDisk()
        val session = BehaviourLogSession(disk, nowMs = { 1L }, launchId = "launch-a")
        session.record("stale")
        val rotated = session.beginUpload()
        assertTrue(rotated.contains("\"kind\":\"stale\""))
        assertFalse(session.lastUploadWasPending())
        session.record("live")
        val pending = session.beginUpload()
        assertTrue(pending.contains("\"kind\":\"stale\""))
        assertFalse(pending.contains("\"kind\":\"live\""))
        assertTrue(session.lastUploadWasPending())
        assertTrue(session.text().contains("\"kind\":\"live\""))
        session.completeUpload(200)
        val follow = session.beginUpload()
        assertTrue(follow.contains("\"kind\":\"live\""))
        assertFalse(session.lastUploadWasPending())
    }

    @Test
    fun uploadSnapshotDoesNotDeleteNewerEvents() {
        val disk = MemoryBehaviourDisk()
        val session = BehaviourLogSession(disk, nowMs = { 1L }, launchId = "launch-a")
        session.record("old")
        val batch = session.beginUpload()
        assertTrue(batch.contains("\"kind\":\"old\""))
        session.record("newer")
        session.completeUpload(500)
        assertTrue(disk.snapshot.contains("\"kind\":\"old\""))
        assertTrue(session.text().contains("\"kind\":\"newer\""))
        session.completeUpload(200)
        assertEquals("", disk.snapshot)
        assertTrue(session.text().contains("\"kind\":\"newer\""))
        assertFalse(session.text().contains("\"kind\":\"old\""))
    }

    @Test
    fun theMarkerIsFlushedToDiskImmediately() {
        val disk = MemoryBehaviourDisk()
        val session = BehaviourLogSession(disk, nowMs = { 1L }, launchId = "launch-a")
        session.mark(null)
        val stored = disk.previous + disk.active
        assertTrue(stored.contains("\"kind\":\"marker\""))
        assertTrue(stored.contains("\"t\":"))
    }

    @Test
    fun theMarkerKeepsItsNameAndSnapsWhenMotionIsReduced() {
        val still = feltOffMarker(marked = true, reduceMotion = true)
        assertEquals("Something felt off", still.label)
        assertEquals("Marked", still.value)
        assertEquals("Saves a marker on this phone", still.hint)
        assertEquals(0, still.motionMillis)
        assertEquals(160, feltOffMarker(marked = false, reduceMotion = false).motionMillis)
    }

    @Test
    fun theRingStaysInsideFourMegabytesAndDropsTheOlderFile() {
        val ring = BehaviourRing(maxBytes = 64)
        repeat(20) { index -> ring.append("event-$index-xxxxxxxx") }
        assertTrue(ring.bytes() <= 64)
        assertFalse(ring.active.contains("event-0-"))
        assertTrue(ring.previous.isEmpty() || ring.previous.encodeToByteArray().size + ring.active.encodeToByteArray().size <= 64)
    }

    @Test
    fun contentIdsAreTwelveHexAndDoNotContainThePlainId() {
        val plain = "episode-plain-id-xyz"
        val hashed = behaviourContentId("install-salt", plain)
        assertEquals(12, hashed.length)
        assertTrue(hashed.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(hashed.contains("episode"))
        assertEquals(hashed, behaviourContentId("install-salt", plain))
        assertTrue(behaviourContentId("other-salt", plain) != hashed)
    }

    @Test
    fun aNoteDropsUrlsPathsAndKeepsOneLine() {
        assertEquals("buffering", behaviourNote("buffering\nsecond"))
        assertEquals("", behaviourNote("https://example.test/a"))
        assertEquals("", behaviourNote("a/b"))
        assertEquals("", behaviourNote("a@b"))
    }

    @Test
    fun uploadDeletesOnlyAfterSuccessAndRetriesOtherwise() {
        assertTrue(telemetryDeleteLocalBatch(200))
        assertTrue(telemetryDeleteLocalBatch(204))
        assertFalse(telemetryDeleteLocalBatch(401))
        assertFalse(telemetryDeleteLocalBatch(500))
        assertFalse(telemetryDeleteLocalBatch(0))
    }

    @Test
    fun pruneDropsBatchesOlderThanThirtyDays() {
        val now = 40L * 24L * 60L * 60L * 1000L
        assertEquals(now - TELEMETRY_PRUNE_AFTER_MS, telemetryPruneBefore(now))
        assertTrue(10L < telemetryPruneBefore(now))
    }

    @Test
    fun uploadWaitsSixHoursAndSkipsWhenItShould() {
        val now = 10_000_000L
        assertTrue(
            telemetryMayUpload(
                enrolled = true,
                shareEnabled = true,
                onWifi = true,
                expensive = false,
                constrained = false,
                lowPowerMode = false,
                lastUploadMs = 0L,
                nowMs = now,
            ),
        )
        assertFalse(
            telemetryMayUpload(
                enrolled = false,
                shareEnabled = true,
                onWifi = true,
                expensive = false,
                constrained = false,
                lowPowerMode = false,
                lastUploadMs = 0L,
                nowMs = now,
            ),
        )
        assertFalse(
            telemetryMayUpload(
                enrolled = true,
                shareEnabled = true,
                onWifi = false,
                expensive = false,
                constrained = false,
                lowPowerMode = false,
                lastUploadMs = 0L,
                nowMs = now,
            ),
        )
        assertFalse(
            telemetryMayUpload(
                enrolled = true,
                shareEnabled = true,
                onWifi = true,
                expensive = true,
                constrained = false,
                lowPowerMode = false,
                lastUploadMs = 0L,
                nowMs = now,
            ),
        )
        assertFalse(
            telemetryMayUpload(
                enrolled = true,
                shareEnabled = true,
                onWifi = true,
                expensive = false,
                constrained = true,
                lowPowerMode = false,
                lastUploadMs = 0L,
                nowMs = now,
            ),
        )
        assertFalse(
            telemetryMayUpload(
                enrolled = true,
                shareEnabled = true,
                onWifi = true,
                expensive = false,
                constrained = false,
                lowPowerMode = true,
                lastUploadMs = 0L,
                nowMs = now,
            ),
        )
        assertFalse(
            telemetryMayUpload(
                enrolled = true,
                shareEnabled = true,
                onWifi = true,
                expensive = false,
                constrained = false,
                lowPowerMode = false,
                lastUploadMs = now - 1_000L,
                nowMs = now,
            ),
        )
        assertTrue(
            telemetryMayUpload(
                enrolled = true,
                shareEnabled = true,
                onWifi = true,
                expensive = false,
                constrained = false,
                lowPowerMode = false,
                lastUploadMs = now - BEHAVIOUR_UPLOAD_INTERVAL_MS,
                nowMs = now,
            ),
        )
    }
}
