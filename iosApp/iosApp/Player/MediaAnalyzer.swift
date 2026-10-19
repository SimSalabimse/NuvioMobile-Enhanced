import Accelerate
import ComposeApp
import Foundation
import UIKit
import Libavcodec
import Libavformat
import Libavutil

/// On-device analysis of a downloaded file with the FFmpeg that ships in the player engine
/// (AVAssetReader cannot open Matroska). One decode pass over the main audio track produces:
///  - speech energy: RMS per 100 ms of the downmix, for subtitle-to-audio sync;
///  - EBU R128 / ITU BS.1770 integrated loudness (K-weighting, 400 ms blocks, -70 LUFS absolute
///    and -10 LU relative gates), for loudness normalisation;
///  - Philips/Haitsma-style 32-bit audio sub-fingerprints every 100 ms of the first 10 and last
///    8 minutes, for intro/credits matching across the episodes of a season.
/// Video and subtitle packets are discarded by the demuxer. Cancellable at any packet.
final class MediaAnalyzer: NSObject, NuvioMediaAnalyzerBridge {
    private let queue = DispatchQueue(label: "nuvio.media-analyzer", qos: .utility)
    private let abort = MediaAnalyzerAbortFlag()

    func currentProgress() -> Double { MediaAnalyzerStatus.shared.progress }

    func lastFailure() -> String? { MediaAnalyzerStatus.shared.failure }

    private let fileLock = NSLock()
    private var runningFile: String?

    private func setRunningFile(_ value: String?) {
        fileLock.lock(); runningFile = value; fileLock.unlock()
    }

    /// The file is about to be deleted: stop the run that reads it, so its handle closes.
    func releaseFile(path: String) {
        fileLock.lock(); let current = runningFile; fileLock.unlock()
        if HeldFileRegistry.samePath(current, path) { abort.set() }
    }

    func analyze(fileUri: String, onDone: @escaping (String?) -> Void) {
        abort.reset()
        setRunningFile(fileUri)
        MediaAnalyzerStatus.shared.begin()
        let flag = abort
        queue.async {
            let result = MediaAnalysisRun(fileUri: fileUri, abort: flag).run()
            self.setRunningFile(nil)
            if result == nil, MediaAnalyzerStatus.shared.failure == nil {
                MediaAnalyzerStatus.shared.fail(flag.isSet ? "Stopped" : "Could not analyse the audio")
            }
            DispatchQueue.main.async { onDone(result) }
        }
    }

    func makeThumbnails(
        fileUri: String,
        outputPrefix: String,
        durationMs: Int64,
        intervalMs: Int64,
        tileWidth: Int32,
        onDone: @escaping (String?) -> Void
    ) {
        abort.reset()
        setRunningFile(fileUri)
        MediaAnalyzerStatus.shared.begin()
        let flag = abort
        queue.async {
            defer { self.setRunningFile(nil) }
            let result = ThumbnailSheetBuilder(
                fileUri: fileUri,
                outputPrefix: outputPrefix,
                durationMs: durationMs,
                intervalMs: intervalMs,
                tileWidth: Int(tileWidth),
                abort: flag
            ).run()
            if result == nil, MediaAnalyzerStatus.shared.failure == nil {
                MediaAnalyzerStatus.shared.fail(flag.isSet ? "Stopped" : "Could not make previews")
            }
            DispatchQueue.main.async { onDone(result) }
        }
    }

    /// Fingerprints [startMs, endMs] of another episode's stream at [url]; the audio is decoded in
    /// memory only (nothing is written to disk). [onDone] gets `{"startMs","stepMs","fingerprint"}`.
    func fingerprintClip(
        url: String,
        headers: [String: String],
        startMs: Int64,
        endMs: Int64,
        onDone: @escaping (String?) -> Void
    ) {
        abort.reset()
        let flag = abort
        queue.async {
            let result = ClipFingerprintRun(url: url, headers: headers, startMs: startMs, endMs: endMs, abort: flag).run()
            DispatchQueue.main.async { onDone(result) }
        }
    }

