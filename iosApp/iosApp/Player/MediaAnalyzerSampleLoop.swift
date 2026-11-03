import Foundation
import Libavcodec
import Libavutil

/// Per-sample work for one decoded audio file: K-weighted loudness, one RMS byte per 100 ms,
/// and a boxcar-decimated mono stream for the head and tail fingerprints.
///
/// Real files take the fast path: the same direct-form biquads and ordered mono mix as the
/// original scalar loop, with that state kept in local variables for the frame and with each
/// sample loaded through a raw pointer. A debug build can set `forceScalar` so the original
/// per-property loop runs on that same file. `processPlanar` and `processPcm` run either loop
/// on a buffer the tests own, with no decoder.
///
/// Energy bytes, fingerprint samples and integrated loudness stay bit-identical with the scalar
/// loop. Intro and credits times come from the fingerprint samples.
final class MediaAnalyzerSampleLoop {
    static let headWindowMs: Int64 = 10 * 60_000
    static let fingerprintTargetRate = 6000.0

    /// Debug-only switch for timing the old loop against the fast loop on one file.
    /// Release builds ignore this and always use the fast loop.
    #if DEBUG
    static var forceScalar = false
    #endif

    static var usesScalarPath: Bool {
        #if DEBUG
        return forceScalar
        #else
        return false
        #endif
    }

    let rate: Int
    let channels: Int
    let tailStartSample: Int64
    let headEndSample: Int64
    let decimation: Int
    let fingerprintRate: Double

    var energyLevels: [UInt8] = []
    var headSamples: [Float] = []
    var tailSamples: [Float] = []
    private(set) var blockPowers: [Double] = []

    private let stepSamples: Int
    private let scalar: Bool
    private var sampleIndex: Int64 = 0
    private var stepSquares = 0.0
    private var stepCount = 0
    private var shelves: [Biquad]
    private var highPasses: [Biquad]
    private let weights: [Double]
    private var channelSquares: [Double]
    private var decimSum: Float = 0
    private var decimCount = 0

    var decodedMs: Int64 { sampleIndex * 1000 / Int64(max(1, rate)) }

    init(rate: Int, channels: Int, durationMs: Int64, tailStartMs: Int64, headWindowMs: Int64 = MediaAnalyzerSampleLoop.headWindowMs, scalar: Bool = MediaAnalyzerSampleLoop.usesScalarPath) {
        self.rate = rate
        self.channels = channels
        self.scalar = scalar
        stepSamples = max(1, rate / 10)
        tailStartSample = durationMs > 0 ? tailStartMs * Int64(rate) / 1000 : Int64.max
        headEndSample = headWindowMs * Int64(rate) / 1000
        decimation = max(1, Int((Double(rate) / Self.fingerprintTargetRate).rounded()))
        fingerprintRate = Double(rate) / Double(decimation)
        let shelf = Biquad.kShelf(rate: Double(rate))
        let highPass = Biquad.kHighPass(rate: Double(rate))
        shelves = Array(repeating: shelf, count: channels)
        highPasses = Array(repeating: highPass, count: channels)
        // BS.1770 channel weights for 5.1 (L R C LFE Ls Rs): surrounds 1.41, LFE excluded.
        if channels == 6 {
            weights = [1, 1, 1, 0, 1.41, 1.41]
        } else if channels == 8 {
            weights = [1, 1, 1, 0, 1.41, 1.41, 1.41, 1.41]
        } else {
            weights = Array(repeating: 1, count: channels)
        }
        channelSquares = Array(repeating: 0, count: channels)
        energyLevels.reserveCapacity(Int(max(durationMs, 0) / 100) + 16)
    }

    func consume(frame: UnsafeMutablePointer<AVFrame>) {
        if scalar {
            consumeScalar(frame: frame)
        } else {
            consumeFast(frame: frame)
        }
    }

