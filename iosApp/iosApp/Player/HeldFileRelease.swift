import Foundation
import ComposeApp

/// Lets go of a downloaded file before (or as) it is deleted. iOS only frees the space of a deleted
/// file once every handle to it is closed, so a seek-preview reader, an analysis run, an audio
/// capture or a player still holding the file kept gigabytes "used" until the app was restarted.
final class HeldFileRegistry {
    static let shared = HeldFileRegistry()
    static let releaseNotification = Notification.Name("NuvioReleaseHeldFile")

    private final class WeakGenerator {
        weak var generator: FFmpegSeekPreviewGenerator?
        let path: String
        init(_ generator: FFmpegSeekPreviewGenerator, path: String) {
            self.generator = generator
            self.path = path
        }
    }

    private let lock = NSLock()
    private var generators: [WeakGenerator] = []

    /// Same file whatever form it came in ("file:///…", a plain path, percent escapes).
    static func normalizedPath(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let path: String
        if trimmed.hasPrefix("file:"), let url = URL(string: trimmed) {
            path = url.path
        } else {
            path = trimmed.removingPercentEncoding ?? trimmed
        }
        return (path as NSString).standardizingPath
    }

    static func samePath(_ a: String?, _ b: String) -> Bool {
        guard let a, !a.isEmpty, !b.isEmpty else { return false }
        let left = normalizedPath(a)
        let right = normalizedPath(b)
        if left == right { return true }
        // A container move changes the folder, not the file name of a download.
        let leftName = (left as NSString).lastPathComponent
        return !leftName.isEmpty && leftName == (right as NSString).lastPathComponent && left.contains("/nuvio_downloads/") == right.contains("/nuvio_downloads/")
    }

    func track(_ generator: FFmpegSeekPreviewGenerator, url: String) {
        guard !url.hasPrefix("http") else { return }
        lock.lock()
        generators.removeAll { $0.generator == nil }
        generators.append(WeakGenerator(generator, path: url))
        lock.unlock()
    }

    func release(path: String) {
        lock.lock()
        let matching = generators.filter { Self.samePath($0.path, path) }.compactMap { $0.generator }
        generators.removeAll { $0.generator == nil || Self.samePath($0.path, path) }
        lock.unlock()
        matching.forEach { $0.close() }
        NuvioMediaAnalyzerRegistration.shared.releaseFile(path: path)
        let post = { NotificationCenter.default.post(name: Self.releaseNotification, object: nil, userInfo: ["path": path]) }
        if Thread.isMainThread { post() } else { DispatchQueue.main.sync(execute: post) }
    }
}

final class NuvioHeldFileReleaser: NSObject, NuvioFileReleaseBridge {
    func releaseFile(path: String) {
        HeldFileRegistry.shared.release(path: path)
    }
}

enum NuvioHeldFileRegistration {
    static func register() {
        NuvioFileReleaseRegistry.shared.register(bridge: NuvioHeldFileReleaser())
    }
}
