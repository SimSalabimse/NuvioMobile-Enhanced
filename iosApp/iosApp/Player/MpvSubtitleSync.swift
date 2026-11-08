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
