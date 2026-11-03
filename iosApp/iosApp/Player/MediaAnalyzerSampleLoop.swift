import Accelerate
import Foundation
import Libavcodec
import Libavutil

/// Per-sample work for one decoded audio file: K-weighted loudness, one RMS byte per 100 ms,
/// and a boxcar-decimated mono stream for the head and tail fingerprints.
///
/// The vDSP path is the one used for real files. A debug build can set `forceScalar` so the
/// original scalar loop runs on that same file. `processPlanar` runs either loop on a buffer
/// the tests own, with no decoder.
///
/// Fingerprint samples and energy bytes stay bit-identical with the scalar loop: the mono mix
/// is an ordered double add, and the decimation and the 100 ms squares are sequential. K-weighted
/// block power uses `vDSP_deq22D` and `vDSP_svesqD`, which round differently, so loudness is
/// allowed to differ (see [MediaAnalyzerSampleLoopTolerance]). Intro and credits times come from
/// the fingerprint samples, not from loudness.
final class MediaAnalyzerSampleLoop {
    static let headWindowMs: Int64 = 10 * 60_000
    static let fingerprintTargetRate = 6000.0

    /// Debug-only switch for timing the old loop against vDSP on one file.
    /// Release builds ignore this and always use vDSP.
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

    private let shelfCoeff: [Double]
    private let highPassCoeff: [Double]
    private var shelfDelay: [BiquadDelay]
    private var highPassDelay: [BiquadDelay]
    private var filteredPlanes: [[Double]] = []
    private var monoPlane: [Double] = []
    private var deqIn: [Double] = []
    private var deqOut: [Double] = []

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
        shelfCoeff = [shelf.b0, shelf.b1, shelf.b2, shelf.a1, shelf.a2]
        highPassCoeff = [highPass.b0, highPass.b1, highPass.b2, highPass.a1, highPass.a2]
        shelfDelay = Array(repeating: BiquadDelay(), count: channels)
        highPassDelay = Array(repeating: BiquadDelay(), count: channels)
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
            consumeVector(frame: frame)
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
        var loop = MediaAnalyzerSampleLoop(
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
                loop.consumeVector(planar: frame)
            }
            offset += count
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

    // MARK: - vDSP

    private func consumeVector(frame: UnsafeMutablePointer<AVFrame>) {
        let count = Int(frame.pointee.nb_samples)
        guard count > 0, let data = frame.pointee.extended_data else { return }
        let format = AVSampleFormat(rawValue: frame.pointee.format)
        let isPlanar = av_sample_fmt_is_planar(format) != 0
        let frameChannels = min(channels, Int(frame.pointee.ch_layout.nb_channels))
        guard frameChannels > 0 else { return }
        var planar = Array(repeating: [Double](), count: frameChannels)
        for ch in 0..<frameChannels {
            let plane = isPlanar ? ch : 0
            guard let base = data[plane] else {
                planar[ch] = [Double](repeating: 0, count: count)
                continue
            }
            let stride = isPlanar ? 1 : frameChannels
            let start = isPlanar ? 0 : ch
            planar[ch] = Self.convert(base: base, format: format, start: start, stride: stride, count: count)
        }
        consumeVector(planar: planar)
    }

