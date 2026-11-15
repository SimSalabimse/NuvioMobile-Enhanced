import BackgroundTasks
import ComposeApp
import zlib
import Foundation
import MetricKit
import Network
import UIKit

/// File-backed behaviour log. Kotlin owns the ring. This type writes the two chunks and uploads them.
final class BehaviourFileBridge: NSObject, NuvioBehaviourLogBridge {
    func write(active: String, previous: String) {
        BehaviourFiles.replace(active: active, previous: previous)
    }
}

enum BehaviourFiles {
    private static let queue = DispatchQueue(label: "tv.nuvio.behaviour", qos: .utility)
    private static let maxFileBytes = 2 * 1024 * 1024

    static func replace(active: String, previous: String) {
        queue.async {
            write(name: "behaviour-a.jsonl", text: String(previous.prefix(maxFileBytes)))
            write(name: "behaviour-b.jsonl", text: String(active.prefix(maxFileBytes)))
        }
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

    static var enabled: Bool {
        if UserDefaults.standard.object(forKey: key) == nil { return true }
        return UserDefaults.standard.bool(forKey: key)
    }

    static var lastUploaded: String {
        let stamp = UserDefaults.standard.double(forKey: lastUploadKey)
        if stamp <= 0 { return "Not yet" }
        let date = Date(timeIntervalSince1970: stamp)
        let format = DateFormatter()
        format.dateStyle = .medium
        format.timeStyle = .short
        return format.string(from: date)
    }
}

enum BehaviourUploadTask {
    static let identifier = "com.nuvio.app.behaviour-upload"

    static func registerAtLaunch() {
        NuvioBehaviourLogRegistry.shared.register(log: BehaviourFileBridge())
        MXMetricManager.shared.add(BehaviourMetrics.shared)
        BehaviourLogFiles_iosKt.nuvioRecordBehaviour(kind: "launch")
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
            BehaviourLogFiles_iosKt.nuvioRecordBehaviour(kind: "background")
            BehaviourLogFiles_iosKt.nuvioBehaviourFlush()
            schedule()
            upload { _ in }
        }
        NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { _ in
            BehaviourLogFiles_iosKt.nuvioRecordBehaviour(kind: "foreground")
        }
    }

    static func schedule() {
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 60 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    static func upload(done: @escaping (Bool) -> Void) {
        guard BehaviourShare.enabled else { done(false); return }
        let token = UserDefaults.standard.string(forKey: "NuvioSyncDeviceToken") ?? ""
        let base = UserDefaults.standard.string(forKey: "NuvioSyncBaseURL") ?? ""
        guard token.count == 43, let url = URL(string: base)?.appendingPathComponent("telemetry") else {
            done(false)
            return
        }
        let path = pathMonitor.currentPath
        let wifi = path.status == .satisfied && path.usesInterfaceType(.wifi) && !path.isExpensive && !path.isConstrained
        if !wifi || ProcessInfo.processInfo.isLowPowerModeEnabled {
            done(false)
            return
        }
        let last = UserDefaults.standard.double(forKey: BehaviourShare.lastUploadKey)
        if last > 0, Date().timeIntervalSince1970 - last < 6 * 60 * 60 {
            done(false)
            return
        }
        let body = BehaviourLogFiles_iosKt.nuvioBehaviourSnapshot()
        guard let raw = body.data(using: .utf8), !raw.isEmpty, let gzipped = gzip(raw), gzipped.count <= 512 * 1024 else {
            done(false)
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("gzip", forHTTPHeaderField: "Content-Encoding")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("222", forHTTPHeaderField: "X-Nuvio-App-Build")
        request.setValue("behaviour", forHTTPHeaderField: "X-Nuvio-Kind")
        request.httpBody = gzipped
        URLSession.shared.dataTask(with: request) { _, response, _ in
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            if (200..<300).contains(code) {
                BehaviourLogFiles_iosKt.nuvioBehaviourClear()
                BehaviourFiles.clear()
                UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: BehaviourShare.lastUploadKey)
                done(true)
            } else {
                done(false)
            }
        }.resume()
    }

    private static let pathMonitor: NWPathMonitor = {
        let monitor = NWPathMonitor()
        monitor.start(queue: DispatchQueue(label: "tv.nuvio.behaviour.path"))
        return monitor
    }()

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

enum BehaviourEvents {
    static func markFromSettings() {
        BehaviourLogFiles_iosKt.nuvioRecordBehaviour(kind: "marker")
        BehaviourLogFiles_iosKt.nuvioBehaviourFlush()
        UINotificationFeedbackGenerator().notificationOccurred(.success)
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
