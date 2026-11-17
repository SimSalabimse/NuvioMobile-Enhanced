import BackgroundTasks
import ComposeApp
import Security
import zlib
import Foundation
import MetricKit
import Network
import UIKit

/// File-backed behaviour log. Kotlin owns the ring. Appends are enqueued onto
/// `tv.nuvio.behaviour.append` and never run on the main thread.
final class BehaviourFileBridge: NSObject, NuvioBehaviourLogBridge {
    func write(active: String, previous: String) {
        BehaviourFiles.replace(active: active, previous: previous)
    }

    func readPrevious() -> String { BehaviourFiles.read("behaviour-a.jsonl") }
    func readActive() -> String { BehaviourFiles.read("behaviour-b.jsonl") }
    func writeSnapshot(text: String) { BehaviourFiles.writeSnapshot(text) }
    func readSnapshot() -> String { BehaviourFiles.read("behaviour-upload.jsonl") }
    func deleteSnapshot() { BehaviourFiles.deleteSnapshot() }
}

enum BehaviourFiles {
    private static let queue = DispatchQueue(label: "tv.nuvio.behaviour", qos: .utility)
    private static let maxFileBytes = 2 * 1024 * 1024

    static func replace(active: String, previous: String) {
        queue.sync {
            write(name: "behaviour-a.jsonl", text: capped(previous))
            write(name: "behaviour-b.jsonl", text: capped(active))
        }
    }

    static func read(_ name: String) -> String {
        queue.sync {
            (try? String(contentsOf: file(name), encoding: .utf8)) ?? ""
        }
    }

    static func writeSnapshot(_ text: String) {
        queue.sync { write(name: "behaviour-upload.jsonl", text: capped(text)) }
    }

    static func deleteSnapshot() {
        queue.sync {
            try? FileManager.default.removeItem(at: file("behaviour-upload.jsonl"))
        }
    }

    private static func capped(_ text: String) -> String {
        let data = text.data(using: .utf8) ?? Data()
        if data.count <= maxFileBytes { return text }
        return String(data: data.prefix(maxFileBytes), encoding: .utf8) ?? ""
    }

    static func combined() -> String {
        queue.sync {
            let previous = (try? String(contentsOf: file("behaviour-a.jsonl"), encoding: .utf8)) ?? ""
            let active = (try? String(contentsOf: file("behaviour-b.jsonl"), encoding: .utf8)) ?? ""
            return previous + active
        }
    }

    static func clear() {
        queue.sync {
            try? FileManager.default.removeItem(at: file("behaviour-a.jsonl"))
            try? FileManager.default.removeItem(at: file("behaviour-b.jsonl"))
        }
    }

    private static func write(name: String, text: String) {
        let url = file(name)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? text.data(using: .utf8)?.write(to: url, options: .atomic)
    }

    private static func file(_ name: String) -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("NuvioBehaviour", isDirectory: true).appendingPathComponent(name)
    }
}

enum BehaviourShare {
    static let key = "NuvioShareBehaviourLog"
    static let lastUploadKey = "NuvioBehaviourLastUpload"
    static let lastAttemptKey = "NuvioBehaviourLastAttempt"
    static let lastAttemptOkKey = "NuvioBehaviourLastAttemptOk"
    static let lastAttemptDetailKey = "NuvioBehaviourLastAttemptDetail"

    static var enabled: Bool {
        if UserDefaults.standard.object(forKey: key) == nil { return true }
        return UserDefaults.standard.bool(forKey: key)
    }

    static func uploadedLabel(stamp: Double) -> String {
        if stamp <= 0 { return "Not yet" }
        return formatted(stamp)
    }

    static var lastUploaded: String {
        uploadedLabel(stamp: UserDefaults.standard.double(forKey: lastUploadKey))
    }

    static func recordAttempt(ok: Bool, detail: String) {
        let defaults = UserDefaults.standard
        defaults.set(Date().timeIntervalSince1970, forKey: lastAttemptKey)
        defaults.set(ok, forKey: lastAttemptOkKey)
        let shown = ok ? "OK" : safeDetail(detail)
        defaults.set(shown, forKey: lastAttemptDetailKey)
    }

