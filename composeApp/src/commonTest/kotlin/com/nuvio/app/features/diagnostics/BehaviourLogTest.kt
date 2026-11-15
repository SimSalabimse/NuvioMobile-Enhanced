package com.nuvio.app.features.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
