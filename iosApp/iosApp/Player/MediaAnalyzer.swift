import Accelerate
import ComposeApp
import Darwin
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
///  - Philips/Haitsma-style 32-bit audio sub-fingerprints every 100 ms of the first 25 and last
///    15 minutes, for intro/credits matching across the episodes of a season.
///    Keep in step with `HEAD_FINGERPRINT_MS` and `TAIL_FINGERPRINT_MS`.
/// Video stays discarded (`AVDISCARD_ALL` on every non-audio stream below). Black-frame ranges
/// are an optional Kotlin cue (`blackRanges`); measuring them here would decode video and blow
/// the ~18 s pass. On device the decision trace reports black=0. Silence and audio scene
/// changes still refine edges. Verify on a Mac: after one episode, Diagnostics shows a timings
/// line and four `segment` lines (intro, recap, credits, preview), and this file still sets
/// `AVDISCARD_ALL` on the non-audio streams.
/// Video and subtitle packets are discarded by the demuxer. Cancellable at any packet.
final class MediaAnalyzer: NSObject, NuvioMediaAnalyzerBridge {
    private let queue = DispatchQueue(label: "nuvio.media-analyzer", qos: .utility)
    /// Every queued or running job has its own flag, so starting a job never un-cancels another.
    private let flagLock = NSLock()
    private var liveFlags: [MediaAnalyzerAbortFlag] = []

    private func startJob() -> MediaAnalyzerAbortFlag {
        let flag = MediaAnalyzerAbortFlag()
        flagLock.lock(); liveFlags.append(flag); flagLock.unlock()
        return flag
    }

    private func endJob(_ flag: MediaAnalyzerAbortFlag) {
        flagLock.lock(); liveFlags.removeAll { $0 === flag }; flagLock.unlock()
    }

