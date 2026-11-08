import Foundation

/// Property values mpv expects for a fitted subtitle sync.
/// `sub-speed` multiplies cue timestamps. `sub-delay` is seconds added after that.
enum MpvSubtitleSync {
    static func encoded(subSpeed: Double, subDelayMs: Int64) -> [String: Double] {
        let speed = subSpeed.isFinite && subSpeed > 0.5 && subSpeed < 1.5 ? subSpeed : 1
        return [
            "sub-speed": speed,
            "sub-delay": Double(subDelayMs) / 1000.0,
        ]
    }
}

/// Fit and user delay for one subtitle track.
/// State changes and the generation check run on the mpv queue. A fit captured for an older
/// generation does not apply after a track change. Swift 5: this is a lock, not an actor.
final class SubtitleTimingGate {
    private let lock = NSLock()
    private var generation: UInt64 = 0
    private var speed: Double = 1
    private var fitDelayMs: Int64 = 0
    private var userDelayMs: Int = 0
    private var userDelaySet = false

    func captureGeneration() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return generation
    }

    var hasUserDelay: Bool {
        lock.lock()
        defer { lock.unlock() }
        return userDelaySet
    }

    var rememberedUserDelay: Int {
        lock.lock()
        defer { lock.unlock() }
        return userDelayMs
    }

    /// Call on the mpv queue. Bumps the generation and clears the fit. The user delay stays.
    func beginTrack() {
        lock.lock()
        generation &+= 1
        speed = 1
        fitDelayMs = 0
        lock.unlock()
    }

    /// Call on the mpv queue. False when [capturedGeneration] is no longer the current track.
    func setFit(speed: Double, delayMs: Int64, capturedGeneration: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard capturedGeneration == generation else { return false }
        self.speed = speed
        self.fitDelayMs = delayMs
        return true
    }

    func setUserDelay(_ delayMs: Int, capturedGeneration: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard capturedGeneration == generation else { return false }
        userDelayMs = delayMs
        userDelaySet = true
        return true
    }

    func propertyValues(capturedGeneration: UInt64) -> [String: Double]? {
        lock.lock()
        defer { lock.unlock() }
        guard capturedGeneration == generation else { return nil }
        let combined = fitDelayMs + Int64(userDelayMs)
        let clamped = max(Int64(-60_000), min(Int64(60_000), combined))
        return MpvSubtitleSync.encoded(subSpeed: speed, subDelayMs: clamped)
    }
}