    /// Runs the chosen loop over [channels] (one array per channel, equal length) in frames of
    /// [frameLength]. [frameLength] may be any positive count, including counts that are not a
    /// multiple of the decimation or of the 100 ms step; the leftover samples stay for the next frame.
    static func processPlanar(
        channels planar: [[Double]],
        rate: Int,
        durationMs: Int64,
        tailStartMs: Int64,
        scalar: Bool,
        headWindowMs: Int64 = MediaAnalyzerSampleLoop.headWindowMs,
        frameLength: Int = 1024
    ) -> MediaAnalyzerSampleLoopOutput {
        let channelCount = planar.count
        let total = planar.map(\.count).min() ?? 0
        let loop = MediaAnalyzerSampleLoop(
            rate: max(1, rate),
            channels: max(1, channelCount),
            durationMs: durationMs,
            tailStartMs: tailStartMs,
            headWindowMs: headWindowMs,
            scalar: scalar
        )
        guard channelCount > 0, total > 0 else { return loop.output() }
        let chunk = max(1, frameLength)
        var offset = 0
        while offset < total {
            let count = min(chunk, total - offset)
            let frame = planar.map { Array($0[offset..<(offset + count)]) }
            if scalar {
                loop.consumeScalar(planar: frame)
            } else {
                loop.consumeFast(planar: frame)
            }
            offset += count
        }
        return loop.output()
    }

    /// Same loops as [consume], fed with packed PCM instead of an `AVFrame`. A frame whose
    /// `sampleCount` is 0 is skipped, including a format change that carries no samples.
    /// A nil plane is a missing `extended_data` pointer and reads as zeros. An unknown format
    /// reads as zeros.
    static func processPcm(
        frames: [MediaAnalyzerPcmFrame],
        rate: Int,
        channels: Int,
        durationMs: Int64,
        tailStartMs: Int64,
        scalar: Bool,
        headWindowMs: Int64 = MediaAnalyzerSampleLoop.headWindowMs
    ) -> MediaAnalyzerSampleLoopOutput {
        let loop = MediaAnalyzerSampleLoop(
            rate: max(1, rate),
            channels: max(1, channels),
            durationMs: durationMs,
            tailStartMs: tailStartMs,
            headWindowMs: headWindowMs,
            scalar: scalar
        )
        for frame in frames {
            let frameChannels = min(loop.channels, max(0, frame.channels))
            Self.withPlaneTable(bytes: frame.planes, slots: max(frame.channels, 1)) { data in
                if scalar {
                    loop.consumeScalar(
                        data: data,
                        format: frame.avFormat,
                        planar: frame.planar,
                        frameChannels: frameChannels,
                        count: frame.sampleCount
                    )
                } else {
                    loop.consumeFast(
                        data: data,
                        format: frame.avFormat,
                        planar: frame.planar,
                        frameChannels: frameChannels,
                        count: frame.sampleCount
                    )
                }
            }
        }
        return loop.output()
    }

    func output() -> MediaAnalyzerSampleLoopOutput {
        MediaAnalyzerSampleLoopOutput(
            energyLevels: energyLevels,
            headSamples: headSamples,
            tailSamples: tailSamples,
            blockPowers: blockPowers,
            integratedLufs: integratedLoudness()
        )
    }

    /// Integrated loudness from 400 ms blocks (four 100 ms steps, 75 % overlap), gated per BS.1770-4.
    func integratedLoudness() -> Double? {
        guard blockPowers.count >= 4 else { return nil }
        var blocks: [Double] = []
        blocks.reserveCapacity(blockPowers.count)
        for j in 0...(blockPowers.count - 4) {
            blocks.append((blockPowers[j] + blockPowers[j + 1] + blockPowers[j + 2] + blockPowers[j + 3]) / 4)
        }
        func lufs(_ power: Double) -> Double { -0.691 + 10 * log10(power) }
        let absolute = blocks.filter { $0 > 0 && lufs($0) > -70 }
        guard !absolute.isEmpty else { return nil }
        let relativeGate = lufs(absolute.reduce(0, +) / Double(absolute.count)) - 10
        let gated = absolute.filter { lufs($0) > relativeGate }
        guard !gated.isEmpty else { return nil }
        return lufs(gated.reduce(0, +) / Double(gated.count))
    }

    // MARK: - Scalar (the original per-sample loop)

    private func consumeScalar(frame: UnsafeMutablePointer<AVFrame>) {
        let count = Int(frame.pointee.nb_samples)
        guard count > 0, let data = frame.pointee.extended_data else { return }
        let format = AVSampleFormat(rawValue: frame.pointee.format)
        let planar = av_sample_fmt_is_planar(format) != 0
        let frameChannels = min(channels, Int(frame.pointee.ch_layout.nb_channels))
        consumeScalar(data: data, format: format, planar: planar, frameChannels: frameChannels, count: count)
    }