    private func abortAll() {
        flagLock.lock(); let flags = liveFlags; flagLock.unlock()
        flags.forEach { $0.set() }
    }

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
        if HeldFileRegistry.samePath(current, path) { abortAll() }
    }

    func analyze(fileUri: String, onDone: @escaping (String?) -> Void) {
        let flag = startJob()
        setRunningFile(fileUri)
        MediaAnalyzerStatus.shared.begin()
        queue.async {
            let result = MediaAnalysisRun(fileUri: fileUri, abort: flag).run()
            self.endJob(flag)
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
        let flag = startJob()
        setRunningFile(fileUri)
        MediaAnalyzerStatus.shared.begin()
        queue.async {
            defer { self.setRunningFile(nil); self.endJob(flag) }
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
        let flag = startJob()
        queue.async {
            let result = ClipFingerprintRun(url: url, headers: headers, startMs: startMs, endMs: endMs, abort: flag).run()
            self.endJob(flag)
            DispatchQueue.main.async { onDone(result) }
        }
    }

    func cancel() {
        abortAll()
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
                // No measured work window: keep the 25 ms floor while a video is playing.
                MediaAnalyzerThrottle.yield(abort: abort, workedNs: 0)
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

private final class MediaAnalysisRun {
    static let stepMs = 100
    static let headMs: Int64 = MediaAnalyzerSampleLoop.headWindowMs
    static let tailMs: Int64 = 15 * 60_000
    static let fingerprintRate = MediaAnalyzerSampleLoop.fingerprintTargetRate

    private let fileUri: String
    private let abort: MediaAnalyzerAbortFlag

    init(fileUri: String, abort: MediaAnalyzerAbortFlag) {
        self.fileUri = fileUri
        self.abort = abort
    }

    func run() -> String? {
        // Drop back to the queue's utility class when this job ends, including on failure,
        // so a later thumbnail on the same thread is not left elevated.
        defer { MediaAnalyzerQosName.restoreUtility() }
        let path: String
        if let url = URL(string: fileUri), url.isFileURL { path = url.path } else { path = fileUri }
        // Monotonic. Date() can jump; uptime does not.
        let startedNs = DispatchTime.now().uptimeNanoseconds
        var sampleLoopNs: UInt64 = 0
        var throttledNs: UInt64 = 0
        var throttleYields = 0

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
        let chaptersStartedNs = DispatchTime.now().uptimeNanoseconds
        let chapters = readChapters(context)
        let chaptersMs = Int((DispatchTime.now().uptimeNanoseconds &- chaptersStartedNs) / 1_000_000)

        var accumulator: MediaAnalyzerSampleLoop?
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
                        accumulator = MediaAnalyzerSampleLoop(rate: rate, channels: channels, durationMs: durationMs, tailStartMs: tailStartMs)
                    }
                    let sampleStartedNs = DispatchTime.now().uptimeNanoseconds
                    accumulator?.consume(frame: frame)
                    sampleLoopNs &+= DispatchTime.now().uptimeNanoseconds &- sampleStartedNs
                }
                av_frame_unref(frame)
            }
        }

        var packetsSinceProgress = 0
        var packetsSinceThrottle = 0
        // The queue is created at utility. Raise it only when no player session is open, then
        // read the thread so the timings line matches the class actually applied.
        MediaAnalyzerQosName.applyForCurrentSession()
        let qos = MediaAnalyzerQosName.current()
        let throttleLevelStart = MediaAnalyzerThrottle.currentLevel()
        var sliceStartedNs = DispatchTime.now().uptimeNanoseconds
        while !abort.isSet {
            // Playback comes first (PlaybackPriority in the shared code): while a video is
            // actually playing the decode sleeps at least as much as before. A pause, the end,
            // or no player session does not sleep. QoS follows the session on each slice.
            MediaAnalyzerQosName.applyForCurrentSession()
            packetsSinceThrottle += 1
            if packetsSinceThrottle >= MediaAnalyzerThrottle.packetsPerCheck {
                packetsSinceThrottle = 0
                let workedNs = DispatchTime.now().uptimeNanoseconds &- sliceStartedNs
                throttleYields += 1
                throttledNs &+= MediaAnalyzerThrottle.yield(abort: abort, workedNs: workedNs)
                sliceStartedNs = DispatchTime.now().uptimeNanoseconds
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
        let elapsedNs = DispatchTime.now().uptimeNanoseconds &- startedNs
        let decodeMs = Int((elapsedNs > sampleLoopNs ? elapsedNs - sampleLoopNs : 0) / 1_000_000)
        let sampleLoopMs = Int(sampleLoopNs / 1_000_000)
        // Chapter titles are classified in the shared Kotlin code. A part they already cover
        // skips that print. Energy, loudness and the other print are unchanged.
        var skipFingerprint = ""
        if !chapters.isEmpty,
           let chapterData = try? JSONSerialization.data(withJSONObject: chapters),
           let chapterJson = String(data: chapterData, encoding: .utf8) {
            skipFingerprint = ChapterSkipClassifier.shared.fingerprintSkip(chaptersJson: chapterJson, durationMs: measuredMs)
        }
        let skipHead = skipFingerprint.contains("head")
        let skipTail = skipFingerprint.contains("tail")
        let fingerprintStartedNs = DispatchTime.now().uptimeNanoseconds
        let headPrint = skipHead ? nil : Fingerprinter.fingerprint(samples: acc.headSamples, rate: acc.fingerprintRate)
        let tailPrint = (!skipTail && durationMs > 0) ? Fingerprinter.fingerprint(samples: acc.tailSamples, rate: acc.fingerprintRate) : nil
        let fingerprintMs = Int((DispatchTime.now().uptimeNanoseconds &- fingerprintStartedNs) / 1_000_000)
        var output: [String: Any] = [
            "durationMs": measuredMs,
            "energyStepMs": Self.stepMs,
            "energy": Data(acc.energyLevels).base64EncodedString(),
            "fingerprintStepMs": Self.stepMs,
            "tailStartMs": tailStartMs,
            "decodeMs": decodeMs,
            "sampleLoopMs": sampleLoopMs,
            "fingerprintMs": fingerprintMs,
            "chapters": chapters,
            "chaptersMs": chaptersMs,
            "throttledMs": Int(throttledNs / 1_000_000),
            "throttleLevelStart": throttleLevelStart,
            "throttleYields": throttleYields,
            "qos": qos,
        ]
        if let lufs = acc.integratedLoudness() { output["integratedLufs"] = lufs }
        if let headPrint { output["head"] = headPrint }
        if let tailPrint { output["tail"] = tailPrint }
        guard let data = try? JSONSerialization.data(withJSONObject: output) else { return nil }
        log("done durationMs=\(measuredMs) steps=\(acc.energyLevels.count) head=\(acc.headSamples.count) tail=\(acc.tailSamples.count)")
        return String(data: data, encoding: .utf8)
    }

    private func log(_ message: String) {
        InAppLogBridge.shared.info(tag: "Analysis/iOS", message: message)
    }

    /// Container chapters (title plus start/end in ms). Best-effort: a file with none, or a
    /// chapter FFmpeg did not fill in, contributes nothing and the audio fingerprints stay.
    private func readChapters(_ context: UnsafeMutablePointer<AVFormatContext>) -> [[String: Any]] {
        let count = Int(context.pointee.nb_chapters)
        if count <= 0 { return [] }
        guard let chapterList = context.pointee.chapters else { return [] }
        var rows: [[String: Any]] = []
        for index in 0..<min(count, 300) {
            guard let chapter = chapterList[index] else { continue }
            let startMs = chapterMillis(chapter.pointee.start, chapter.pointee.time_base)
            if startMs < 0 { continue }
            let endMs = chapterMillis(chapter.pointee.end, chapter.pointee.time_base)
            var row: [String: Any] = ["startMs": startMs, "endMs": endMs > startMs ? endMs : 0]
            if let title = chapterTitle(chapter) { row["title"] = title }
            rows.append(row)
        }
        return rows
    }

    private func chapterMillis(_ ticks: Int64, _ timeBase: AVRational) -> Int64 {
        if ticks < 0 || timeBase.den == 0 { return -1 }
        let ms = Double(ticks) * Double(timeBase.num) / Double(timeBase.den) * 1000.0
        if !ms.isFinite || ms < 0 || ms > Double(Int64.max) { return -1 }
        return Int64(ms.rounded())
    }

    private func chapterTitle(_ chapter: UnsafeMutablePointer<AVChapter>) -> String? {
        guard let entry = av_dict_get(chapter.pointee.metadata, "title", nil, 0) else { return nil }
        let title = String(cString: entry.pointee.value).trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? nil : title
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
        self.endMs = endMs
        self.abort = abort
    }

    func run() -> String? {
        guard endMs > startMs else { return nil }
        if endMs - startMs > 50_000 {
            // A longer known segment is not the first 50 seconds of that segment.
            return json(["windowComplete": false, "decodedFrames": 0, "decodedSamples": 0])
        }
        if abort.isSet { return nil }
        guard let allocated = avformat_alloc_context() else { return nil }
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
        // Open, find-stream, seek, and read all share this context's interrupt callback.
        // There is no whole-episode timeout. Cancellation and rw_timeout stop those calls.
        guard !abort.isSet, avformat_open_input(&contextPointer, url, nil, &options) >= 0, let context = contextPointer else {
            log("clip open failed")
            return nil
        }
        defer { avformat_close_input(&contextPointer) }
        if abort.isSet { return nil }
        _ = avformat_find_stream_info(context, nil)
        if abort.isSet { return nil }

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
        guard !abort.isSet, av_seek_frame(context, -1, seekTarget, AVSEEK_FLAG_BACKWARD) >= 0 else {
            log("clip seek failed")
            return nil
        }
        avcodec_flush_buffers(codecContext)
        let timeBase = stream.pointee.time_base
        let streamStart = stream.pointee.start_time != Self.noPtsValue ? stream.pointee.start_time : 0

        var accumulator: MediaAnalyzerSampleLoop?
        var originMs: Int64 = -1
        var reachedEnd = false
        var decodedFrames = 0
        var decodedSamples = 0
        var missingTimestamps = 0
        var stuckTimestamps = 0
        var lastTimestamp: Int64 = Self.noPtsValue
        let windowMs = max(Int64(1), endMs - startMs)
        let maxFrames = Int(windowMs / 10) + 200
        let maxSamples = maxFrames * 4_000
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
                    decodedFrames += 1
                    decodedSamples += Int(frame.pointee.nb_samples)
                    if decodedFrames > maxFrames || decodedSamples > maxSamples { reachedEnd = true; break }
                    let pts = frame.pointee.best_effort_timestamp
                    let frameMs = pts == Self.noPtsValue ? nil : av_rescale_q(pts - streamStart, timeBase, AVRational(num: 1, den: 1000))
                    guard let frameMs else {
                        missingTimestamps += 1
                        if missingTimestamps > 60 { reachedEnd = true; break }
                        continue
                    }
                    if frameMs < startMs {
                        // A bad seek that lands far before the window must not decode the prefix.
                        if startMs - frameMs > 2_000 { reachedEnd = true; break }
                        continue
                    }
                    if lastTimestamp != Self.noPtsValue && frameMs <= lastTimestamp {
                        stuckTimestamps += 1
                        if stuckTimestamps > 60 { reachedEnd = true; break }
                    } else {
                        stuckTimestamps = 0
                    }
                    lastTimestamp = frameMs
                    if frameMs > endMs { reachedEnd = true; break }
                    if originMs < 0 { originMs = frameMs }
                    let rate = Int(frame.pointee.sample_rate)
                    let channels = Int(frame.pointee.ch_layout.nb_channels)
                    guard rate > 0, channels > 0 else { continue }
                    if accumulator == nil {
                        accumulator = MediaAnalyzerSampleLoop(rate: rate, channels: channels, durationMs: 0, tailStartMs: 0)
                    }
                    accumulator?.consume(frame: frame)
                }
            }
            av_packet_unref(packet)
        }
        guard !abort.isSet, let acc = accumulator,
              let print = Fingerprinter.fingerprint(samples: acc.headSamples, rate: acc.fingerprintRate) else { return nil }
        let output: [String: Any] = [
            "startMs": originMs,
            "stepMs": MediaAnalysisRun.stepMs,
            "fingerprint": print,
            "decodedFrames": decodedFrames,
            "decodedSamples": decodedSamples,
            "windowComplete": true,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: output) else { return nil }
        log("clip done startMs=\(originMs) decodedMs=\(acc.decodedMs)")
        return String(data: data, encoding: .utf8)
    }

    private func log(_ message: String) {
        InAppLogBridge.shared.info(tag: "Analysis/iOS", message: message)
    }

    private func json(_ output: [String: Any]) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: output) else { return nil }
        return String(data: data, encoding: .utf8)
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