    static func lastAttemptLabel(stamp: Double, ok: Bool, detail: String) -> String {
        if stamp <= 0 { return "Not yet" }
        let outcome = ok ? "OK" : (detail.isEmpty ? "Could not upload" : detail)
        return "\(formatted(stamp)) · \(outcome)"
    }

    private static func formatted(_ stamp: Double) -> String {
        let format = DateFormatter()
        format.dateStyle = .medium
        format.timeStyle = .short
        return format.string(from: Date(timeIntervalSince1970: stamp))
    }

    /// Short status text. Drops anything that could be a path or a URL.
    private static func safeDetail(_ detail: String) -> String {
        let kept = detail.filter { $0.isLetter || $0.isNumber || $0 == " " || $0 == "-" }
        let trimmed = kept.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "Could not upload" }
        return String(trimmed.prefix(40))
    }
}

enum BehaviourUploadRoute: Equatable {
    case wifi
    case cellular
    case offline
}

enum BehaviourUploadTask {
    static let identifier = "com.nuvio.app.behaviour-upload"

    static func registerAtLaunch() {
        NuvioBehaviourLogRegistry.shared.register(log: BehaviourFileBridge())
        MXMetricManager.shared.add(BehaviourMetrics.shared)
        BehaviourLogFiles_iosKt.nuvioBehaviourOpen()
        BehaviourPath.start()
        DispatchQueue.global(qos: .utility).async {
            BehaviourLogFiles_iosKt.nuvioRecordBehaviour(kind: "launch")
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let processing = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            upload { ok in
                processing.setTaskCompleted(success: ok)
            }
        }
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { _ in
            BehaviourLogFiles_iosKt.nuvioRecordHeat(where: "background")
            DispatchQueue.global(qos: .utility).async {
                BehaviourLogFiles_iosKt.nuvioRecordBehaviour(kind: "background")
                BehaviourLogFiles_iosKt.nuvioBehaviourFlush()
            }
            schedule()
            upload { _ in }
        }
        NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { _ in
            BehaviourLogFiles_iosKt.nuvioRecordHeat(where: "foreground")
            DispatchQueue.global(qos: .utility).async {
                BehaviourLogFiles_iosKt.nuvioRecordBehaviour(kind: "foreground")
            }
        }
    }