    func cancel() {
        abort.set()
    }
}

/// Scrub-preview thumbnails of a downloaded file: one keyframe every `intervalMs`, drawn into
/// JPEG contact sheets of 10 x 10 tiles (`<outputPrefix><n>.jpg`). Uses the same FFmpeg keyframe
/// decoder as the live seek preview, on the local file.
private final class ThumbnailSheetBuilder {
    static let columns = 10
    static let rows = 10

    let fileUri: String
    let outputPrefix: String
    let durationMs: Int64
    let intervalMs: Int64
    let tileWidth: Int
    let abort: MediaAnalyzerAbortFlag

    init(fileUri: String, outputPrefix: String, durationMs: Int64, intervalMs: Int64, tileWidth: Int, abort: MediaAnalyzerAbortFlag) {
        self.fileUri = fileUri
        self.outputPrefix = outputPrefix
        self.durationMs = durationMs
        self.intervalMs = max(1000, intervalMs)
        self.tileWidth = max(64, tileWidth)
        self.abort = abort
    }

    func run() -> String? {
        let path = URL(string: fileUri)?.path ?? fileUri.replacingOccurrences(of: "file://", with: "")
        guard durationMs > 0, FileManager.default.fileExists(atPath: path) else {
            MediaAnalyzerStatus.shared.fail("File missing for previews")
            return nil
        }
        let generator = FFmpegSeekPreviewGenerator(url: path, headers: [:])
        defer { generator.close() }
        let count = Int(max(1, durationMs / intervalMs))
        let perSheet = Self.columns * Self.rows
        let sheets = (count + perSheet - 1) / perSheet
        var tileHeight = 0
        var written = 0
        var lastTile: UIImage?
        for sheet in 0..<sheets {
            if abort.isSet { removeSheets(upTo: sheet); return nil }
            var tiles: [UIImage?] = []
            let first = sheet * perSheet
            let last = min(count, first + perSheet)
            for index in first..<last {
                if abort.isSet { removeSheets(upTo: sheet); return nil }
                MediaAnalyzerThrottle.yield(abort: abort)
                MediaAnalyzerStatus.shared.set(Double(index) / Double(count))
                let positionMs = min(durationMs - 1000, Int64(index) * intervalMs + intervalMs / 2)
                let image: UIImage? = autoreleasepool {
                    guard let data = generator.frameJpeg(positionMs: max(0, positionMs), maxWidth: Int32(tileWidth)) else { return nil }
                    return UIImage(data: data)
                }
                if tileHeight == 0, let image, image.size.width > 0 {
                    tileHeight = max(2, Int((Double(tileWidth) * Double(image.size.height) / Double(image.size.width)).rounded()))
                }
                // A frame that cannot be decoded repeats the previous one.
                tiles.append(image ?? lastTile)
                if image != nil { lastTile = image }
            }
            if tileHeight == 0 { return nil }
            let size = CGSize(width: Self.columns * tileWidth, height: Self.rows * tileHeight)
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            format.opaque = true
            let renderer = UIGraphicsImageRenderer(size: size, format: format)
            let data = renderer.jpegData(withCompressionQuality: 0.6) { context in
                UIColor.black.setFill()
                context.fill(CGRect(origin: .zero, size: size))
                for (offset, tile) in tiles.enumerated() {
                    guard let tile else { continue }
                    let column = offset % Self.columns
                    let row = offset / Self.columns
                    tile.draw(in: CGRect(x: column * tileWidth, y: row * tileHeight, width: tileWidth, height: tileHeight))
                }
            }
            do {
                try data.write(to: URL(fileURLWithPath: "\(outputPrefix)\(sheet).jpg"), options: .atomic)
            } catch {
                removeSheets(upTo: sheet)
                return nil
            }
            written = sheet + 1
        }
        let json: [String: Any] = [
            "count": count,
            "tileWidth": tileWidth,
            "tileHeight": tileHeight,
            "columns": Self.columns,
            "rows": Self.rows,
            "sheets": written,
        ]
        guard let out = try? JSONSerialization.data(withJSONObject: json) else { return nil }
        return String(data: out, encoding: .utf8)
    }

