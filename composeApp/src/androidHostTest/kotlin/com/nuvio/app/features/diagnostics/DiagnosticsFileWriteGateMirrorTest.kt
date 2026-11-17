package com.nuvio.app.features.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runnable copy of the decisions in
 * iosApp/iosApp/Diagnostics/DiagnosticsFileLog.swift and DiagnosticsCrashMarker.swift.
 * The copy is not the iOS implementation. The XCTest next to those files is the Swift test,
 * and this machine cannot compile Swift.
 */
class DiagnosticsFileWriteGateMirrorTest {
    @Test
    fun throwingWriterPausesAndResumesAfterBackoff() {
        val appender = ScriptedAppender()
        appender.fail = true
        var now = 1_700_000_000_000L
        val writer = MirrorFileLogWriter(
            appender = appender,
            nowMillis = { now },
            freeBytes = { MirrorFileWriteGate.LOW_SPACE_THRESHOLD_BYTES },
        )

        assertFalse(writer.append("download failed"))
        assertEquals(1, appender.attempts)
        assertFalse(writer.append("still full"))
        assertEquals(1, appender.attempts)

        now += MirrorFileWriteGate.FAILURE_BACKOFF_MILLIS - 1
        assertFalse(writer.append("still paused"))
        assertEquals(1, appender.attempts)

        now += 1
        appender.fail = false
        assertTrue(writer.append("retry"))
        assertEquals(listOf("retry"), appender.lines)

        assertTrue(writer.append("after backoff"))
        assertEquals(listOf("retry", "after backoff"), appender.lines)
    }

    @Test
    fun lowSpaceSkipsUntilCapacityReturns() {
        val appender = ScriptedAppender()
        var freeBytes: Long? = MirrorFileWriteGate.LOW_SPACE_THRESHOLD_BYTES - 1
        val writer = MirrorFileLogWriter(
            appender = appender,
            nowMillis = { 0L },
            freeBytes = { freeBytes },
        )

        assertFalse(writer.append("skipped"))
        assertEquals(0, appender.attempts)

        freeBytes = MirrorFileWriteGate.LOW_SPACE_THRESHOLD_BYTES
        assertTrue(writer.append("written"))
        assertEquals(listOf("written"), appender.lines)
    }

    @Test
    fun unknownFreeSpaceStillAttemptsTheWrite() {
        val appender = ScriptedAppender()
        val writer = MirrorFileLogWriter(appender, nowMillis = { 0L }, freeBytes = { null })
        assertTrue(writer.append("attempted"))
        assertEquals(1, appender.attempts)
    }

    @Test
    fun gateDecisionTable() {
        val gate = MirrorFileWriteGate()
        val now = 10_000L
        val limit = MirrorFileWriteGate.LOW_SPACE_THRESHOLD_BYTES

        assertEquals(MirrorWriteChoice.Write, gate.decision(null, now))
        assertEquals(MirrorWriteChoice.Write, gate.decision(limit, now))
        assertEquals(MirrorWriteChoice.SkipLowSpace, gate.decision(limit - 1, now))
        assertEquals(MirrorWriteChoice.SkipLowSpace, gate.decision(0, now))

        gate.noteWriteFailed(now)
        assertEquals(MirrorWriteChoice.SkipPaused, gate.decision(limit, now))
        assertEquals(
            MirrorWriteChoice.Write,
            gate.decision(limit, now + MirrorFileWriteGate.FAILURE_BACKOFF_MILLIS),
        )

        gate.noteWriteSucceeded()
        assertEquals(MirrorWriteChoice.SkipLowSpace, gate.decision(0, now))
    }