    private func consumeScalar(
        data: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>,
        format: AVSampleFormat,
        planar: Bool,
        frameChannels: Int,
        count: Int
    ) {
        guard count > 0 else { return }
        let speechChannels = weights.prefix(frameChannels).filter { $0 > 0 }.count

        for i in 0..<count {
            var mono = 0.0
            for ch in 0..<frameChannels {
                let value = Self.sample(data: data, format: format, planar: planar, channel: ch, index: i, channels: frameChannels)
                let k = highPasses[ch].process(shelves[ch].process(value))
                channelSquares[ch] += k * k
                if weights[ch] > 0 { mono += value }
            }
            mono /= Double(max(1, speechChannels))
            pushMono(mono)
        }
    }

    private func consumeScalar(planar: [[Double]]) {
        let frameChannels = min(channels, planar.count)
        let count = planar.prefix(frameChannels).map(\.count).min() ?? 0
        guard count > 0 else { return }
        let speechChannels = weights.prefix(frameChannels).filter { $0 > 0 }.count

        for i in 0..<count {
            var mono = 0.0
            for ch in 0..<frameChannels {
                let value = planar[ch][i]
                let k = highPasses[ch].process(shelves[ch].process(value))
                channelSquares[ch] += k * k
                if weights[ch] > 0 { mono += value }
            }
            mono /= Double(max(1, speechChannels))
            pushMono(mono)
        }
    }

    /// One mono sample. Shared by both scalar entries so the fingerprint and energy math cannot drift.
    private func pushMono(_ mono: Double) {
        stepSquares += mono * mono
        stepCount += 1

        if sampleIndex < headEndSample || sampleIndex >= tailStartSample {
            decimSum += Float(mono)
            decimCount += 1
            if decimCount == decimation {
                let value = decimSum / Float(decimation)
                if sampleIndex < headEndSample { headSamples.append(value) } else { tailSamples.append(value) }
                decimSum = 0
                decimCount = 0
            }
        }

        if stepCount == stepSamples {
            closeStep()
        }
        sampleIndex += 1
    }

    // MARK: - Fast path (scalar math, locals, raw sample loads)

    private func consumeFast(frame: UnsafeMutablePointer<AVFrame>) {
        let count = Int(frame.pointee.nb_samples)
        guard count > 0, let data = frame.pointee.extended_data else { return }
        let format = AVSampleFormat(rawValue: frame.pointee.format)
        let planar = av_sample_fmt_is_planar(format) != 0
        let frameChannels = min(channels, Int(frame.pointee.ch_layout.nb_channels))
        consumeFast(data: data, format: format, planar: planar, frameChannels: frameChannels, count: count)
    }

    /// Doubles the tests already decoded. Presented to the fast loop as planar float64 so the
    /// sample load is the same raw-pointer read real DBLP frames use.
    private func consumeFast(planar source: [[Double]]) {
        let frameChannels = min(channels, source.count)
        let count = source.prefix(frameChannels).map(\.count).min() ?? 0
        guard count > 0 else { return }
        var planes = (0..<frameChannels).map { Array(source[$0].prefix(count)) }
        Self.withDoublePlanes(&planes) { data in
            consumeFast(data: data, format: AV_SAMPLE_FMT_DBLP, planar: true, frameChannels: frameChannels, count: count)
        }
    }