    private func consumeVector(planar: [[Double]]) {
        let frameChannels = min(channels, planar.count)
        let count = planar.prefix(frameChannels).map(\.count).min() ?? 0
        guard count > 0, frameChannels > 0 else { return }
        preparePlanes(count: count, frameChannels: frameChannels)

        for ch in 0..<frameChannels {
            // Shelf and high-pass cannot share a buffer: the second stage reads the first stage's output.
            let shelf = apply(source: planar[ch], count: count, coefficients: shelfCoeff, delay: &shelfDelay[ch])
            let highPassed = apply(source: shelf, count: count, coefficients: highPassCoeff, delay: &highPassDelay[ch])
            for i in 0..<count {
                filteredPlanes[ch][i] = highPassed[i]
            }
        }

        // Ordered scalar mix, same as the old loop. A vector add/divide can round differently
        // and would move fingerprint bits. Channel count is at most 8; the biquads are the vDSP work.
        for i in 0..<count {
            monoPlane[i] = 0
        }
        var speech = 0
        for ch in 0..<frameChannels where weights[ch] > 0 {
            let plane = planar[ch]
            for i in 0..<count {
                monoPlane[i] += plane[i]
            }
            speech += 1
        }
        if speech > 1 {
            let divisor = Double(speech)
            for i in 0..<count {
                monoPlane[i] /= divisor
            }
        }

        // Step edges only. A step that ends mid-frame has to flush channel power before the next
        // samples are added. `slice` is the exact remainder, so a length that is not a multiple
        // of 4 (or of the decimation) is passed through to vDSP as that count, not padded.
        var offset = 0
        while offset < count {
            let slice = min(stepSamples - stepCount, count - offset)
            guard slice > 0 else { break }
            for ch in 0..<frameChannels {
                var sum = 0.0
                filteredPlanes[ch].withUnsafeBufferPointer { plane in
                    vDSP_svesqD(plane.baseAddress! + offset, 1, &sum, vDSP_Length(slice))
                }
                channelSquares[ch] += sum
            }
            for i in 0..<slice {
                pushMono(monoPlane[offset + i])
            }
            offset += slice
        }
    }

    /// `vDSP_deq22D` writes [count] new samples.
    /// Input and output each hold count + 2 values: index 0 and 1 are the previous two
    /// inputs or outputs (`x[n-2]`, `x[n-1]` and `y[n-2]`, `y[n-1]`).
    /// Coefficients are `b0, b1, b2, a1, a2` for
    /// `y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]`.
    /// Stride is 1. [count] is the exact number of new samples, including a short tail.
    private func apply(
        source: [Double],
        count: Int,
        coefficients: [Double],
        delay: inout BiquadDelay
    ) -> [Double] {
        var produced = [Double](repeating: 0, count: count)
        guard count > 0, coefficients.count == 5 else { return produced }
        let need = count + 2
        if deqIn.count < need {
            deqIn = [Double](repeating: 0, count: need)
            deqOut = [Double](repeating: 0, count: need)
        }
        let limit = min(count, source.count)
        deqIn[0] = delay.x2
        deqIn[1] = delay.x1
        for i in 0..<limit {
            deqIn[i + 2] = source[i]
        }
        if limit < count {
            for i in limit..<count {
                deqIn[i + 2] = 0
            }
        }
        deqOut[0] = delay.y2
        deqOut[1] = delay.y1
        deqIn.withUnsafeBufferPointer { input in
            deqOut.withUnsafeMutableBufferPointer { output in
                coefficients.withUnsafeBufferPointer { coeff in
                    vDSP_deq22D(
                        input.baseAddress!,
                        1,
                        coeff.baseAddress!,
                        output.baseAddress!,
                        1,
                        vDSP_Length(count)
                    )
                }
            }
        }
        delay.x2 = deqIn[count]
        delay.x1 = deqIn[count + 1]
        delay.y2 = deqOut[count]
        delay.y1 = deqOut[count + 1]
        for i in 0..<count {
            produced[i] = deqOut[i + 2]
        }
        return produced
    }