/// Launch-argument gate shared with Kotlin `isolateStorageKey`. A normal icon tap never sets it.
private enum NuvioAutomationDefaults {
    static func storeKey(_ raw: String) -> String {
        let arguments = ProcessInfo.processInfo.arguments
        let env = ProcessInfo.processInfo.environment["NUVIO_AUTOMATION"]?.lowercased()
        let active = arguments.contains("-NuvioAutomation") || env == "1" || env == "true"
        return active ? "nuvio_auto_\(raw)" : raw
    }
}

/// Set from Kotlin (setAnalysisThrottleLevel): 0 full speed, 1 a video is playing, 2 the player is buffering.
///
/// While a video is actually playing, one check sleeps at least [playingSleepFloorUs] (the previous
/// fixed 25 ms every [packetsPerCheck] packets). If that slice of work was longer than
/// [playingWorkBudgetMs], the sleep grows by [playingSleepPerBudgetMs] per budget window, so the
/// analyser is never hotter during playback than it was before. A shorter slice keeps the floor.
/// Level 0 (no player, or playback paused or ended) does not sleep. Level 2 is unchanged.
private enum MediaAnalyzerThrottle {
    private static let key = NuvioAutomationDefaults.storeKey("NuvioAnalysisThrottleLevel")

    /// Packets between throttle checks. Same slice as the previous loop.
    static let packetsPerCheck = 64
    /// Minimum sleep while a video is playing, once per [packetsPerCheck]. The old fixed sleep.
    static let playingSleepFloorUs: useconds_t = 25_000
    /// Work window that can earn extra sleep while a video is playing.
    static let playingWorkBudgetMs: UInt64 = 20
    /// Extra sleep per [playingWorkBudgetMs] of work, used only when it exceeds the floor.
    /// 25 ms of sleep per 20 ms of work. Not 5 ms: that duty cycle would be hotter than the floor
    /// on a short slice, and a playing video must not get a hotter analyser than before.
    static let playingSleepPerBudgetMs: UInt64 = 25
    /// Unchanged hold while the player is struggling (level 2).
    static let strainedSleepUs: useconds_t = 250_000
    /// Unchanged cap so a level that is never cleared cannot stall the queue (about a minute).
    static let strainedMaxWaits = 240

