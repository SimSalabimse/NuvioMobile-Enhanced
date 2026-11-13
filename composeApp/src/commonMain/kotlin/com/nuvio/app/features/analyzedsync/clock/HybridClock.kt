package com.nuvio.app.features.analyzedsync.clock

import com.nuvio.app.features.analyzedsync.SYNC_MAX_HLC_COUNTER
import com.nuvio.app.features.analyzedsync.SYNC_MAX_HLC_SKEW_MS
import com.nuvio.app.features.analyzedsync.SYNC_MAX_SAFE_INTEGER

internal data class DeviceClock(val wallMs: Long, val counter: Int)

internal data class Hlc(val wallMs: Long, val counter: Int, val deviceId: String)

internal class ClockError(val code: String) : Exception(code)

private val DEVICE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

internal fun clockIsAcceptable(wallMs: Long, nowMs: Long): Boolean =
    wallMs in 0..SYNC_MAX_SAFE_INTEGER &&
        nowMs in 0..SYNC_MAX_SAFE_INTEGER &&
        wallMs <= nowMs + SYNC_MAX_HLC_SKEW_MS

/**
 * Next clock for a record this device writes.
 * A saved wall more than 24 hours ahead of [nowMs] is dropped, so a corrected clock
 * does not keep emitting records other devices reject.
 */
internal fun tick(local: DeviceClock, nowMs: Long, deviceId: String): Hlc {
    if (nowMs !in 0..SYNC_MAX_SAFE_INTEGER) throw ClockError("bad_input")
    if (local.wallMs !in 0..SYNC_MAX_SAFE_INTEGER || local.counter < 0) throw ClockError("bad_input")
    if (!DEVICE_ID.matches(deviceId)) throw ClockError("bad_input")
    val wallMs: Long
    val counter: Int
    if (nowMs > local.wallMs || local.wallMs > nowMs + SYNC_MAX_HLC_SKEW_MS) {
        wallMs = nowMs
        counter = 0
    } else {
        wallMs = local.wallMs
        counter = local.counter + 1
    }
    if (counter > SYNC_MAX_HLC_COUNTER) throw ClockError("counter_exhausted")
    return Hlc(wallMs, counter, deviceId)
}

/** Adopt a remote clock. A wall more than 24 hours ahead does not move the local clock. */
internal fun receive(local: DeviceClock, remote: Hlc, nowMs: Long): DeviceClock {
    if (!clockIsAcceptable(remote.wallMs, nowMs)) return local
    if (remote.counter !in 0..SYNC_MAX_HLC_COUNTER) return local
    if (remote.wallMs > local.wallMs || (remote.wallMs == local.wallMs && remote.counter > local.counter)) {
        return DeviceClock(remote.wallMs, remote.counter)
    }
    return local
}

internal fun compareHlc(left: Hlc, right: Hlc): Int {
    if (left.wallMs != right.wallMs) return if (left.wallMs > right.wallMs) 1 else -1
    if (left.counter != right.counter) return if (left.counter > right.counter) 1 else -1
    if (left.deviceId != right.deviceId) return if (left.deviceId > right.deviceId) 1 else -1
    return 0
}
