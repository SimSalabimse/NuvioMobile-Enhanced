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

    func testAFitCapturedBeforeATrackChangeDoesNotApply() {
        let gate = SubtitleTimingGate()
        let oldTrack = gate.captureGeneration()
        XCTAssertTrue(gate.setFit(speed: 0.9901, delayMs: -400, capturedGeneration: oldTrack))
        gate.beginTrack()
        XCTAssertFalse(gate.setFit(speed: 0.9901, delayMs: -400, capturedGeneration: oldTrack))
        XCTAssertTrue(gate.setUserDelay(250, capturedGeneration: gate.captureGeneration()))
        let values = gate.propertyValues(capturedGeneration: gate.captureGeneration())
        XCTAssertEqual(values?["sub-speed"] ?? 0, 1, accuracy: 0.0000001)
        XCTAssertEqual(values?["sub-delay"] ?? 0, 0.25, accuracy: 0.0000001)
        XCTAssertNil(gate.propertyValues(capturedGeneration: oldTrack))
    }

    func testUserDelayStaysOnceAfterTheFitIsCleared() {
        let gate = SubtitleTimingGate()
        let generation = gate.captureGeneration()
        XCTAssertTrue(gate.setUserDelay(250, capturedGeneration: generation))
        XCTAssertTrue(gate.setUserDelay(250, capturedGeneration: generation))
        XCTAssertTrue(gate.setFit(speed: 0.99, delayMs: -2_000, capturedGeneration: generation))
        let combined = gate.propertyValues(capturedGeneration: generation)
        XCTAssertEqual(combined?["sub-delay"] ?? 0, -1.75, accuracy: 0.0000001)
        gate.beginTrack()
        let cleared = gate.propertyValues(capturedGeneration: gate.captureGeneration())
        XCTAssertEqual(cleared?["sub-speed"] ?? 0, 1, accuracy: 0.0000001)
        XCTAssertEqual(cleared?["sub-delay"] ?? 0, 0.25, accuracy: 0.0000001)
    }
}