    private func removeSheets(upTo sheet: Int) {
        for index in 0...sheet {
            try? FileManager.default.removeItem(atPath: "\(outputPrefix)\(index).jpg")
        }
    }
}

/// Progress and last failure of the running analyzer call, read by Kotlin for the status UI.
final class MediaAnalyzerStatus {
    static let shared = MediaAnalyzerStatus()
    private let lock = NSLock()
    private var _progress: Double = -1
    private var _failure: String?

    var progress: Double { lock.lock(); defer { lock.unlock() }; return _progress }
    var failure: String? { lock.lock(); defer { lock.unlock() }; return _failure }

    func begin() { lock.lock(); _progress = 0; _failure = nil; lock.unlock() }
    func set(_ value: Double) { lock.lock(); _progress = min(1, max(0, value)); lock.unlock() }
    func fail(_ reason: String) { lock.lock(); _failure = reason; lock.unlock() }
}

private final class MediaAnalyzerAbortFlag {
    private let lock = NSLock()
    private var value = false
    var isSet: Bool { lock.lock(); defer { lock.unlock() }; return value }
    func set() { lock.lock(); value = true; lock.unlock() }
    func reset() { lock.lock(); value = false; lock.unlock() }
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

private final class MediaAnalysisRun {
    static let stepMs = 100
    static let headMs: Int64 = 10 * 60_000
    static let tailMs: Int64 = 8 * 60_000
    static let fingerprintRate = 6000.0

    private let fileUri: String
    private let abort: MediaAnalyzerAbortFlag

    init(fileUri: String, abort: MediaAnalyzerAbortFlag) {
        self.fileUri = fileUri
        self.abort = abort
    }