    static func schedule() {
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 60 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    static func decide(satisfied: Bool, wifi: Bool, cellular: Bool) -> BehaviourUploadRoute {
        if !satisfied { return .offline }
        if cellular && !wifi { return .cellular }
        return .wifi
    }

    /// Waits for the first path update, so the first tap is not "No network" before the monitor has a path.
    static func route(done: @escaping (BehaviourUploadRoute) -> Void) {
        BehaviourPath.start()
        BehaviourPath.path(timeout: 0.5) { path in
            let decided = decide(
                satisfied: path.status == .satisfied,
                wifi: path.usesInterfaceType(.wifi),
                cellular: path.usesInterfaceType(.cellular)
            )
            DispatchQueue.main.async { done(decided) }
        }
    }

    /// [now] skips the six-hour wait. Cellular is used only after the settings row confirms it.
    static func upload(now: Bool = false, allowCellular: Bool = false, done: @escaping (Bool) -> Void) {
        let finish: (Bool, String, Bool) -> Void = { ok, detail, record in
            DispatchQueue.main.async {
                if record { BehaviourShare.recordAttempt(ok: ok, detail: detail) }
                done(ok)
            }
        }
        guard BehaviourShare.enabled else {
            finish(false, "Sharing is off", now)
            return
        }
        let ingestKey = TelemetryPackaging.value("NuvioTelemetryIngestKey")
        let ingestBase = TelemetryPackaging.value("NuvioTelemetryBaseURL")
        let token = UserDefaults.standard.string(forKey: "NuvioSyncDeviceToken") ?? ""
        let syncBase = UserDefaults.standard.string(forKey: "NuvioSyncBaseURL") ?? ""
        let destination: URL
        let ingest: String?
        if let ingestKey, let ingestBase, let url = URL(string: ingestBase)?.appendingPathComponent("telemetry") {
            destination = url
            ingest = ingestKey
        } else if token.count == 43, let url = URL(string: syncBase)?.appendingPathComponent("telemetry") {
            destination = url
            ingest = nil
        } else {
            finish(false, "Not configured", now)
            return
        }
        let send: () -> Void = {
            sendBatch(destination: destination, ingest: ingest, token: token, now: now, allowFollowUp: true, finish: finish)
        }
        if now {
            route { decided in
                switch decided {
                case .offline:
                    finish(false, "No network", true)
                case .cellular where !allowCellular:
                    finish(false, "Needs cellular confirm", true)
                case .cellular, .wifi:
                    send()
                }
            }
            return
        }
        BehaviourPath.start()
        BehaviourPath.path(timeout: 0.5) { path in
            let wifi = path.status == .satisfied && path.usesInterfaceType(.wifi) && !path.isExpensive && !path.isConstrained
            DispatchQueue.main.async {
                if !wifi || ProcessInfo.processInfo.isLowPowerModeEnabled {
                    finish(false, "", false)
                    return
                }
                let last = UserDefaults.standard.double(forKey: BehaviourShare.lastUploadKey)
                if last > 0, Date().timeIntervalSince1970 - last < 6 * 60 * 60 {
                    finish(false, "", false)
                    return
                }
                send()
            }
        }
    }

    /// One gzip body, at most 512 KB. A leftover snapshot is sent first. After that 2xx, the live ring follows once.
    private static func sendBatch(
        destination: URL,
        ingest: String?,
        token: String,
        now: Bool,
        allowFollowUp: Bool,
        finish: @escaping (Bool, String, Bool) -> Void
    ) {
        DispatchQueue.global(qos: .utility).async {
            let body = BehaviourLogFiles_iosKt.nuvioBehaviourBeginUpload()
            let wasPending = BehaviourLogFiles_iosKt.nuvioBehaviourLastUploadWasPending()
            guard let raw = body.data(using: .utf8), !raw.isEmpty, let gzipped = gzip(raw), gzipped.count <= 512 * 1024 else {
                if !allowFollowUp {
                    finish(true, "OK", true)
                } else {
                    finish(false, "Nothing to send", now)
                }
                return
            }
            var request = URLRequest(url: destination)
            request.httpMethod = "POST"
            request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            request.setValue("gzip", forHTTPHeaderField: "Content-Encoding")
            if let ingest {
                request.setValue(ingest, forHTTPHeaderField: "X-Nuvio-Ingest")
                request.setValue(TelemetryInstall.id, forHTTPHeaderField: "X-Nuvio-Install")
            } else {
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }
            let build = (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "224")
                .filter { $0.isNumber || $0 == "." }
            request.setValue(build.isEmpty ? "224" : String(build.prefix(32)), forHTTPHeaderField: "X-Nuvio-App-Build")
            request.setValue("behaviour", forHTTPHeaderField: "X-Nuvio-Kind")
            request.httpBody = gzipped
            URLSession.shared.dataTask(with: request) { _, response, _ in
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                BehaviourLogFiles_iosKt.nuvioBehaviourFinishUpload(status: Int32(code))
                if (200..<300).contains(code) {
                    UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: BehaviourShare.lastUploadKey)
                    if wasPending && allowFollowUp {
                        sendBatch(
                            destination: destination,
                            ingest: ingest,
                            token: token,
                            now: now,
                            allowFollowUp: false,
                            finish: finish
                        )
                    } else {
                        finish(true, "OK", true)
                    }
                } else if code == 0 {
                    finish(false, "No response", true)
                } else {
                    finish(false, "HTTP \(code)", true)
                }
            }.resume()
        }
    }