    /// Same math and order as [consumeScalar], with filter and step state in locals for the frame.
    private func consumeFast(
        data: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>,
        format: AVSampleFormat,
        planar: Bool,
        frameChannels: Int,
        count: Int
    ) {
        guard count > 0 else { return }
        let speechChannels = weights.prefix(frameChannels).filter { $0 > 0 }.count
        let divisor = Double(max(1, speechChannels))
        var sh = shelves
        var hp = highPasses
        var sq = channelSquares
        var stepSq = stepSquares
        var stepCnt = stepCount
        var idx = sampleIndex
        var dSum = decimSum
        var dCnt = decimCount
        let headEnd = headEndSample
        let tailStart = tailStartSample
        let dec = decimation
        let stepN = stepSamples
        // Own storage, so the read pointer is not the class property closeStep reads.
        var w = weights
        w.reserveCapacity(w.count + 1)
        sh.withUnsafeMutableBufferPointer { shp in
            hp.withUnsafeMutableBufferPointer { hpp in
                sq.withUnsafeMutableBufferPointer { sqp in
                    w.withUnsafeBufferPointer { wp in
                        guard let shelfBase = shp.baseAddress,
                              let highBase = hpp.baseAddress,
                              let squareBase = sqp.baseAddress,
                              let weightBase = wp.baseAddress else { return }
                        for i in 0..<count {
                            var mono = 0.0
                            for ch in 0..<frameChannels {
                                let value = Self.sampleFast(
                                    data: data,
                                    format: format,
                                    planar: planar,
                                    channel: ch,
                                    index: i,
                                    channels: frameChannels
                                )
                                var shelf = shelfBase[ch]
                                var high = highBase[ch]
                                let k = high.process(shelf.process(value))
                                shelfBase[ch] = shelf
                                highBase[ch] = high
                                squareBase[ch] += k * k
                                if weightBase[ch] > 0 { mono += value }
                            }
                            mono /= divisor
                            stepSq += mono * mono
                            stepCnt += 1
                            if idx < headEnd || idx >= tailStart {
                                dSum += Float(mono)
                                dCnt += 1
                                if dCnt == dec {
                                    let value = dSum / Float(dec)
                                    if idx < headEnd { headSamples.append(value) } else { tailSamples.append(value) }
                                    dSum = 0
                                    dCnt = 0
                                }
                            }
                            if stepCnt == stepN {
                                for c in 0..<sqp.count { channelSquares[c] = squareBase[c] }
                                stepSquares = stepSq
                                stepCount = stepCnt
                                closeStep()
                                for c in 0..<sqp.count { squareBase[c] = 0 }
                                stepSq = 0
                                stepCnt = 0
                            }
                            idx += 1
                        }
                    }
                }
            }
        }
        shelves = sh
        highPasses = hp
        channelSquares = sq
        stepSquares = stepSq
        stepCount = stepCnt
        sampleIndex = idx
        decimSum = dSum
        decimCount = dCnt
    }

    private func closeStep() {
        let rms = sqrt(stepSquares / Double(max(1, stepCount)))
        let db = rms > 0 ? 20 * log10(rms) : -90
        energyLevels.append(UInt8(max(0, min(255, ((db + 90) / 90 * 255).rounded()))))
        var power = 0.0
        for ch in 0..<channels {
            power += weights[ch] * channelSquares[ch] / Double(max(1, stepCount))
            channelSquares[ch] = 0
        }
        blockPowers.append(power)
        stepSquares = 0
        stepCount = 0
    }

    /// One sample, loaded without `withMemoryRebound`. [base] is the plane pointer from FFmpeg
    /// (or an aligned test buffer). The byte offset is applied on a raw pointer, then bound to
    /// the sample type. Unknown formats are zeros, matching [sample].
    @inline(__always)
    private static func sampleFast(
        data: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>,
        format: AVSampleFormat,
        planar: Bool,
        channel: Int,
        index: Int,
        channels: Int
    ) -> Double {
        let plane = planar ? channel : 0
        let offset = planar ? index : index * channels + channel
        guard let base = data[plane] else { return 0 }
        switch format {
        case AV_SAMPLE_FMT_FLT, AV_SAMPLE_FMT_FLTP:
            let raw = UnsafeMutableRawPointer(base).advanced(by: offset * MemoryLayout<Float>.stride)
            return Double(raw.assumingMemoryBound(to: Float.self).pointee)
        case AV_SAMPLE_FMT_DBL, AV_SAMPLE_FMT_DBLP:
            let raw = UnsafeMutableRawPointer(base).advanced(by: offset * MemoryLayout<Double>.stride)
            return raw.assumingMemoryBound(to: Double.self).pointee
        case AV_SAMPLE_FMT_S16, AV_SAMPLE_FMT_S16P:
            let raw = UnsafeMutableRawPointer(base).advanced(by: offset * MemoryLayout<Int16>.stride)
            return Double(raw.assumingMemoryBound(to: Int16.self).pointee) / 32768.0
        case AV_SAMPLE_FMT_S32, AV_SAMPLE_FMT_S32P:
            let raw = UnsafeMutableRawPointer(base).advanced(by: offset * MemoryLayout<Int32>.stride)
            return Double(raw.assumingMemoryBound(to: Int32.self).pointee) / 2147483648.0
        case AV_SAMPLE_FMT_U8, AV_SAMPLE_FMT_U8P:
            return (Double(base[offset]) - 128.0) / 128.0
        default:
            return 0
        }
    }