    static func currentLevel() -> Int {
        UserDefaults.standard.integer(forKey: key)
    }

    /// Sleeps when a playing or struggling player is holding the analyser back.
    /// [workedNs] is the time since the previous check, not including that check's sleep.
    /// Returns the nanoseconds spent inside the sleeps (0 when the level does not sleep).
    static func yield(abort: MediaAnalyzerAbortFlag, workedNs: UInt64) -> UInt64 {
        var sleptNs: UInt64 = 0
        var level = currentLevel()
        if level == 1 {
            sleptNs &+= sleepPlaying(workedNs: workedNs, abort: abort)
            return sleptNs
        }
        // Hold while the player struggles, but never for more than a minute at a time, so a level
        // that is never cleared cannot stall the queue.
        var waited = 0
        while level >= 2 && !abort.isSet && waited < strainedMaxWaits {
            sleptNs &+= measuredSleep(strainedSleepUs)
            waited += 1
            level = currentLevel()
        }
        return sleptNs
    }

    /// At least the old 25 ms. Longer work sleeps more, in slices `usleep` is known to honour.
    private static func sleepPlaying(workedNs: UInt64, abort: MediaAnalyzerAbortFlag) -> UInt64 {
        let workedMs = workedNs / 1_000_000
        let budgetMs = workedMs * playingSleepPerBudgetMs / playingWorkBudgetMs
        let floorMs = UInt64(playingSleepFloorUs) / 1_000
        var remainingUs = max(floorMs, budgetMs) * 1_000
        var sleptNs: UInt64 = 0
        while remainingUs > 0 && !abort.isSet {
            let slice = useconds_t(min(remainingUs, UInt64(strainedSleepUs)))
            sleptNs &+= measuredSleep(slice)
            remainingUs -= UInt64(slice)
        }
        return sleptNs
    }

