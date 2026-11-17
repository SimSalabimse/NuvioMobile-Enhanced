import Foundation
import XCTest
@testable import iosApp

/// Old scalar loop against the fast loop, on a buffer this test builds.
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

    /// Packed PCM through the same two loops `consume` uses. `scalar: true` is the old loop
    /// (`forceScalar` selects that loop for a real file). Energy bytes, fingerprint samples and
    /// loudness have to match exactly.
    func testFastLoopMatchesScalarOnPackedFormats() {
        let body = Array(repeating: 1024, count: 19) + [13]
        comparePcm(
            label: "stereo FLTP",
            format: .float32,
            planar: true,
            channels: 2,
            sizes: body,
            expectLoudness: true
        )
        comparePcm(
            label: "5.1 FLTP",
            format: .float32,
            planar: true,
            channels: 6,
            sizes: body,
            expectLoudness: true
        )
        comparePcm(
            label: "S16 interleaved stereo",
            format: .int16,
            planar: false,
            channels: 2,
            sizes: body,
            expectLoudness: true
        )
        comparePcm(
            label: "FLT interleaved 5.1",
            format: .float32,
            planar: false,
            channels: 6,
            sizes: body,
            expectLoudness: true
        )
        comparePcm(
            label: "odd-sized last frame",
            format: .float64,
            planar: true,
            channels: 2,
            sizes: Array(repeating: 1000, count: 20) + [13],
            expectLoudness: true
        )
        comparePcm(
            label: "mid-file format with zero samples",
            format: .float32,
            planar: true,
            channels: 2,
            sizes: [10000, 9469],
            insertZeroAfter: 0,
            zeroFormat: .int16,
            expectLoudness: true
        )
        comparePcm(
            label: "DBL interleaved",
            format: .float64,
            planar: false,
            channels: 2,
            sizes: body,
            expectLoudness: true
        )
        comparePcm(
            label: "S32 planar",
            format: .int32,
            planar: true,
            channels: 1,
            sizes: body,
            expectLoudness: true
        )
        comparePcm(
            label: "U8 interleaved",
            format: .uint8,
            planar: false,
            channels: 2,
            sizes: body,
            expectLoudness: true
        )
        comparePcm(
            label: "unknown format is zeros",
            format: .unknown,
            planar: false,
            channels: 2,
            sizes: body,
            expectLoudness: false
        )
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

    private func comparePcm(
        label: String,
        format: MediaAnalyzerPcmFrame.Format,
        planar: Bool,
        channels: Int,
        sizes: [Int],
        insertZeroAfter: Int? = nil,
        zeroFormat: MediaAnalyzerPcmFrame.Format = .unknown,
        expectLoudness: Bool,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let rate = 48_000
        let frames = pcmFrames(
            format: format,
            planar: planar,
            channels: channels,
            sizes: sizes,
            insertZeroAfter: insertZeroAfter,
            zeroFormat: zeroFormat
        )
        let scalar = MediaAnalyzerSampleLoop.processPcm(
            frames: frames,
            rate: rate,
            channels: channels,
            durationMs: 1_000,
            tailStartMs: 300,
            scalar: true,
            headWindowMs: 200
        )
        let fast = MediaAnalyzerSampleLoop.processPcm(
            frames: frames,
            rate: rate,
            channels: channels,
            durationMs: 1_000,
            tailStartMs: 300,
            scalar: false,
            headWindowMs: 200
        )
        XCTAssertEqual(scalar.energyLevels, fast.energyLevels, label, file: file, line: line)
        XCTAssertEqual(scalar.headSamples, fast.headSamples, "\(label) fingerprint", file: file, line: line)
        XCTAssertEqual(scalar.tailSamples, fast.tailSamples, label, file: file, line: line)
        XCTAssertEqual(scalar.blockPowers, fast.blockPowers, label, file: file, line: line)
        switch (scalar.integratedLufs, fast.integratedLufs) {
        case (nil, nil):
            if expectLoudness {
                XCTFail("\(label) produced no loudness", file: file, line: line)
            }
        case let (left?, right?):
            XCTAssertEqual(left, right, label, file: file, line: line)
            if !expectLoudness {
                XCTFail("\(label) produced loudness from zeros", file: file, line: line)
            }
        default:
            XCTFail("\(label) loudness presence differs", file: file, line: line)
        }
        if expectLoudness {
            XCTAssertFalse(scalar.energyLevels.isEmpty, label, file: file, line: line)
            XCTAssertFalse(scalar.headSamples.isEmpty, label, file: file, line: line)
            XCTAssertFalse(scalar.tailSamples.isEmpty, label, file: file, line: line)
        } else {
            XCTAssertTrue(scalar.energyLevels.allSatisfy { $0 == 0 }, label, file: file, line: line)
        }
    }

    private func pcmFrames(
        format: MediaAnalyzerPcmFrame.Format,
        planar: Bool,
        channels: Int,
        sizes: [Int],
        insertZeroAfter: Int?,
        zeroFormat: MediaAnalyzerPcmFrame.Format
    ) -> [MediaAnalyzerPcmFrame] {
        var frames: [MediaAnalyzerPcmFrame] = []
        var start = 0
        for (index, size) in sizes.enumerated() {
            frames.append(pcmFrame(format: format, planar: planar, channels: channels, start: start, count: size))
            start += size
            if insertZeroAfter == index {
                frames.append(MediaAnalyzerPcmFrame(
                    format: zeroFormat,
                    planar: true,
                    channels: channels,
                    sampleCount: 0,
                    planes: []
                ))
            }
        }
        return frames
    }

    private func pcmFrame(
        format: MediaAnalyzerPcmFrame.Format,
        planar: Bool,
        channels: Int,
        start: Int,
        count: Int
    ) -> MediaAnalyzerPcmFrame {
        guard count > 0 else {
            return MediaAnalyzerPcmFrame(format: format, planar: planar, channels: channels, sampleCount: 0, planes: [])
        }
        if planar {
            var planes: [Data?] = []
            for channel in 0..<channels {
                var bytes: [UInt8] = []
                for index in 0..<count {
                    bytes.append(contentsOf: encode(signal(channel: channel, index: start + index), format: format))
                }
                planes.append(Data(bytes))
            }
            return MediaAnalyzerPcmFrame(format: format, planar: true, channels: channels, sampleCount: count, planes: planes)
        }
        var bytes: [UInt8] = []
        for index in 0..<count {
            for channel in 0..<channels {
                bytes.append(contentsOf: encode(signal(channel: channel, index: start + index), format: format))
            }
        }
        return MediaAnalyzerPcmFrame(format: format, planar: false, channels: channels, sampleCount: count, planes: [Data(bytes)])
    }

    private func signal(channel: Int, index: Int) -> Double {
        let t = Double(index) / 48_000
        return 0.25 * sin(2 * Double.pi * Double(90 * (channel + 1)) * t)
    }

    private func encode(_ value: Double, format: MediaAnalyzerPcmFrame.Format) -> [UInt8] {
        switch format {
        case .float32:
            var sample = Float(value)
            return withUnsafeBytes(of: &sample) { Array($0) }
        case .float64:
            var sample = value
            return withUnsafeBytes(of: &sample) { Array($0) }
        case .int16:
            var sample = Int16(clamping: Int((value * 32_767).rounded()))
            return withUnsafeBytes(of: &sample) { Array($0) }
        case .int32:
            // Same full-scale mapping as int16 (`value * 32_767`). A 0.25 sine is then about
            // -12 dBFS and clears the loudness gate. Scaling by 1_000_000 left it near -78 dBFS,
            // under the gate, so both loops returned no LUFS and the comparison never ran.
            var sample = Int32(clamping: Int((value * 2_147_483_647).rounded()))
            return withUnsafeBytes(of: &sample) { Array($0) }
        case .uint8:
            let scaled = Int((value * 128 + 128).rounded())
            return [UInt8(max(0, min(255, scaled)))]
        case .unknown:
            return [0, 0, 0, 0]
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
