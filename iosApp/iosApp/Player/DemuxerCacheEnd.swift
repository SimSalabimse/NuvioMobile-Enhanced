import Foundation

/// Absolute end of cached media, in seconds.
///
/// `seekableRangeEnd` (`demuxer-cache-state/seekable-ranges/0/end`) and
/// `cacheTime` (`demuxer-cache-time`) are absolute timestamps. An absolute end
/// more than 0.4s past the playhead wins. `cacheDuration`
/// (`demuxer-cache-duration`) is seconds ahead of the playhead, and is added
/// only when it is greater than 0.5 and less than 180. Otherwise the end is
/// the playhead. Do not add the playhead to `cacheTime`.
///
/// This file is compiled by the iOS app target. The desktop mpv bridge compiles
/// the same source at `MPVKit/Sources/DesktopMPVBridge/DemuxerCacheEnd.swift`.
/// Keep the two copies identical. This file stays free of AppKit and libmpv.
func demuxerCachedRangeEnd(
    playhead: Double,
    seekableRangeEnd: Double?,
    cacheTime: Double?,
    cacheDuration: Double?
) -> Double {
    let safePlayhead = playhead.isFinite ? playhead : 0
    let absoluteEnds = [seekableRangeEnd, cacheTime].compactMap { $0 }.filter { value in
        value.isFinite && value > safePlayhead + 0.4
    }
    if let end = absoluteEnds.max() {
        return end
    }
    if let ahead = cacheDuration, ahead.isFinite, ahead > 0.5, ahead < 180 {
        return safePlayhead + ahead
    }
    return safePlayhead
}