    private func preparePlanes(count: Int, frameChannels: Int) {
        if filteredPlanes.count < frameChannels {
            filteredPlanes.append(contentsOf: repeatElement([Double](), count: frameChannels - filteredPlanes.count))
        }
        for ch in 0..<frameChannels where filteredPlanes[ch].count < count {
            filteredPlanes[ch] = [Double](repeating: 0, count: count)
        }
        if monoPlane.count < count {
            monoPlane = [Double](repeating: 0, count: count)
        }
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

    /// Converts [count] samples starting at element [start], stepping by [stride].
    /// [count] is exact: a tail that is not a multiple of the vector width is included, not dropped.
    private static func convert(
        base: UnsafeMutablePointer<UInt8>,
        format: AVSampleFormat,
        start: Int,
        stride: Int,
        count: Int
    ) -> [Double] {
        guard count > 0, stride > 0 else { return [] }
        var dst = [Double](repeating: 0, count: count)
        let n = vDSP_Length(count)
        let step = vDSP_Stride(stride)
        switch format {
        case AV_SAMPLE_FMT_FLT, AV_SAMPLE_FMT_FLTP:
            let src = base.advanced(by: start * MemoryLayout<Float>.size).assumingMemoryBound(to: Float.self)
            dst.withUnsafeMutableBufferPointer { out in
                vDSP_vspdp(src, step, out.baseAddress!, 1, n)
            }
        case AV_SAMPLE_FMT_DBL, AV_SAMPLE_FMT_DBLP:
            let src = base.advanced(by: start * MemoryLayout<Double>.size).assumingMemoryBound(to: Double.self)
            var one = 1.0
            dst.withUnsafeMutableBufferPointer { out in
                vDSP_vsmulD(src, step, &one, out.baseAddress!, 1, n)
            }
        case AV_SAMPLE_FMT_S16, AV_SAMPLE_FMT_S16P:
            let src = base.advanced(by: start * MemoryLayout<Int16>.size).assumingMemoryBound(to: Int16.self)
            var scale = 32768.0
            dst.withUnsafeMutableBufferPointer { out in
                vDSP_vflt16D(src, step, out.baseAddress!, 1, n)
                vDSP_vsdivD(out.baseAddress!, 1, &scale, out.baseAddress!, 1, n)
            }
        case AV_SAMPLE_FMT_S32, AV_SAMPLE_FMT_S32P:
            let src = base.advanced(by: start * MemoryLayout<Int32>.size).assumingMemoryBound(to: Int32.self)
            var scale = 2147483648.0
            dst.withUnsafeMutableBufferPointer { out in
                vDSP_vflt32D(src, step, out.baseAddress!, 1, n)
                vDSP_vsdivD(out.baseAddress!, 1, &scale, out.baseAddress!, 1, n)
            }
        case AV_SAMPLE_FMT_U8, AV_SAMPLE_FMT_U8P:
            // UInt8 -> Float is exact, and Float -> Double is exact, so this matches
            // (Double(byte) - 128) / 128.
            var floats = [Float](repeating: 0, count: count)
            let src = base.advanced(by: start)
            floats.withUnsafeMutableBufferPointer { floatOut in
                vDSP_vfltu8(src, step, floatOut.baseAddress!, 1, n)
            }
            dst.withUnsafeMutableBufferPointer { out in
                floats.withUnsafeBufferPointer { floatOut in
                    vDSP_vspdp(floatOut.baseAddress!, 1, out.baseAddress!, 1, n)
                }
                var bias = -128.0
                vDSP_vsaddD(out.baseAddress!, 1, &bias, out.baseAddress!, 1, n)
                var scale = 128.0
                vDSP_vsdivD(out.baseAddress!, 1, &scale, out.baseAddress!, 1, n)
            }
        default:
            break
        }
        return dst
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
}

struct MediaAnalyzerSampleLoopOutput {
    var energyLevels: [UInt8]
    var headSamples: [Float]
    var tailSamples: [Float]
    var blockPowers: [Double]
    var integratedLufs: Double?
}

/// How close the vDSP loop stays to the scalar loop.
/// Fingerprint samples and energy bytes are exact. Block power and loudness use the K-weighting
/// filters, where `vDSP_deq22D` rounds differently from the scalar direct form.
enum MediaAnalyzerSampleLoopTolerance {
    static let blockPowerAbsolute = 1e-6
    static let blockPowerRelative = 1e-4
    /// LU. Downstream intro, credits and recap times do not use loudness.
    static let loudnessLu = 0.05
}

private struct BiquadDelay {
    var x1 = 0.0
    var x2 = 0.0
    var y1 = 0.0
    var y2 = 0.0
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