    func run() -> String? {
        let path: String
        if let url = URL(string: fileUri), url.isFileURL { path = url.path } else { path = fileUri }

        guard let allocated = avformat_alloc_context() else { return nil }
        allocated.pointee.interrupt_callback.callback = { opaque in
            guard let opaque else { return 0 }
            return Unmanaged<MediaAnalyzerAbortFlag>.fromOpaque(opaque).takeUnretainedValue().isSet ? 1 : 0
        }
        allocated.pointee.interrupt_callback.opaque = Unmanaged.passUnretained(abort).toOpaque()
        var contextPointer: UnsafeMutablePointer<AVFormatContext>? = allocated
        guard avformat_open_input(&contextPointer, path, nil, nil) >= 0, let context = contextPointer else {
            log("open failed")
            MediaAnalyzerStatus.shared.fail("Could not open the file")
            return nil
        }
        defer { avformat_close_input(&contextPointer) }
        _ = avformat_find_stream_info(context, nil)

        let audioIndex = av_find_best_stream(context, AVMEDIA_TYPE_AUDIO, -1, -1, nil, 0)
        guard audioIndex >= 0, let stream = context.pointee.streams[Int(audioIndex)],
              let parameters = stream.pointee.codecpar,
              let decoder = avcodec_find_decoder(parameters.pointee.codec_id),
              let codecContext = avcodec_alloc_context3(decoder) else {
            log("no decodable audio")
            MediaAnalyzerStatus.shared.fail("No audio track it can decode")
            return nil
        }
        var codecPointer: UnsafeMutablePointer<AVCodecContext>? = codecContext
        defer { avcodec_free_context(&codecPointer) }
        guard avcodec_parameters_to_context(codecContext, parameters) >= 0,
              avcodec_open2(codecContext, decoder, nil) >= 0 else {
            log("decoder open failed")
            MediaAnalyzerStatus.shared.fail("Audio decoder failed to start")
            return nil
        }
        for index in 0..<Int(context.pointee.nb_streams) where index != Int(audioIndex) {
            context.pointee.streams[index]?.pointee.discard = AVDISCARD_ALL
        }

        let durationMs: Int64 = context.pointee.duration > 0 ? context.pointee.duration / 1000 : 0
        let tailStartMs: Int64 = durationMs > Self.tailMs ? durationMs - Self.tailMs : 0

        var accumulator: Accumulator?
        guard let packet = av_packet_alloc(), let frame = av_frame_alloc() else { return nil }
        var packetPointer: UnsafeMutablePointer<AVPacket>? = packet
        var framePointer: UnsafeMutablePointer<AVFrame>? = frame
        defer {
            av_packet_free(&packetPointer)
            av_frame_free(&framePointer)
        }

        func drainFrames() {
            while avcodec_receive_frame(codecContext, frame) >= 0 {
                let rate = Int(frame.pointee.sample_rate)
                let channels = Int(frame.pointee.ch_layout.nb_channels)
                if rate > 0, channels > 0 {
                    if accumulator == nil {
                        accumulator = Accumulator(rate: rate, channels: channels, durationMs: durationMs, tailStartMs: tailStartMs)
                    }
                    accumulator?.consume(frame: frame)
                }
                av_frame_unref(frame)
            }
        }

        var packetsSinceProgress = 0
        var packetsSinceThrottle = 0
        while !abort.isSet {
            // Playback comes first (PlaybackPriority in the shared code): while the player is open
            // the decode runs in small slices with short sleeps, and holds while it buffers.
            packetsSinceThrottle += 1
            if packetsSinceThrottle >= 64 {
                packetsSinceThrottle = 0
                MediaAnalyzerThrottle.yield(abort: abort)
            }
            let read = av_read_frame(context, packet)
            if read < 0 { break }
            if packet.pointee.stream_index == audioIndex {
                if avcodec_send_packet(codecContext, packet) >= 0 { drainFrames() }
                packetsSinceProgress += 1
                if packetsSinceProgress >= 200, durationMs > 0, let acc = accumulator {
                    packetsSinceProgress = 0
                    MediaAnalyzerStatus.shared.set(Double(acc.decodedMs) / Double(durationMs))
                }
            }
            av_packet_unref(packet)
        }
        if abort.isSet {
            log("cancelled")
            return nil
        }
        _ = avcodec_send_packet(codecContext, nil)
        drainFrames()

        guard let acc = accumulator else { return nil }
        let measuredMs = durationMs > 0 ? durationMs : acc.decodedMs
        var output: [String: Any] = [
            "durationMs": measuredMs,
            "energyStepMs": Self.stepMs,
            "energy": Data(acc.energyLevels).base64EncodedString(),
            "fingerprintStepMs": Self.stepMs,
            "tailStartMs": tailStartMs,
        ]
        if let lufs = acc.integratedLoudness() { output["integratedLufs"] = lufs }
        if let head = Fingerprinter.fingerprint(samples: acc.headSamples, rate: acc.fingerprintRate) {
            output["head"] = head
        }
        if durationMs > 0, let tail = Fingerprinter.fingerprint(samples: acc.tailSamples, rate: acc.fingerprintRate) {
            output["tail"] = tail
        }
        guard let data = try? JSONSerialization.data(withJSONObject: output) else { return nil }
        log("done durationMs=\(measuredMs) steps=\(acc.energyLevels.count) head=\(acc.headSamples.count) tail=\(acc.tailSamples.count)")
        return String(data: data, encoding: .utf8)
    }

    private func log(_ message: String) {
        InAppLogBridge.shared.info(tag: "Analysis/iOS", message: message)
    }
}

/// One time window of a remote episode, fingerprinted like the head of a downloaded file.
private final class ClipFingerprintRun {
    private static let noPtsValue = Int64.min