    private static func gzip(_ data: Data) -> Data? {
        guard !data.isEmpty else { return nil }
        var stream = z_stream()
        let opened = deflateInit2_(
            &stream,
            Z_DEFAULT_COMPRESSION,
            Z_DEFLATED,
            15 + 16,
            8,
            Z_DEFAULT_STRATEGY,
            ZLIB_VERSION,
            Int32(MemoryLayout<z_stream>.size)
        )
        if opened != Z_OK { return nil }
        defer { deflateEnd(&stream) }
        return data.withUnsafeBytes { raw -> Data? in
            guard let source = raw.bindMemory(to: Bytef.self).baseAddress else { return nil }
            stream.next_in = UnsafeMutablePointer(mutating: source)
            stream.avail_in = uInt(data.count)
            var output = Data(count: data.count + 64)
            let written: Int = output.withUnsafeMutableBytes { out -> Int in
                guard let destination = out.bindMemory(to: Bytef.self).baseAddress else { return 0 }
                stream.next_out = destination
                stream.avail_out = uInt(out.count)
                let result = deflate(&stream, Z_FINISH)
                if result != Z_STREAM_END { return 0 }
                return out.count - Int(stream.avail_out)
            }
            if written <= 0 { return nil }
            return output.prefix(written)
        }
    }
}

/// Starts at launch. The first path update is the one Upload now waits for.
enum BehaviourPath {
    private static let queue = DispatchQueue(label: "tv.nuvio.behaviour.path")
    private static let state = DispatchQueue(label: "tv.nuvio.behaviour.path.state")
    private static let monitor = NWPathMonitor()
    private static var started = false
    private static var ready = false
    private static var latest: NWPath?

    static func start() {
        state.sync {
            guard !started else { return }
            started = true
            monitor.pathUpdateHandler = { path in
                state.sync {
                    latest = path
                    ready = true
                }
            }
            monitor.start(queue: queue)
        }
    }

    static func path(timeout: TimeInterval, done: @escaping (NWPath) -> Void) {
        start()
        queue.async {
            let snapshot = state.sync { (ready, latest) }
            if snapshot.0, let path = snapshot.1 {
                done(path)
                return
            }
            queue.asyncAfter(deadline: .now() + timeout) {
                let path = state.sync { latest } ?? monitor.currentPath
                done(path)
            }
        }
    }
}

enum BehaviourEvents {
    static func markFromSettings() {
        // Returns after the marker is on disk. The write runs on tv.nuvio.behaviour.append.
        BehaviourLogFiles_iosKt.nuvioBehaviourMark()
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
}

enum TelemetryPackaging {
    static func value(_ key: String) -> String? {
        let raw = Bundle.main.object(forInfoDictionaryKey: key) as? String ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.contains("$(") { return nil }
        return trimmed
    }
}

enum TelemetryInstall {
    static var id: String {
        let key = "NuvioInstallId"
        if let existing = UserDefaults.standard.string(forKey: key),
           existing.count >= 8,
           existing.count <= 64,
           existing.unicodeScalars.allSatisfy({ CharacterSet(charactersIn: "0123456789abcdef-").contains($0) }) {
            return existing
        }
        var bytes = [UInt8](repeating: 0, count: 8)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let created = bytes.map { String(format: "%02x", $0) }.joined()
        UserDefaults.standard.set(created, forKey: key)
        return created
    }
}

final class BehaviourMetrics: NSObject, MXMetricManagerSubscriber {
    static let shared = BehaviourMetrics()

    func didReceive(_ payloads: [MXMetricPayload]) {
        payloads.forEach { payload in
            store(kind: "metric", data: payload.jsonRepresentation())
        }
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        payloads.forEach { payload in
            store(kind: "diagnostic", data: payload.jsonRepresentation())
        }
    }

    private func store(kind: String, data: Data) {
        guard data.count <= 32 * 1024, let text = String(data: data, encoding: .utf8) else {
            BehaviourLogFiles_iosKt.nuvioRecordBehaviour(kind: "metrickit-\(kind)-dropped")
            return
        }
        BehaviourLogFiles_iosKt.nuvioAppendRaw(line: text)
    }
}
