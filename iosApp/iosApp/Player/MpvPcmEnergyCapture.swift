import Foundation
import QuartzCore
import Libmpv
import ComposeApp

/// Decodes media with a second, headless libmpv (`vo=null`, `ao=pcm`) and turns
/// that WAV into RMS energy. AVAssetReader cannot open Matroska.
///
/// libmpv 0.41 rejects inline `ao` suboptions (`pcm:file=...`). The pcm writer is
/// configured with separate `ao`, `ao-pcm-file`, and `ao-pcm-waveheader` options.
/// ao=pcm writes a WAVE_FORMAT_EXTENSIBLE file and only creates it once the
/// audio output starts, so an empty file plus a warn-only log used to surface
/// as "timed out opening media" with no mpv text.
final class MpvPcmEnergyCapture {
    var onHardFailure: ((String) -> Void)?

    private let stateLock = NSLock()
    private var userStop = false
    private var started = false
    private var finishedSamples: [ComposeApp.AudioEnergySample] = []
    private var storedFailureReason = ""
    private var wavURL: URL?
    private var startTimeMs: Int64 = 0
    private var doneSemaphore = DispatchSemaphore(value: 0)
    private var didReportHardFailure = false

    func start(
        urlString: String,
        headers: [String: String],
        audioTrackId: Int?,
        startTimeMs: Int64,
        durationMs: Int64 = 30_000,
        cacheFile: URL? = nil,
        cacheOriginMs: Int64 = 0,
        cacheDurationMs: Int64 = 0
    ) {
        stop()
        stateLock.lock()
        userStop = false
        started = true
        finishedSamples = []
        storedFailureReason = ""
        didReportHardFailure = false
        self.startTimeMs = startTimeMs
        doneSemaphore = DispatchSemaphore(value: 0)
        let wavURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("nuvio-autosync-\(UUID().uuidString).wav")
        self.wavURL = wavURL
        stateLock.unlock()

        let semaphore = doneSemaphore
        Thread { [weak self] in
            defer { semaphore.signal() }
            self?.run(
                urlString: urlString,
                headers: headers,
                audioTrackId: audioTrackId,
                startTimeMs: startTimeMs,
                durationMs: durationMs,
                wavURL: wavURL,
                cacheFile: cacheFile,
                cacheOriginMs: cacheOriginMs,
                cacheDurationMs: cacheDurationMs
            )
        }.start()
    }

    func stop() -> [ComposeApp.AudioEnergySample] {
        stateLock.lock()
        let wasStarted = started
        userStop = true
        let semaphore = doneSemaphore
        stateLock.unlock()
        if wasStarted {
            _ = semaphore.wait(timeout: .now() + 10)
        }
        stateLock.lock()
        started = false
        let samples = finishedSamples
        stateLock.unlock()
        return samples
    }

    func capturedDurationMs() -> Int64 {
        stateLock.lock()
        let samples = finishedSamples
        let start = startTimeMs
        let url = wavURL
        stateLock.unlock()
        if let last = samples.last {
            return max(0, last.timestampMs - start)
        }
        guard let url else { return 0 }
        return AudioEnergyWave.capturedDurationMs(at: url)
    }

    func failureReason() -> String {
        stateLock.lock()
        defer { stateLock.unlock() }
        return storedFailureReason
    }

    private struct Attempt {
        let urlString: String
        let headers: [String: String]
        let audioTrackId: Int?
        let decodeStartMs: Int64
        let timestampOriginMs: Int64
        let durationMs: Int64
        let local: Bool
        let label: String
    }

