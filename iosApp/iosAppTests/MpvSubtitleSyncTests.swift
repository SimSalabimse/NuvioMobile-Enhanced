import XCTest

final class MpvSubtitleSyncTests: XCTestCase {
    func testLateCueUsesSpeedBelowOneAndDelayIsSeconds() {
        let values = MpvSubtitleSync.encoded(subSpeed: 0.996677, subDelayMs: -2_000)
        XCTAssertEqual(values["sub-speed"] ?? 0, 0.996677, accuracy: 0.0000001)
        XCTAssertEqual(values["sub-delay"] ?? 0, -2.0, accuracy: 0.0000001)
    }

    func testAnUntrustedSpeedResetsToOne() {
        let values = MpvSubtitleSync.encoded(subSpeed: 4, subDelayMs: 500)
        XCTAssertEqual(values["sub-speed"] ?? 0, 1, accuracy: 0.0000001)
        XCTAssertEqual(values["sub-delay"] ?? 0, 0.5, accuracy: 0.0000001)
    }
}
