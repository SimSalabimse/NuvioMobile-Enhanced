import Foundation

/// Standalone check for `demuxerCachedRangeEnd`. Compile it with that file:
/// `swiftc -o demuxer-cache-end-tests iosApp/iosApp/Player/DemuxerCacheEnd.swift iosApp/DemuxerCacheEndTests.swift`
/// This file is not an iOS app source.

func expectEnd(
    _ actual: Double,
    _ expected: Double,
    _ name: String
) {
    if actual != expected {
        fputs("FAIL \(name): expected \(expected) got \(actual)\n", stderr)
        exit(1)
    }
}

@main
struct DemuxerCacheEndTestRunner {
    static func main() {
        runDemuxerCacheEndTests()
        print("demuxerCachedRangeEnd: ok")
    }
}

func runDemuxerCacheEndTests() {
    // Playhead 35:04. Cache time is the absolute end, so the buffer ends at 2106,
    // not at playhead + cache time (4210).
    expectEnd(
        demuxerCachedRangeEnd(playhead: 2104, seekableRangeEnd: nil, cacheTime: 2106, cacheDuration: nil),
        2106,
        "cache time is an absolute end"
    )

    // No absolute end. A sane cache duration is seconds ahead of the playhead.
    expectEnd(
        demuxerCachedRangeEnd(playhead: 100, seekableRangeEnd: nil, cacheTime: nil, cacheDuration: 12),
        112,
        "cache duration within the guard is ahead of the playhead"
    )

    // 500s is outside the existing guard (greater than 0.5 and less than 180).
    expectEnd(
        demuxerCachedRangeEnd(playhead: 100, seekableRangeEnd: nil, cacheTime: nil, cacheDuration: 500),
        100,
        "cache duration outside the 180 second guard is ignored"
    )

    // The seekable-range end is also an absolute timestamp and beats a duration.
    expectEnd(
        demuxerCachedRangeEnd(playhead: 100, seekableRangeEnd: 140, cacheTime: nil, cacheDuration: 12),
        140,
        "seekable range end wins over cache duration"
    )

    // An absolute end still wins when a duration is also present.
    expectEnd(
        demuxerCachedRangeEnd(playhead: 2104, seekableRangeEnd: nil, cacheTime: 2106, cacheDuration: 500),
        2106,
        "absolute cache time wins over an out-of-range duration"
    )

    // The existing 0.4s margin: a cache timestamp that is not past the playhead
    // does not become the end.
    expectEnd(
        demuxerCachedRangeEnd(playhead: 100, seekableRangeEnd: nil, cacheTime: 100.3, cacheDuration: nil),
        100,
        "cache time within 0.4s of the playhead is not an absolute end"
    )
    expectEnd(
        demuxerCachedRangeEnd(playhead: 100, seekableRangeEnd: nil, cacheTime: 100.5, cacheDuration: 12),
        100.5,
        "cache time past the 0.4s margin is the end"
    )

    expectEnd(
        demuxerCachedRangeEnd(playhead: 100, seekableRangeEnd: nil, cacheTime: nil, cacheDuration: 0.5),
        100,
        "cache duration of 0.5 is outside the guard"
    )
    expectEnd(
        demuxerCachedRangeEnd(playhead: 100, seekableRangeEnd: nil, cacheTime: nil, cacheDuration: 180),
        100,
        "cache duration of 180 is outside the guard"
    )
}
