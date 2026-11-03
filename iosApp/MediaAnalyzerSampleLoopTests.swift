import Foundation
import XCTest
@testable import iosApp

/// Old scalar loop against the vDSP loop, on a buffer this test builds.
/// Needs a Mac with Xcode. This VM has no Swift compiler, so the numbers are not invented here.
final class MediaAnalyzerSampleLoopTests: XCTestCase {
    func testVectorMatchesScalarOnSyntheticBuffer() {
        let rate = 48_000
        let frames = 48_000 + 13
        let planar = synth(rate: rate, frames: frames, channels: 2)
        let scalar = MediaAnalyzerSampleLoop.processPlanar(
            channels: planar,
            rate: rate,
            durationMs: 1_100,
            tailStartMs: 0,
            scalar: true,
            frameLength: 1003
        )
        let vector = MediaAnalyzerSampleLoop.processPlanar(
            channels: planar,
            rate: rate,
            durationMs: 1_100,
            tailStartMs: 0,
            scalar: false,
            frameLength: 1003
        )
        assertEquivalent(scalar, vector)
        XCTAssertFalse(scalar.energyLevels.isEmpty)
        XCTAssertFalse(scalar.headSamples.isEmpty)
    }

    func testVectorMatchesScalarAcrossHeadGapTailAndShortFrames() {
        let rate = 48_000
        // Head window ends at 200 ms, tail collection starts at 500 ms, file is 800 ms.
        // Decimation pauses in the gap and resumes, which is what the scalar loop does.
        let frames = 800 * rate / 1000 + 7
        let planar = synth(rate: rate, frames: frames, channels: 6)
        let scalar = MediaAnalyzerSampleLoop.processPlanar(
            channels: planar,
            rate: rate,
            durationMs: 800,
            tailStartMs: 500,
            scalar: true,
            headWindowMs: 200,
            frameLength: 1003
        )
        let vector = MediaAnalyzerSampleLoop.processPlanar(
            channels: planar,
            rate: rate,
            durationMs: 800,
            tailStartMs: 500,
            scalar: false,
            headWindowMs: 200,
            frameLength: 1003
        )
        assertEquivalent(scalar, vector)
        XCTAssertFalse(scalar.headSamples.isEmpty)
        XCTAssertFalse(scalar.tailSamples.isEmpty)
    }

    func testOneSampleAndSevenSampleTailsMatch() {
        for frames in [1, 7] {
            let planar = synth(rate: 48_000, frames: frames, channels: 1)
            let scalar = MediaAnalyzerSampleLoop.processPlanar(
                channels: planar,
                rate: 48_000,
                durationMs: 1,
                tailStartMs: 0,
                scalar: true,
                frameLength: frames
            )
            let vector = MediaAnalyzerSampleLoop.processPlanar(
                channels: planar,
                rate: 48_000,
                durationMs: 1,
                tailStartMs: 0,
                scalar: false,
                frameLength: frames
            )
            assertEquivalent(scalar, vector)
        }
    }

    func testSyntheticSpeedComparisonIsMeasured() {
        let rate = 48_000
        let frames = rate * 3
        let planar = synth(rate: rate, frames: frames, channels: 2)
        let scalarMs = elapsedMs {
            _ = MediaAnalyzerSampleLoop.processPlanar(
                channels: planar,
                rate: rate,
                durationMs: 3_000,
                tailStartMs: 0,
                scalar: true,
                frameLength: 1024
            )
        }
        let vectorStarted = DispatchTime.now().uptimeNanoseconds
        let vector = MediaAnalyzerSampleLoop.processPlanar(
            channels: planar,
            rate: rate,
            durationMs: 3_000,
            tailStartMs: 0,
            scalar: false,
            frameLength: 1024
        )
        let vectorMs = Int((DispatchTime.now().uptimeNanoseconds &- vectorStarted) / 1_000_000)
        let scalar = MediaAnalyzerSampleLoop.processPlanar(
            channels: planar,
            rate: rate,
            durationMs: 3_000,
            tailStartMs: 0,
            scalar: true,
            frameLength: 1024
        )
        assertEquivalent(scalar, vector)
        XCTAssertGreaterThan(scalarMs, 0)
        XCTAssertGreaterThan(vectorMs, 0)
        let speedup = Double(scalarMs) / Double(vectorMs)
        print("sample-loop synthetic scalarMs=\(scalarMs) vectorMs=\(vectorMs) speedup=\(String(format: "%.2f", speedup)) frames=\(frames) channels=2")
    }

    #if DEBUG
    func testDebugFlagSelectsTheScalarPath() {
        let previous = MediaAnalyzerSampleLoop.forceScalar
        defer { MediaAnalyzerSampleLoop.forceScalar = previous }
        MediaAnalyzerSampleLoop.forceScalar = true
        XCTAssertTrue(MediaAnalyzerSampleLoop.usesScalarPath)
        MediaAnalyzerSampleLoop.forceScalar = false
        XCTAssertFalse(MediaAnalyzerSampleLoop.usesScalarPath)
    }
    #endif

    private func elapsedMs(_ body: () -> Void) -> Int {
        let started = DispatchTime.now().uptimeNanoseconds
        body()
        return Int((DispatchTime.now().uptimeNanoseconds &- started) / 1_000_000)
    }

    private func assertEquivalent(
        _ scalar: MediaAnalyzerSampleLoopOutput,
        _ vector: MediaAnalyzerSampleLoopOutput,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(scalar.energyLevels, vector.energyLevels, file: file, line: line)
        XCTAssertEqual(scalar.headSamples, vector.headSamples, "fingerprint samples must match so intro/credits times stay the same", file: file, line: line)
        XCTAssertEqual(scalar.tailSamples, vector.tailSamples, file: file, line: line)
        XCTAssertEqual(scalar.blockPowers.count, vector.blockPowers.count, file: file, line: line)
        for (a, b) in zip(scalar.blockPowers, vector.blockPowers) {
            let scale = max(1.0, abs(a), abs(b))
            let accuracy = max(
                MediaAnalyzerSampleLoopTolerance.blockPowerAbsolute,
                MediaAnalyzerSampleLoopTolerance.blockPowerRelative * scale
            )
            XCTAssertEqual(a, b, accuracy: accuracy, file: file, line: line)
        }
        switch (scalar.integratedLufs, vector.integratedLufs) {
        case (nil, nil):
            break
        case let (a?, b?):
            XCTAssertEqual(a, b, accuracy: MediaAnalyzerSampleLoopTolerance.loudnessLu, file: file, line: line)
        default:
            XCTFail("loudness presence differs scalar=\(String(describing: scalar.integratedLufs)) vector=\(String(describing: vector.integratedLufs))", file: file, line: line)
        }
    }

    private func synth(rate: Int, frames: Int, channels: Int) -> [[Double]] {
        (0..<channels).map { channel in
            (0..<frames).map { index in
                let t = Double(index) / Double(rate)
                let freq = 110.0 * Double(channel + 1)
                return 0.25 * sin(2 * Double.pi * freq * t)
            }
        }
    }
}