    private let url: String
    private let headers: [String: String]
    private let startMs: Int64
    private let endMs: Int64
    private let abort: MediaAnalyzerAbortFlag

    init(url: String, headers: [String: String], startMs: Int64, endMs: Int64, abort: MediaAnalyzerAbortFlag) {
        self.url = url
        self.headers = headers
        self.startMs = startMs
        self.endMs = min(endMs, startMs + MediaAnalysisRun.headMs)
        self.abort = abort
    }

    func run() -> String? {
        guard endMs > startMs, let allocated = avformat_alloc_context() else { return nil }
        allocated.pointee.interrupt_callback.callback = { opaque in
            guard let opaque else { return 0 }
            return Unmanaged<MediaAnalyzerAbortFlag>.fromOpaque(opaque).takeUnretainedValue().isSet ? 1 : 0
        }
        allocated.pointee.interrupt_callback.opaque = Unmanaged.passUnretained(abort).toOpaque()
        var options: OpaquePointer?
        defer { av_dict_free(&options) }
        _ = av_dict_set(&options, "rw_timeout", "10000000", 0)
        var headerLines = ""
        for (name, value) in headers {
            if name.caseInsensitiveCompare("User-Agent") == .orderedSame {
                _ = av_dict_set(&options, "user_agent", value, 0)
            } else {
                headerLines += "\(name): \(value)\r\n"
            }
        }
        if !headerLines.isEmpty { _ = av_dict_set(&options, "headers", headerLines, 0) }
        var contextPointer: UnsafeMutablePointer<AVFormatContext>? = allocated
        guard avformat_open_input(&contextPointer, url, nil, &options) >= 0, let context = contextPointer else {
            log("clip open failed")
            return nil
        }
        defer { avformat_close_input(&contextPointer) }
        _ = avformat_find_stream_info(context, nil)

        let audioIndex = av_find_best_stream(context, AVMEDIA_TYPE_AUDIO, -1, -1, nil, 0)
        guard audioIndex >= 0, let stream = context.pointee.streams[Int(audioIndex)],
              let parameters = stream.pointee.codecpar,
              let decoder = avcodec_find_decoder(parameters.pointee.codec_id),
              let codecContext = avcodec_alloc_context3(decoder) else { return nil }
        var codecPointer: UnsafeMutablePointer<AVCodecContext>? = codecContext
        defer { avcodec_free_context(&codecPointer) }
        guard avcodec_parameters_to_context(codecContext, parameters) >= 0,
              avcodec_open2(codecContext, decoder, nil) >= 0 else { return nil }
        for index in 0..<Int(context.pointee.nb_streams) where index != Int(audioIndex) {
            context.pointee.streams[index]?.pointee.discard = AVDISCARD_ALL
        }

        var seekTarget = startMs * 1000
        if context.pointee.start_time != Self.noPtsValue { seekTarget += context.pointee.start_time }
        guard av_seek_frame(context, -1, seekTarget, AVSEEK_FLAG_BACKWARD) >= 0 else {
            log("clip seek failed")
            return nil
        }
        avcodec_flush_buffers(codecContext)
        let timeBase = stream.pointee.time_base
        let streamStart = stream.pointee.start_time != Self.noPtsValue ? stream.pointee.start_time : 0

        var accumulator: Accumulator?
        var originMs: Int64 = -1
        var reachedEnd = false
        guard let packet = av_packet_alloc(), let frame = av_frame_alloc() else { return nil }
        var packetPointer: UnsafeMutablePointer<AVPacket>? = packet
        var framePointer: UnsafeMutablePointer<AVFrame>? = frame
        defer {
            av_packet_free(&packetPointer)
            av_frame_free(&framePointer)
        }
        while !abort.isSet, !reachedEnd, av_read_frame(context, packet) >= 0 {
            if packet.pointee.stream_index == audioIndex, avcodec_send_packet(codecContext, packet) >= 0 {
                while avcodec_receive_frame(codecContext, frame) >= 0 {
                    defer { av_frame_unref(frame) }
                    let pts = frame.pointee.best_effort_timestamp
                    let frameMs = pts == Self.noPtsValue ? nil : av_rescale_q(pts - streamStart, timeBase, AVRational(num: 1, den: 1000))
                    if let frameMs, frameMs > endMs { reachedEnd = true; break }
                    if originMs < 0 {
                        // Audio before the window (the seek lands on an earlier keyframe) is skipped.
                        guard let frameMs, frameMs >= startMs else { continue }
                        originMs = frameMs
                    }
                    let rate = Int(frame.pointee.sample_rate)
                    let channels = Int(frame.pointee.ch_layout.nb_channels)
                    guard rate > 0, channels > 0 else { continue }
                    if accumulator == nil {
                        accumulator = Accumulator(rate: rate, channels: channels, durationMs: 0, tailStartMs: 0)
                    }
                    accumulator?.consume(frame: frame)
                }
            }
            av_packet_unref(packet)
        }
        guard !abort.isSet, let acc = accumulator,
              let print = Fingerprinter.fingerprint(samples: acc.headSamples, rate: acc.fingerprintRate) else { return nil }
        let output: [String: Any] = ["startMs": originMs, "stepMs": MediaAnalysisRun.stepMs, "fingerprint": print]
        guard let data = try? JSONSerialization.data(withJSONObject: output) else { return nil }
        log("clip done startMs=\(originMs) decodedMs=\(acc.decodedMs)")
        return String(data: data, encoding: .utf8)
    }