    @Test
    fun crashMarkerClearsOnBackgroundAndTerminateWithoutLogging() {
        val armed = MirrorCrashMarker.markerAfterLaunch(markerWasSet = false, alreadyInBackground = false)
        assertFalse(armed.logUncleanTermination)
        assertTrue(armed.markerSet)

        val previousCrash = MirrorCrashMarker.markerAfterLaunch(markerWasSet = true, alreadyInBackground = false)
        assertTrue(previousCrash.logUncleanTermination)
        assertTrue(previousCrash.markerSet)

        val alreadyBackground = MirrorCrashMarker.markerAfterLaunch(markerWasSet = true, alreadyInBackground = true)
        assertTrue(alreadyBackground.logUncleanTermination)
        assertFalse(alreadyBackground.markerSet)

        val background = MirrorCrashMarker.decisionAfter(markerWasSet = true, signal = MirrorProcessSignal.EnteredBackground)
        assertFalse(background.logUncleanTermination)
        assertFalse(background.markerSet)

        val terminateSignal = MirrorCrashMarker.decisionAfter(markerWasSet = true, signal = MirrorProcessSignal.WillTerminate)
        assertFalse(terminateSignal.logUncleanTermination)
        assertFalse(terminateSignal.markerSet)

        val activeSignal = MirrorCrashMarker.decisionAfter(markerWasSet = false, signal = MirrorProcessSignal.BecameActive)
        assertFalse(activeSignal.logUncleanTermination)
        assertTrue(activeSignal.markerSet)

        val launchAfterBackground = MirrorCrashMarker.decisionAfter(
            markerWasSet = background.markerSet,
            signal = MirrorProcessSignal.Launch,
        )
        assertFalse(launchAfterBackground.logUncleanTermination)
        assertTrue(launchAfterBackground.markerSet)
    }
}

internal enum class MirrorWriteChoice { Write, SkipLowSpace, SkipPaused }

internal class MirrorFileWriteGate {
    var pausedUntilMillis: Long? = null

    fun decision(freeBytes: Long?, nowMillis: Long): MirrorWriteChoice {
        val pausedUntil = pausedUntilMillis
        if (pausedUntil != null && nowMillis < pausedUntil) return MirrorWriteChoice.SkipPaused
        if (freeBytes != null && freeBytes < LOW_SPACE_THRESHOLD_BYTES) return MirrorWriteChoice.SkipLowSpace
        return MirrorWriteChoice.Write
    }

    fun noteWriteSucceeded() {
        pausedUntilMillis = null
    }

    fun noteWriteFailed(nowMillis: Long) {
        pausedUntilMillis = nowMillis + FAILURE_BACKOFF_MILLIS
    }

    companion object {
        const val LOW_SPACE_THRESHOLD_BYTES: Long = 50L * 1024L * 1024L
        const val FAILURE_BACKOFF_MILLIS: Long = 30_000L
    }
}

internal class MirrorFileLogWriter(
    private val appender: MirrorFileAppending,
    private val nowMillis: () -> Long,
    private val freeBytes: () -> Long?,
) {
    private val gate = MirrorFileWriteGate()

    fun append(line: String): Boolean {
        val current = nowMillis()
        return when (gate.decision(freeBytes(), current)) {
            MirrorWriteChoice.SkipLowSpace, MirrorWriteChoice.SkipPaused -> false
            MirrorWriteChoice.Write -> {
                try {
                    appender.append(line)
                    gate.noteWriteSucceeded()
                    true
                } catch (_: Exception) {
                    gate.noteWriteFailed(current)
                    false
                }
            }
        }
    }
}

internal fun interface MirrorFileAppending {
    fun append(line: String)
}

internal enum class MirrorProcessSignal { Launch, BecameActive, EnteredBackground, WillTerminate }

internal data class MirrorCrashMarkerDecision(
    val logUncleanTermination: Boolean,
    val markerSet: Boolean,
)

internal object MirrorCrashMarker {
    fun decisionAfter(markerWasSet: Boolean, signal: MirrorProcessSignal): MirrorCrashMarkerDecision =
        when (signal) {
            MirrorProcessSignal.Launch -> MirrorCrashMarkerDecision(logUncleanTermination = markerWasSet, markerSet = true)
            MirrorProcessSignal.BecameActive -> MirrorCrashMarkerDecision(logUncleanTermination = false, markerSet = true)
            MirrorProcessSignal.EnteredBackground, MirrorProcessSignal.WillTerminate ->
                MirrorCrashMarkerDecision(logUncleanTermination = false, markerSet = false)
        }

    fun markerAfterLaunch(markerWasSet: Boolean, alreadyInBackground: Boolean): MirrorCrashMarkerDecision {
        val launch = decisionAfter(markerWasSet, MirrorProcessSignal.Launch)
        if (!alreadyInBackground) return launch
        val backgrounded = decisionAfter(launch.markerSet, MirrorProcessSignal.EnteredBackground)
        return MirrorCrashMarkerDecision(
            logUncleanTermination = launch.logUncleanTermination,
            markerSet = backgrounded.markerSet,
        )
    }
}

private class ScriptedAppender : MirrorFileAppending {
    val lines = mutableListOf<String>()
    var attempts = 0
    var fail = false

    override fun append(line: String) {
        attempts += 1
        if (fail) throw IllegalStateException("disk full")
        lines += line
    }
}
