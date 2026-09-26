import Foundation
import QuartzCore
import Libmpv
import ComposeApp

/// Decodes the playing URL with a second, headless libmpv (`vo=null`, `ao=pcm`) and
/// turns that WAV into RMS energy. AVAssetReader cannot open Matroska, and its
/// `isPlayable` flag is false for remote URLs until the asset keys load.
///
/// libmpv 0.41 rejects inline `ao` suboptions (`pcm:file=...`). The pcm writer is
/// configured with separate `ao`, `ao-pcm-file`, and `ao-pcm-waveheader` options.
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
        durationMs: Int64 = 30_000
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
                wavURL: wavURL
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

    private func run(
        urlString: String,
        headers: [String: String],
        audioTrackId: Int?,
        startTimeMs: Int64,
        durationMs: Int64,
        wavURL: URL
    ) {
        defer {
            try? FileManager.default.removeItem(at: wavURL)
            stateLock.lock()
            self.wavURL = nil
            stateLock.unlock()
        }

        guard let created = mpv_create() else {
            finish(samples: [], failure: "pcm: mpv_create failed", hard: true)
            return
        }
        var destroyed = false
        defer {
            if !destroyed {
                mpv_terminate_destroy(created)
            }
        }

        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture/PCM",
            message: "Using headless mpv ao-pcm-file"
        )
        if let failure = rejection(of: created, option: "ao", value: "pcm") {
            finish(samples: [], failure: failure, hard: true)
            return
        }
        let wavPath = wavURL.path
        if rejection(of: created, option: "ao-pcm-file", value: wavPath) != nil {
            FileManager.default.createFile(atPath: wavPath, contents: Data())
            if let failure = rejection(of: created, option: "ao-pcm-file", value: wavPath) {
                finish(samples: [], failure: failure, hard: true)
                return
            }
        }
        if let failure = rejection(of: created, option: "ao-pcm-waveheader", value: "yes") {
            finish(samples: [], failure: failure, hard: true)
            return
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
        setOption(created, "hr-seek", "yes")
        setOption(created, "keep-open", "no")
        setOption(created, "cache-pause", "no")
        setOption(created, "network-timeout", "20")
        setOption(created, "demuxer-max-bytes", "\(32 * 1024 * 1024)")
        let scheme = URL(string: urlString)?.scheme?.lowercased() ?? ""
        setOption(created, "ytdl", scheme == "ytdl" ? "yes" : "no")
        let startSeconds = String(format: "%.3f", Double(startTimeMs) / 1000.0)
        let lengthSeconds = String(format: "%.3f", Double(durationMs) / 1000.0)
        if let failure = rejection(of: created, option: "start", value: startSeconds) {
            finish(samples: [], failure: failure, hard: true)
            return
        }
        setOption(created, "length", lengthSeconds)
        if let audioTrackId, audioTrackId > 0 {
            setOption(created, "aid", "\(audioTrackId)")
        }
        applyHeaders(created, headers)
        mpv_request_log_messages(created, "warn")

        let initStatus = mpv_initialize(created)
        if initStatus < 0 {
            finish(samples: [], failure: "pcm: initialize \(String(cString: mpv_error_string(initStatus)))", hard: true)
            return
        }

        var loadArgs: [UnsafePointer<CChar>?] = [
            UnsafePointer(strdup("loadfile")),
            UnsafePointer(strdup(urlString)),
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
            finish(samples: [], failure: "pcm: loadfile \(String(cString: mpv_error_string(loadStatus)))", hard: true)
            return
        }

        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture/PCM",
            message: "Headless decode started at \(startTimeMs)ms for \(scheme.isEmpty ? "no-scheme" : scheme) url"
        )

        let openedAt = CACurrentMediaTime()
        var endError: String?
        var lastWarn = ""
        var playbackEnded = false
        while !isUserStopRequested() && !playbackEnded {
            if AudioEnergyWave.capturedDurationMs(at: wavURL) >= durationMs {
                break
            }
            if AudioEnergyWave.capturedDurationMs(at: wavURL) == 0,
               CACurrentMediaTime() - openedAt > 12 {
                endError = lastWarn.isEmpty ? "timed out opening media" : lastWarn
                break
            }
            guard let event = mpv_wait_event(created, 0.2) else { break }
            switch event.pointee.event_id {
            case MPV_EVENT_NONE:
                continue
            case MPV_EVENT_LOG_MESSAGE:
                if let message = event.pointee.data {
                    let log = UnsafeMutablePointer<mpv_event_log_message>(OpaquePointer(message))
                    let text = String(cString: log.pointee.text).trimmingCharacters(in: .whitespacesAndNewlines)
                    if !text.isEmpty {
                        lastWarn = String(text.prefix(160))
                    }
                }
            case MPV_EVENT_END_FILE:
                if let data = event.pointee.data {
                    let endFile = UnsafePointer<mpv_event_end_file>(OpaquePointer(data)).pointee
                    if endFile.reason == MPV_END_FILE_REASON_ERROR {
                        endError = String(cString: mpv_error_string(endFile.error))
                    }
                }
                playbackEnded = true
            case MPV_EVENT_SHUTDOWN:
                playbackEnded = true
            default:
                break
            }
        }

        mpv_terminate_destroy(created)
        destroyed = true
        let points = AudioEnergyWave.energySamples(from: wavURL, startTimeMs: startTimeMs)
        let samples = points.map {
            ComposeApp.AudioEnergySample(timestampMs: $0.timestampMs, energy: $0.energy)
        }
        if samples.isEmpty {
            let reason = endError ?? (lastWarn.isEmpty ? "decoded no audio" : lastWarn)
            finish(samples: [], failure: "pcm: \(reason)", hard: !isUserStopRequested())
        } else {
            let peak = points.map(\.energy).max() ?? 0
            InAppLogBridge.shared.info(
                tag: "MPV/iOS/AudioCapture/PCM",
                message: "Captured N=\(samples.count) peak=\(String(format: "%.4f", peak))"
            )
            finish(samples: samples, failure: "", hard: false)
        }
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

    /// On-screen line for a rejected option. Nil means mpv accepted it.
    private func rejection(of ctx: OpaquePointer, option name: String, value: String) -> String? {
        let status = mpv_set_option_string(ctx, name, value)
        if status < 0 {
            let reason = String(cString: mpv_error_string(status))
            InAppLogBridge.shared.warn(
                tag: "MPV/iOS/AudioCapture/PCM",
                message: "option \(name) rejected: \(reason)"
            )
            return "pcm: \(name) rejected: \(reason)"
        }
        return nil
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