    private func log(_ message: String) {
        InAppLogBridge.shared.info(tag: "Analysis/iOS", message: message)
    }
}

/// Everything measured from the decoded PCM in one pass.
private struct Accumulator {
    let rate: Int
    let channels: Int
    let tailStartSample: Int64
    let headEndSample: Int64
    let decimation: Int
    let fingerprintRate: Double

    var energyLevels: [UInt8] = []
    var headSamples: [Float] = []
    var tailSamples: [Float] = []

    private let stepSamples: Int
    private var sampleIndex: Int64 = 0
    private var stepSquares = 0.0
    private var stepCount = 0
    private var shelves: [Biquad]
    private var highPasses: [Biquad]
    private let weights: [Double]
    private var channelSquares: [Double]
    private var blockPowers: [Double] = []
    private var decimSum: Float = 0
    private var decimCount = 0

    var decodedMs: Int64 { sampleIndex * 1000 / Int64(rate) }

    init(rate: Int, channels: Int, durationMs: Int64, tailStartMs: Int64) {
        self.rate = rate
        self.channels = channels
        stepSamples = max(1, rate / 10)
        tailStartSample = durationMs > 0 ? tailStartMs * Int64(rate) / 1000 : Int64.max
        headEndSample = MediaAnalysisRun.headMs * Int64(rate) / 1000
        decimation = max(1, Int((Double(rate) / MediaAnalysisRun.fingerprintRate).rounded()))
        fingerprintRate = Double(rate) / Double(max(1, Int((Double(rate) / MediaAnalysisRun.fingerprintRate).rounded())))
        shelves = Array(repeating: Biquad.kShelf(rate: Double(rate)), count: channels)
        highPasses = Array(repeating: Biquad.kHighPass(rate: Double(rate)), count: channels)
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

    mutating func consume(frame: UnsafeMutablePointer<AVFrame>) {
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
    }

    private mutating func closeStep() {
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

/// 32-bit sub-fingerprints (Haitsma & Kalker): 33 log-spaced bands between 300 and 2000 Hz in a
/// ~0.34 s Hann window every 100 ms; bit m is the sign of the band-energy difference across
/// frequency, differenced across time.
private enum Fingerprinter {
    static let frameSize = 2048
    static let bands = 33

    static func fingerprint(samples: [Float], rate: Double) -> String? {
        let hop = Int((rate * 0.1).rounded())
        guard hop > 0, samples.count >= frameSize + hop else { return nil }
        let log2n = vDSP_Length(log2(Double(frameSize)))
        guard let setup = vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2)) else { return nil }
        defer { vDSP_destroy_fftsetup(setup) }

        var window = [Float](repeating: 0, count: frameSize)
        vDSP_hann_window(&window, vDSP_Length(frameSize), Int32(vDSP_HANN_NORM))
        let edges: [Int] = (0...bands).map { b in
            let freq = 300.0 * pow(2000.0 / 300.0, Double(b) / Double(bands))
            return min(frameSize / 2 - 1, max(1, Int((freq / rate * Double(frameSize)).rounded())))
        }

        var previous = [Float](repeating: 0, count: bands)
        var havePrevious = false
        var values: [UInt32] = []
        var frame = [Float](repeating: 0, count: frameSize)
        var real = [Float](repeating: 0, count: frameSize / 2)
        var imag = [Float](repeating: 0, count: frameSize / 2)
        var power = [Float](repeating: 0, count: frameSize / 2)
        var start = 0
        while start + frameSize <= samples.count {
            samples.withUnsafeBufferPointer { src in
                vDSP_vmul(src.baseAddress! + start, 1, window, 1, &frame, 1, vDSP_Length(frameSize))
            }
            real.withUnsafeMutableBufferPointer { realPtr in
                imag.withUnsafeMutableBufferPointer { imagPtr in
                    var split = DSPSplitComplex(realp: realPtr.baseAddress!, imagp: imagPtr.baseAddress!)
                    frame.withUnsafeBufferPointer { framePtr in
                        framePtr.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: frameSize / 2) {
                            vDSP_ctoz($0, 2, &split, 1, vDSP_Length(frameSize / 2))
                        }
                    }
                    vDSP_fft_zrip(setup, &split, 1, log2n, FFTDirection(FFT_FORWARD))
                    vDSP_zvmags(&split, 1, &power, 1, vDSP_Length(frameSize / 2))
                }
            }
            var energies = [Float](repeating: 0, count: bands)
            for b in 0..<bands {
                var sum: Float = 0
                var bin = edges[b]
                repeat {
                    sum += power[bin]
                    bin += 1
                } while bin < edges[b + 1]
                energies[b] = sum
            }
            if havePrevious {
                var bits: UInt32 = 0
                for m in 0..<(bands - 1) {
                    let now = energies[m] - energies[m + 1]
                    let before = previous[m] - previous[m + 1]
                    if now - before > 0 { bits |= (1 << UInt32(m)) }
                }
                values.append(bits)
            } else {
                // The first frame has no predecessor; keep the timeline aligned with a zero word.
                values.append(0)
            }
            previous = energies
            havePrevious = true
            start += hop
        }
        var data = Data(capacity: values.count * 4)
        for value in values {
            var little = value.littleEndian
            withUnsafeBytes(of: &little) { data.append(contentsOf: $0) }
        }
        return data.base64EncodedString()
    }
}

enum NuvioMediaAnalyzerRegistration {
    static let shared = MediaAnalyzer()

    static func register() {
        NuvioMediaAnalyzerRegistry.shared.register(analyzer: shared)
    }
}


/// Set from Kotlin (setAnalysisThrottleLevel): 0 full speed, 1 playing, 2 the player is buffering.
private enum MediaAnalyzerThrottle {
    private static let key = "NuvioAnalysisThrottleLevel"

    static func yield(abort: MediaAnalyzerAbortFlag) {
        var level = UserDefaults.standard.integer(forKey: key)
        if level == 1 {
            usleep(25_000)
            return
        }
        // Hold while the player struggles, but never for more than a minute at a time, so a level
        // that is never cleared cannot stall the queue.
        var waited = 0
        while level >= 2 && !abort.isSet && waited < 240 {
            usleep(250_000)
            waited += 1
            level = UserDefaults.standard.integer(forKey: key)
        }
    }
}