    private static func sample(
        data: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>,
        format: AVSampleFormat,
        planar: Bool,
        channel: Int,
        index: Int,
        channels: Int
    ) -> Double {
        let plane = planar ? channel : 0
        let offset = planar ? index : index * channels + channel
        guard let base = data[plane] else { return 0 }
        switch format {
        case AV_SAMPLE_FMT_FLT, AV_SAMPLE_FMT_FLTP:
            return Double(base.withMemoryRebound(to: Float.self, capacity: offset + 1) { $0[offset] })
        case AV_SAMPLE_FMT_DBL, AV_SAMPLE_FMT_DBLP:
            return base.withMemoryRebound(to: Double.self, capacity: offset + 1) { $0[offset] }
        case AV_SAMPLE_FMT_S16, AV_SAMPLE_FMT_S16P:
            return Double(base.withMemoryRebound(to: Int16.self, capacity: offset + 1) { $0[offset] }) / 32768.0
        case AV_SAMPLE_FMT_S32, AV_SAMPLE_FMT_S32P:
            return Double(base.withMemoryRebound(to: Int32.self, capacity: offset + 1) { $0[offset] }) / 2147483648.0
        case AV_SAMPLE_FMT_U8, AV_SAMPLE_FMT_U8P:
            return (Double(base[offset]) - 128.0) / 128.0
        default:
            return 0
        }
    }

    /// [body] sees an `extended_data` table. Nil entries and empty buffers are null planes.
    /// Storage is 32-byte aligned so a later `assumingMemoryBound` to Float or Double is valid.
    /// Pointers are only valid inside [body].
    private static func withPlaneTable(
        bytes: [Data?],
        slots: Int,
        _ body: (UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>) -> Void
    ) {
        let width = max(slots, bytes.count, 1)
        var held: [AlignedBytes?] = Array(repeating: nil, count: width)
        for index in 0..<width {
            guard index < bytes.count, let data = bytes[index], !data.isEmpty else { continue }
            held[index] = AlignedBytes(copying: data)
        }
        var pointers = [UnsafeMutablePointer<UInt8>?](repeating: nil, count: width)
        for index in 0..<width {
            pointers[index] = held[index]?.pointer
        }
        pointers.withUnsafeMutableBufferPointer { buffer in
            if let base = buffer.baseAddress {
                body(base)
            }
        }
    }

    private static func withDoublePlanes(
        _ planes: inout [[Double]],
        _ body: (UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>) -> Void
    ) {
        // Own copies, so no closure touches `planes` while its bytes are borrowed (exclusivity).
        let storage: [UnsafeMutableBufferPointer<Double>] = planes.map { plane in
            let buffer = UnsafeMutableBufferPointer<Double>.allocate(capacity: max(plane.count, 1))
            _ = buffer.initialize(from: plane)
            return buffer
        }
        defer { storage.forEach { $0.deallocate() } }
        var pointers = [UnsafeMutablePointer<UInt8>?](repeating: nil, count: max(planes.count, 1))
        for index in 0..<planes.count where !planes[index].isEmpty {
            pointers[index] = storage[index].baseAddress.map {
                UnsafeMutableRawPointer($0).assumingMemoryBound(to: UInt8.self)
            }
        }
        pointers.withUnsafeMutableBufferPointer { buffer in
            if let base = buffer.baseAddress {
                body(base)
            }
        }
        for index in 0..<planes.count {
            planes[index] = Array(storage[index].prefix(planes[index].count))
        }
    }
}

/// Packed PCM for [MediaAnalyzerSampleLoop.processPcm]. One entry is one decoded frame.
struct MediaAnalyzerPcmFrame {
    enum Format {
        case float32
        case float64
        case int16
        case int32
        case uint8
        case unknown
    }

