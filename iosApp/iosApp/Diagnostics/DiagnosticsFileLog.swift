import Foundation

/// Appends one JSON line to the diagnostics file. Callers inject a test double that throws.
protocol DiagnosticsFileAppending {
    func append(line: String) throws
}

enum DiagnosticsFileWriteDecision: Equatable {
    case write
    case skipLowSpace
    case skipPaused
}

/// Decides whether a diagnostics line may touch the disk.
///
/// A failed write pauses file logging for `failureBackoff`. Free space under 50 MB skips the
/// write without waiting out that pause, so logging resumes on the next line once space returns.
struct DiagnosticsFileWriteGate {
    static let lowSpaceThresholdBytes: Int64 = 50 * 1024 * 1024
    static let failureBackoff: TimeInterval = 30

    private(set) var pausedUntil: Date?

    func decision(freeBytes: Int64?, now: Date) -> DiagnosticsFileWriteDecision {
        if let pausedUntil, now < pausedUntil {
            return .skipPaused
        }
        if let freeBytes, freeBytes < Self.lowSpaceThresholdBytes {
            return .skipLowSpace
        }
        return .write
    }

    mutating func noteWriteSucceeded() {
        pausedUntil = nil
    }

    mutating func noteWriteFailed(now: Date) {
        pausedUntil = now.addingTimeInterval(Self.failureBackoff)
    }
}

/// File-log coordinator. A throwing appender cannot escape this type: the error pauses writes.
final class DiagnosticsFileLogWriter {
    private let appender: any DiagnosticsFileAppending
    private let now: () -> Date
    private let freeBytes: () -> Int64?
    private var gate = DiagnosticsFileWriteGate()

    init(
        appender: any DiagnosticsFileAppending,
        now: @escaping () -> Date = { Date() },
        freeBytes: @escaping () -> Int64?
    ) {
        self.appender = appender
        self.now = now
        self.freeBytes = freeBytes
    }

    /// Writes one line. Returns false when the line was not written. Does not throw.
    @discardableResult
    func append(line: String) -> Bool {
        let current = now()
        switch gate.decision(freeBytes: freeBytes(), now: current) {
        case .skipLowSpace, .skipPaused:
            return false
        case .write:
            do {
                try appender.append(line: line)
                gate.noteWriteSucceeded()
                return true
            } catch {
                gate.noteWriteFailed(now: current)
                return false
            }
        }
    }
}

enum DiagnosticsDiskSpace {
    /// Bytes the volume can spare for important usage, or nil when the value cannot be read.
    /// Nil does not skip the write; the throwing appender still catches a full disk.
    static func importantUsageFreeBytes(at fileURL: URL) -> Int64? {
        let parentFolder = fileURL.deletingLastPathComponent()
        let resourceKeys: Set<URLResourceKey> = [.volumeAvailableCapacityForImportantUsageKey]
        guard let values = try? parentFolder.resourceValues(forKeys: resourceKeys) else {
            return nil
        }
        return values.volumeAvailableCapacityForImportantUsage
    }
}

/// Real file appender. Deployment target is iOS 16.1, so `seekToEnd()` and `write(contentsOf:)`
/// are available. Those throw Swift errors. The older `seekToEndOfFile()` / `write(_:)` pair
/// raises an Objective-C exception that Swift cannot catch.
struct DiagnosticsFileAppender: DiagnosticsFileAppending {
    let fileURL: URL

    func append(line: String) throws {
        let data = Data((line + "\n").utf8)
        if FileManager.default.fileExists(atPath: fileURL.path) {
            let handle = try FileHandle(forWritingTo: fileURL)
            defer { try? handle.close() }
            _ = try handle.seekToEnd()
            try handle.write(contentsOf: data)
        } else {
            try data.write(to: fileURL, options: .atomic)
        }
    }
}