    private func run(
        urlString: String,
        headers: [String: String],
        audioTrackId: Int?,
        startTimeMs: Int64,
        durationMs: Int64,
        wavURL: URL,
        cacheFile: URL?,
        cacheOriginMs: Int64,
        cacheDurationMs: Int64
    ) {
        defer {
            try? FileManager.default.removeItem(at: wavURL)
            if let cacheFile {
                try? FileManager.default.removeItem(at: cacheFile)
            }
            stateLock.lock()
            self.wavURL = nil
            stateLock.unlock()
        }

        var attempts: [Attempt] = []
        // Lines on screen start before the tap. A dump that begins at the
        // playhead has no energy under those cues, and the matcher reports
        // no dialogue. Prefer audio that already contains them.
        let coveredStartMs = max(0, startTimeMs - 20_000)
        let cacheCoversLines = cacheFile != nil && cacheOriginMs + 1_000 < startTimeMs
        if let cacheFile, cacheCoversLines {
            attempts.append(cacheAttempt(
                cacheFile: cacheFile,
                cacheOriginMs: cacheOriginMs,
                cacheDurationMs: cacheDurationMs
            ))
        }
        attempts.append(Attempt(
            urlString: urlString,
            headers: headers,
            audioTrackId: audioTrackId,
            decodeStartMs: coveredStartMs,
            timestampOriginMs: coveredStartMs,
            durationMs: max(durationMs, startTimeMs - coveredStartMs + 5_000),
            local: isLocal(urlString),
            label: cacheFile == nil ? "url" : "url-after-cache"
        ))
        if let cacheFile, !cacheCoversLines {
            attempts.append(cacheAttempt(
                cacheFile: cacheFile,
                cacheOriginMs: cacheOriginMs,
                cacheDurationMs: cacheDurationMs
            ))
        }

        var details: [String] = []
        for attempt in attempts {
            if isUserStopRequested() { break }
            let decoded = decode(attempt, wavURL: wavURL)
            if !decoded.samples.isEmpty {
                let peak = decoded.samples.map(\.energy).max() ?? 0
                InAppLogBridge.shared.info(
                    tag: "MPV/iOS/AudioCapture/PCM",
                    message: "Captured N=\(decoded.samples.count) peak=\(String(format: "%.4f", peak)) via \(attempt.label)"
                )
                finish(samples: decoded.samples, failure: "", hard: false)
                return
            }
            if !decoded.detail.isEmpty {
                details.append(decoded.detail)
            }
        }

        let chosen: String
        if details.count >= 2 {
            chosen = "cache-miss; \(details[details.count - 1])"
        } else {
            chosen = details.last ?? ""
        }
        let line = PcmCaptureDiagnostic.failureLine(
            log: chosen,
            endError: nil,
            snapshot: "mpv logged nothing; wav=0B"
        )
        finish(samples: [], failure: line, hard: !isUserStopRequested())
    }

    private func decode(_ attempt: Attempt, wavURL: URL) -> (samples: [ComposeApp.AudioEnergySample], detail: String) {
        try? FileManager.default.removeItem(at: wavURL)
        FileManager.default.createFile(atPath: wavURL.path, contents: Data())

        guard let created = mpv_create() else {
            return ([], "mpv_create failed")
        }
        var destroyed = false
        defer {
            if !destroyed {
                mpv_terminate_destroy(created)
            }
        }

        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture/PCM",
            message: "Using headless mpv ao-pcm-file (\(attempt.label))"
        )
        let wavPath = wavURL.path
        if let failure = rejection(of: created, option: "ao-pcm-file", value: wavPath) {
            return ([], failure)
        }
        if let failure = rejection(of: created, option: "ao-pcm-waveheader", value: "yes") {
            return ([], failure)
        }
        if let failure = rejection(of: created, option: "ao", value: "pcm") {
            return ([], failure)
        }
        setOption(created, "vo", "null")
        setOption(created, "vid", "no")
        setOption(created, "sid", "no")
        setOption(created, "audio-display", "no")
        setOption(created, "config", "no")
        setOption(created, "load-scripts", "no")
        setOption(created, "terminal", "no")
        setOption(created, "input-default-bindings", "no")
        setOption(created, "input-vo-keyboard", "no")
        setOption(created, "audio-format", "s16")
        setOption(created, "audio-samplerate", "48000")
        setOption(created, "audio-channels", "stereo")
        setOption(created, "audio-fallback-to-null", "no")
        setOption(created, "hr-seek", "no")
        setOption(created, "pause", "no")
        setOption(created, "keep-open", "no")
        setOption(created, "cache-pause", "no")
        setOption(created, "network-timeout", attempt.local ? "8" : "18")
        setOption(created, "demuxer-max-bytes", "\(32 * 1024 * 1024)")
        setOption(
            created,
            "demuxer-lavf-o",
            "protocol_whitelist=[file,crypto,data,http,https,tcp,tls]"
        )
        let scheme = URL(string: attempt.urlString)?.scheme?.lowercased() ?? ""
        setOption(created, "ytdl", scheme == "ytdl" ? "yes" : "no")
        let startSeconds = String(format: "%.3f", Double(attempt.decodeStartMs) / 1000.0)
        let lengthSeconds = String(format: "%.3f", Double(attempt.durationMs + 1_000) / 1000.0)
        if let failure = rejection(of: created, option: "start", value: startSeconds) {
            return ([], failure)
        }
        setOption(created, "length", lengthSeconds)
        if let audioTrackId = attempt.audioTrackId, audioTrackId > 0 {
            setOption(created, "aid", "\(audioTrackId)")
        }
        applyHeaders(created, attempt.headers)
        mpv_request_log_messages(created, "info")