    var format: Format
    /// Planar: `planes[channel]` holds `sampleCount` native-endian samples.
    /// Interleaved: `planes[0]` holds `sampleCount * channels` samples.
    var planar: Bool
    var channels: Int
    var sampleCount: Int
    /// A nil plane matches a null `extended_data` entry and reads as zeros.
    var planes: [Data?]

    fileprivate var avFormat: AVSampleFormat {
        switch (format, planar) {
        case (.float32, false): return AV_SAMPLE_FMT_FLT
        case (.float32, true): return AV_SAMPLE_FMT_FLTP
        case (.float64, false): return AV_SAMPLE_FMT_DBL
        case (.float64, true): return AV_SAMPLE_FMT_DBLP
        case (.int16, false): return AV_SAMPLE_FMT_S16
        case (.int16, true): return AV_SAMPLE_FMT_S16P
        case (.int32, false): return AV_SAMPLE_FMT_S32
        case (.int32, true): return AV_SAMPLE_FMT_S32P
        case (.uint8, false): return AV_SAMPLE_FMT_U8
        case (.uint8, true): return AV_SAMPLE_FMT_U8P
        case (.unknown, _): return AVSampleFormat(rawValue: -1)
        }
    }
}

struct MediaAnalyzerSampleLoopOutput {
    var energyLevels: [UInt8]
    var headSamples: [Float]
    var tailSamples: [Float]
    var blockPowers: [Double]
    var integratedLufs: Double?
}

/// How close an older vector path stayed to the scalar loop.
/// The fast path matches the scalar loop exactly, including loudness. These ceilings remain so a
/// comparison can still allow the margin that path used.
enum MediaAnalyzerSampleLoopTolerance {
    static let blockPowerAbsolute = 1e-6
    static let blockPowerRelative = 1e-4
    /// LU. Downstream intro, credits and recap times do not use loudness.
    static let loudnessLu = 0.05
}

private struct Biquad {
    var b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0
    var z1 = 0.0, z2 = 0.0

    mutating func process(_ x: Double) -> Double {
        let y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    /// BS.1770 stage 1 (head-related high shelf), for any sample rate.
    static func kShelf(rate: Double) -> Biquad {
        let f0 = 1681.974450955533, g = 3.999843853973347, q = 0.7071752369554196
        let k = tan(Double.pi * f0 / rate)
        let vh = pow(10.0, g / 20.0)
        let vb = pow(vh, 0.4996667741545416)
        let a0 = 1.0 + k / q + k * k
        return Biquad(
            b0: (vh + vb * k / q + k * k) / a0,
            b1: 2.0 * (k * k - vh) / a0,
            b2: (vh - vb * k / q + k * k) / a0,
            a1: 2.0 * (k * k - 1.0) / a0,
            a2: (1.0 - k / q + k * k) / a0
        )
    }

    /// BS.1770 stage 2 (RLB high-pass).
    static func kHighPass(rate: Double) -> Biquad {
        let f0 = 38.13547087602444, q = 0.5003270373238773
        let k = tan(Double.pi * f0 / rate)
        let a0 = 1.0 + k / q + k * k
        return Biquad(b0: 1.0, b1: -2.0, b2: 1.0, a1: 2.0 * (k * k - 1.0) / a0, a2: (1.0 - k / q + k * k) / a0)
    }
}

/// 32-byte aligned copy of a PCM plane. FFmpeg frame planes are aligned; `[UInt8]` is not.
private final class AlignedBytes {
    private let raw: UnsafeMutableRawPointer
    private let size: Int

    init(copying data: Data) {
        size = data.count
        raw = UnsafeMutableRawPointer.allocate(byteCount: max(size, 1), alignment: 32)
        raw.initializeMemory(as: UInt8.self, repeating: 0, count: max(size, 1))
        if size > 0 {
            data.withUnsafeBytes { src in
                if let base = src.baseAddress {
                    raw.copyMemory(from: base, byteCount: size)
                }
            }
        }
    }

    deinit {
        raw.deallocate()
    }

    var pointer: UnsafeMutablePointer<UInt8> {
        raw.assumingMemoryBound(to: UInt8.self)
    }
}
