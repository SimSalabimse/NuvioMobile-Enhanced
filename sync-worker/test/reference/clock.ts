/**
 * Hybrid logical clock. Producers call tick. Receivers call receive.
 * A wall time more than MAX_HLC_SKEW_MS ahead of the device's now is ignored.
 */

export const MAX_HLC_COUNTER = 1_000_000;
export const MAX_HLC_SKEW_MS = 24 * 60 * 60 * 1000;
export const TOMBSTONE_KEEP_MS = 90 * 24 * 60 * 60 * 1000;

const DEVICE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

export interface DeviceClock {
  wallMs: number;
  counter: number;
}

export interface ClockStamp extends DeviceClock {
  deviceId: string;
}

export class ClockError extends Error {
  constructor(readonly code: "bad_input" | "counter_exhausted") {
    super(code);
  }
}

export function clockIsAcceptable(wallMs: number, nowMs: number): boolean {
  return Number.isSafeInteger(wallMs) && Number.isSafeInteger(nowMs) && wallMs >= 0 && nowMs >= 0 && wallMs <= nowMs + MAX_HLC_SKEW_MS;
}

/**
 * Next clock for a record this device writes.
 * A saved wall more than the skew ahead of nowMs is dropped. That happens when
 * the device clock was wrong and has been corrected. Staying on that wall would
 * keep emitting records nobody else will accept.
 */
export function tick(local: DeviceClock, nowMs: number, deviceId: string): ClockStamp {
  if (!Number.isSafeInteger(nowMs) || nowMs < 0) throw new ClockError("bad_input");
  if (!Number.isSafeInteger(local.wallMs) || local.wallMs < 0 || !Number.isSafeInteger(local.counter) || local.counter < 0) {
    throw new ClockError("bad_input");
  }
  if (!DEVICE_ID.test(deviceId)) throw new ClockError("bad_input");
  let wallMs: number;
  let counter: number;
  if (nowMs > local.wallMs || local.wallMs > nowMs + MAX_HLC_SKEW_MS) {
    wallMs = nowMs;
    counter = 0;
  } else {
    wallMs = local.wallMs;
    counter = local.counter + 1;
  }
  if (counter > MAX_HLC_COUNTER) throw new ClockError("counter_exhausted");
  return { wallMs, counter, deviceId };
}

/** Adopt a remote clock. A far-future wall does not move the local clock. */
export function receive(local: DeviceClock, remote: ClockStamp, nowMs: number): DeviceClock {
  if (!clockIsAcceptable(remote.wallMs, nowMs)) return { wallMs: local.wallMs, counter: local.counter };
  if (remote.counter < 0 || remote.counter > MAX_HLC_COUNTER) return { wallMs: local.wallMs, counter: local.counter };
  if (remote.wallMs > local.wallMs || (remote.wallMs === local.wallMs && remote.counter > local.counter)) {
    return { wallMs: remote.wallMs, counter: remote.counter };
  }
  return { wallMs: local.wallMs, counter: local.counter };
}