    private static func measuredSleep(_ microseconds: useconds_t) -> UInt64 {
        let started = DispatchTime.now().uptimeNanoseconds
        usleep(microseconds)
        return DispatchTime.now().uptimeNanoseconds &- started
    }
}

/// Analyser-thread QoS. The queue is created at utility. The thread is raised to userInitiated
/// only while no player session is open (`NuvioAnalysisPlayerSessionOpen`), and put back when one is.
private enum MediaAnalyzerQosName {
    private static let sessionKey = NuvioAutomationDefaults.storeKey("NuvioAnalysisPlayerSessionOpen")
    private static var lastApplied: qos_class_t = QOS_CLASS_UNSPECIFIED

    static func applyForCurrentSession() {
        let target: qos_class_t = sessionOpen() ? QOS_CLASS_UTILITY : QOS_CLASS_USER_INITIATED
        guard target != lastApplied else { return }
        if pthread_set_qos_class_self_np(target, 0) == 0 {
            lastApplied = target
        }
    }

    static func restoreUtility() {
        if pthread_set_qos_class_self_np(QOS_CLASS_UTILITY, 0) == 0 {
            lastApplied = QOS_CLASS_UTILITY
        }
    }

    static func current() -> String {
        let klass = qos_class_self()
        if klass == QOS_CLASS_USER_INTERACTIVE { return "userInteractive" }
        if klass == QOS_CLASS_USER_INITIATED { return "userInitiated" }
        if klass == QOS_CLASS_DEFAULT { return "default" }
        if klass == QOS_CLASS_UTILITY { return "utility" }
        if klass == QOS_CLASS_BACKGROUND { return "background" }
        if klass == QOS_CLASS_UNSPECIFIED { return "unspecified" }
        return "unknown"
    }

    private static func sessionOpen() -> Bool {
        UserDefaults.standard.bool(forKey: sessionKey)
    }
}