        let initStatus = mpv_initialize(created)
        if initStatus < 0 {
            return ([], "initialize \(String(cString: mpv_error_string(initStatus)))")
        }
        if let failure = propertyRejection(created, "ao", "pcm")
            ?? propertyRejection(created, "ao-pcm-file", wavPath)
            ?? propertyRejection(created, "ao-pcm-waveheader", "yes") {
            return ([], failure)
        }
        if let actual = propertyString(created, "ao-pcm-file"),
           !actual.isEmpty,
           !actual.contains((wavPath as NSString).lastPathComponent) {
            return ([], "ao-pcm-file is \(actual)")
        }

        var loadArgs: [UnsafePointer<CChar>?] = [
            UnsafePointer(strdup("loadfile")),
            UnsafePointer(strdup(attempt.urlString)),
            UnsafePointer(strdup("replace")),
            nil
        ]
        defer {
            for pointer in loadArgs where pointer != nil {
                free(UnsafeMutablePointer(mutating: pointer!))
            }
        }
        let loadStatus = mpv_command(created, &loadArgs)
        if loadStatus < 0 {
            return ([], "loadfile \(String(cString: mpv_error_string(loadStatus)))")
        }

        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture/PCM",
            message: "Headless decode started at \(attempt.decodeStartMs)ms via \(attempt.label)"
        )

        let openedAt = CACurrentMediaTime()
        let openDeadline: CFTimeInterval = attempt.local ? 8 : 22
        var endError: String?
        var lastWarn = ""
        var playbackEnded = false
        var latchedSampleZero = false
        var stampOriginMs = attempt.timestampOriginMs
        while !isUserStopRequested() && !playbackEnded {
            let captured = AudioEnergyWave.capturedDurationMs(at: wavURL)
            if !latchedSampleZero {
                latchedSampleZero = latchSampleZero(
                    created,
                    attempt: attempt,
                    capturedMs: captured,
                    stampOriginMs: &stampOriginMs
                )
            }
            if captured >= attempt.durationMs {
                break
            }
            if captured == 0, CACurrentMediaTime() - openedAt > openDeadline {
                break
            }
            guard let event = mpv_wait_event(created, 0.2) else { break }
            switch event.pointee.event_id {
            case MPV_EVENT_NONE:
                continue
            case MPV_EVENT_LOG_MESSAGE:
                if let message = event.pointee.data {
                    let log = UnsafeMutablePointer<mpv_event_log_message>(OpaquePointer(message))
                    let level = String(cString: log.pointee.level).trimmingCharacters(in: .whitespacesAndNewlines)
                    let prefix = String(cString: log.pointee.prefix).trimmingCharacters(in: .whitespacesAndNewlines)
                    let text = String(cString: log.pointee.text).trimmingCharacters(in: .whitespacesAndNewlines)
                    if !text.isEmpty, level == "warn" || level == "error" || level == "fatal" {
                        lastWarn = String("\(prefix): \(text)".prefix(140))
                    }
                }
            case MPV_EVENT_END_FILE:
                if let data = event.pointee.data {
                    let endFile = UnsafePointer<mpv_event_end_file>(OpaquePointer(data)).pointee
                    if endFile.reason == MPV_END_FILE_REASON_ERROR {
                        endError = "end-file \(String(cString: mpv_error_string(endFile.error)))"
                    }
                }
                playbackEnded = true
            case MPV_EVENT_SHUTDOWN:
                playbackEnded = true
            default:
                break
            }
        }

        if !latchedSampleZero {
            let captured = AudioEnergyWave.capturedDurationMs(at: wavURL)
            _ = latchSampleZero(
                created,
                attempt: attempt,
                capturedMs: captured,
                stampOriginMs: &stampOriginMs,
                force: true
            )
        }
        let snapshot = playbackSnapshot(created, wavURL: wavURL)
        mpv_terminate_destroy(created)
        destroyed = true
        let points = AudioEnergyWave.energySamples(from: wavURL, startTimeMs: stampOriginMs)
        let samples = points.map {
            ComposeApp.AudioEnergySample(timestampMs: $0.timestampMs, energy: $0.energy)
        }
        if !samples.isEmpty {
            return (samples, "")
        }
        if !lastWarn.isEmpty {
            return ([], lastWarn)
        }
        if let endError, !endError.isEmpty {
            return ([], endError)
        }
        return ([], snapshot)
    }

    private func cacheAttempt(
        cacheFile: URL,
        cacheOriginMs: Int64,
        cacheDurationMs: Int64
    ) -> Attempt {
        Attempt(
            urlString: cacheFile.path,
            headers: [:],
            audioTrackId: nil,
            decodeStartMs: 0,
            timestampOriginMs: cacheOriginMs,
            durationMs: max(cacheDurationMs, 1_000),
            local: true,
            label: "cache"
        )
    }

    /// Logs dump origin against the first audio PTS. A multi-second gap means the
    /// wav's first frame is not the dump origin (the -7.2s miss). Stamp sample 0 there.
    /// Cache packets keep their media PTS, so that frame is not `origin + PTS`.
    private func latchSampleZero(
        _ ctx: OpaquePointer,
        attempt: Attempt,
        capturedMs: Int64,
        stampOriginMs: inout Int64,
        force: Bool = false
    ) -> Bool {
        guard capturedMs >= 1_000 || force else { return false }
        let audioPts = propertyDouble(ctx, "audio-pts")
        let timePos = propertyDouble(ctx, "time-pos")
        let pts = audioPts ?? timePos
        guard let pts, pts.isFinite, pts >= 0 else {
            if force {
                InAppLogBridge.shared.info(
                    tag: "MPV/iOS/AudioCapture/PCM",
                    message: "Dump origin \(attempt.timestampOriginMs)ms decodeStart \(attempt.decodeStartMs)ms first audio PTS unavailable via \(attempt.label)"
                )
            }
            return force
        }
        let ptsMs = Int64((pts * 1000.0).rounded())
        let firstFrameMs = ptsMs - capturedMs
        // A large negative gap is a PTS that has not caught up with the wav yet.
        if firstFrameMs < -300 {
            if force {
                InAppLogBridge.shared.info(
                    tag: "MPV/iOS/AudioCapture/PCM",
                    message: "Dump origin \(attempt.timestampOriginMs)ms decodeStart \(attempt.decodeStartMs)ms first audio PTS \(firstFrameMs)ms still behind the wav via \(attempt.label)"
                )
            }
            return force
        }
        let stamped = pcmSampleZeroMs(
            timestampOriginMs: attempt.timestampOriginMs,
            decodeStartMs: attempt.decodeStartMs,
            firstFrameMs: firstFrameMs
        )
        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture/PCM",
            message: "Dump origin \(attempt.timestampOriginMs)ms decodeStart \(attempt.decodeStartMs)ms first audio PTS \(firstFrameMs)ms stamp \(stamped)ms captured \(capturedMs)ms via \(attempt.label)"
        )
        if stamped != stampOriginMs {
            stampOriginMs = stamped
            InAppLogBridge.shared.info(
                tag: "MPV/iOS/AudioCapture/PCM",
                message: "Stamping PCM from first audio PTS, origin \(stampOriginMs)ms"
            )
        }
        return true
    }

    private func propertyDouble(_ ctx: OpaquePointer, _ name: String) -> Double? {
        var data = 0.0
        guard mpv_get_property(ctx, name, MPV_FORMAT_DOUBLE, &data) >= 0 else { return nil }
        guard data.isFinite else { return nil }
        return data
    }

    private func playbackSnapshot(_ ctx: OpaquePointer, wavURL: URL) -> String {
        let ao = propertyString(ctx, "current-ao") ?? propertyString(ctx, "ao") ?? "unknown"
        let bytes = wavByteCount(wavURL)
        let idle = flag(ctx, "core-idle") ? 1 : 0
        let pause = flag(ctx, "pause") ? 1 : 0
        let cache = flag(ctx, "paused-for-cache") ? 1 : 0
        let seeking = flag(ctx, "seeking") ? 1 : 0
        let eof = flag(ctx, "eof-reached") ? 1 : 0
        return "mpv logged nothing; wav=\(bytes)B idle=\(idle) pause=\(pause) cache=\(cache) seek=\(seeking) eof=\(eof) ao=\(ao)"
    }

    private func wavByteCount(_ url: URL) -> Int64 {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
    }

    private func isLocal(_ urlString: String) -> Bool {
        let scheme = URL(string: urlString)?.scheme?.lowercased() ?? ""
        return scheme.isEmpty || scheme == "file"
    }

    private func finish(samples: [ComposeApp.AudioEnergySample], failure: String, hard: Bool) {
        stateLock.lock()
        finishedSamples = samples
        if !failure.isEmpty && storedFailureReason.isEmpty {
            storedFailureReason = String(failure.prefix(180))
        }
        let reason = storedFailureReason
        let stoppedByUser = userStop
        let report = hard && samples.isEmpty && !didReportHardFailure && !reason.isEmpty && !stoppedByUser
        if report {
            didReportHardFailure = true
        }
        let callback = onHardFailure
        stateLock.unlock()
        guard report else { return }
        DispatchQueue.main.async {
            callback?(reason)
        }
    }

    private func isUserStopRequested() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return userStop
    }

    /// On-screen fragment for a rejected option. Nil means mpv accepted it.
    /// Callers that surface this directly prefix `pcm:`; decode() passes it through failureLine.
    private func rejection(of ctx: OpaquePointer, option name: String, value: String) -> String? {
        let status = mpv_set_option_string(ctx, name, value)
        if status < 0 {
            let reason = String(cString: mpv_error_string(status))
            InAppLogBridge.shared.warn(
                tag: "MPV/iOS/AudioCapture/PCM",
                message: "option \(name) rejected: \(reason)"
            )
            return "\(name) rejected: \(reason)"
        }
        return nil
    }

    private func propertyRejection(_ ctx: OpaquePointer, _ name: String, _ value: String) -> String? {
        let status = mpv_set_property_string(ctx, name, value)
        if status < 0 {
            let reason = String(cString: mpv_error_string(status))
            return "\(name) property \(reason)"
        }
        return nil
    }

    private func propertyString(_ ctx: OpaquePointer, _ name: String) -> String? {
        guard let cstr = mpv_get_property_string(ctx, name) else { return nil }
        let value = String(cString: cstr)
        mpv_free(cstr)
        return value.isEmpty ? nil : value
    }

    private func flag(_ ctx: OpaquePointer, _ name: String) -> Bool {
        var data = CInt(0)
        guard mpv_get_property(ctx, name, MPV_FORMAT_FLAG, &data) >= 0 else { return false }
        return data > 0
    }

    @discardableResult
    private func setOption(_ ctx: OpaquePointer, _ name: String, _ value: String) -> Bool {
        rejection(of: ctx, option: name, value: value) == nil
    }

    private func applyHeaders(_ ctx: OpaquePointer, _ headers: [String: String]) {
        var remaining: [String: String] = [:]
        var userAgent: String?
        for (key, value) in headers {
            if key.caseInsensitiveCompare("User-Agent") == .orderedSame {
                userAgent = value
            } else if key.caseInsensitiveCompare("Range") != .orderedSame {
                remaining[key] = value
            }
        }
        if let userAgent, !userAgent.isEmpty {
            setOption(ctx, "user-agent", userAgent)
        }
        guard !remaining.isEmpty else { return }
        let serialized = remaining
            .sorted { $0.key.localizedCaseInsensitiveCompare($1.key) == .orderedAscending }
            .map { key, value in
                let escaped = value
                    .replacingOccurrences(of: "\\", with: "\\\\")
                    .replacingOccurrences(of: ",", with: "\\,")
                return "\(key): \(escaped)"
            }
            .joined(separator: ",")
        setOption(ctx, "http-header-fields", serialized)
    }
}

/// Media time of WAV sample 0.
///
/// A URL decode seeks to `decodeStartMs`, which is also the timestamp origin, so a
/// late keyframe is `origin + (firstFrame - decodeStart)`. A cache dump seeks at 0
/// in a file whose packets still carry media PTS. Adding the dump origin on top of
/// that PTS places the envelope one playhead later than the cues.
func pcmSampleZeroMs(timestampOriginMs: Int64, decodeStartMs: Int64, firstFrameMs: Int64) -> Int64 {
    if timestampOriginMs == decodeStartMs {
        let seekGap = firstFrameMs - decodeStartMs
        if seekGap >= 1_000 {
            return timestampOriginMs + seekGap
        }
        return timestampOriginMs
    }
    let fromDecode = abs(firstFrameMs - decodeStartMs)
    let fromOrigin = abs(firstFrameMs - timestampOriginMs)
    if fromOrigin <= fromDecode {
        return firstFrameMs
    }
    let seekGap = firstFrameMs - decodeStartMs
    if seekGap >= 1_000 {
        return timestampOriginMs + seekGap
    }
    return timestampOriginMs
}
