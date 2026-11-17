import Foundation
import UIKit

final class DiagnosticsManager {
    static let shared = DiagnosticsManager()
    
    private let fileURL: URL
    private let fileLog: DiagnosticsFileLogWriter
    private let queue = DispatchQueue(label: "tv.nuvio.diagnostics", qos: .utility)
    private let maxFileSize: Int = 4 * 1024 * 1024
    private var lifecycleObservers: [NSObjectProtocol] = []
    
    private init() {
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let url = documentsPath.appendingPathComponent("diagnostics.jsonl")
        fileURL = url
        fileLog = DiagnosticsFileLogWriter(
            appender: DiagnosticsFileAppender(fileURL: url),
            freeBytes: { DiagnosticsDiskSpace.importantUsageFreeBytes(at: url) }
        )
        
        checkForCrash()
    }
    
    var isEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: "NuvioDiagnosticsEnabled") }
        set {
            UserDefaults.standard.set(newValue, forKey: "NuvioDiagnosticsEnabled")
            if !newValue {
                deleteLog()
            }
        }
    }
    
    var hasSeenIntro: Bool {
        get { UserDefaults.standard.bool(forKey: "NuvioDiagnosticsIntroSeen") }
        set { UserDefaults.standard.set(newValue, forKey: "NuvioDiagnosticsIntroSeen") }
    }
    
    func log(category: DiagnosticsEntry.Category, message: String) {
        guard isEnabled else { return }
        
        queue.async { [weak self] in
            guard let self = self else { return }
            
            let redactedMessage = DiagnosticsRedactor.redact(message)
            let entry = DiagnosticsEntry.create(category: category, message: redactedMessage)
            
            guard let jsonLine = entry.toJSONLine() else { return }
            
            self.appendLine(jsonLine)
        }
    }
    
    func logColdLaunchTime(_ duration: TimeInterval) {
        log(category: .launch, message: "Cold launch completed in \(String(format: "%.2f", duration))s")
    }
    
    func logTimeToPlayback(from tapTime: Date, to playbackTime: Date) {
        let duration = playbackTime.timeIntervalSince(tapTime)
        log(category: .performance, message: "Time from tap to playback: \(String(format: "%.2f", duration))s")
    }
    
    func logPlaybackStall(duration: TimeInterval) {
        log(category: .playback, message: "Playback stalled for \(String(format: "%.2f", duration))s")
    }

    static let latestAnalysisTimingKey = "NuvioDiagnosticsLatestAnalysisTiming"

    /// Keeps the latest analysis stage line for the diagnostics screen, and appends it to the log when saving is on.
    func logAnalysisTimings(_ message: String) {
        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        if trimmed.hasPrefix("timings ") {
            UserDefaults.standard.set(trimmed, forKey: Self.latestAnalysisTimingKey)
        }
        log(category: .performance, message: trimmed)
    }

    func latestAnalysisTiming() -> String? {
        UserDefaults.standard.string(forKey: Self.latestAnalysisTimingKey)
    }
    
    func logNetworkError(statusCode: Int, url: String) {
        let host = URL(string: url)?.host ?? "unknown"
        log(category: .networkError, message: "HTTP \(statusCode) from \(host)")
    }
    
    func logDownloadFailure(title: String, reason: String) {
        let redactedReason = DiagnosticsRedactor.redact(reason)
        log(category: .download, message: "Download failed: \(title) - \(redactedReason)")
    }
    
    func logError(message: String) {
        log(category: .error, message: message)
    }
    
    func exportLog() -> URL? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            return nil
        }
        return fileURL
    }
    
    func clearLog() {
        UserDefaults.standard.removeObject(forKey: Self.latestAnalysisTimingKey)
        queue.async { [weak self] in
            guard let self = self else { return }
            try? FileManager.default.removeItem(at: self.fileURL)
        }
    }
    
    func deleteLog() {
        clearLog()
    }
    
    func getLogSize() -> String {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
              let fileSize = attributes[.size] as? Int64 else {
            return "0 KB"
        }
        
        let kb = Double(fileSize) / 1024.0
        if kb < 1024 {
            return String(format: "%.1f KB", kb)
        } else {
            let mb = kb / 1024.0
            return String(format: "%.1f MB", mb)
        }
    }
    
    private func checkForCrash() {
        let defaults = UserDefaults.standard
        let wasSet = defaults.bool(forKey: DiagnosticsCrashMarker.storageKey)
        let markerDecision = DiagnosticsCrashMarker.markerAfterLaunch(
            markerWasSet: wasSet,
            alreadyInBackground: applicationState() == .background
        )
        defaults.set(markerDecision.markerSet, forKey: DiagnosticsCrashMarker.storageKey)

        if markerDecision.logUncleanTermination {
            log(category: .crash, message: "App did not terminate cleanly on previous launch (possible crash)")
        }

        let center = NotificationCenter.default
        let namesAndSignals: [(Notification.Name, DiagnosticsProcessSignal)] = [
            (UIApplication.didEnterBackgroundNotification, .enteredBackground),
            (UIApplication.willTerminateNotification, .willTerminate),
            (UIApplication.didBecomeActiveNotification, .becameActive),
        ]
        for (name, signal) in namesAndSignals {
            lifecycleObservers.append(
                center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                    self?.applyCrashSignal(signal)
                }
            )
        }
    }

    private func applyCrashSignal(_ signal: DiagnosticsProcessSignal) {
        let defaults = UserDefaults.standard
        let markerDecision = DiagnosticsCrashMarker.decisionAfter(
            markerWasSet: defaults.bool(forKey: DiagnosticsCrashMarker.storageKey),
            signal: signal
        )
        defaults.set(markerDecision.markerSet, forKey: DiagnosticsCrashMarker.storageKey)
    }

    private func applicationState() -> UIApplication.State {
        let readState = { UIApplication.shared.applicationState }
        if Thread.isMainThread {
            return readState()
        }
        return DispatchQueue.main.sync(execute: readState)
    }
    
    private func appendLine(_ line: String) {
        guard fileLog.append(line: line) else { return }
        rotateIfNeeded()
    }
    
    private func rotateIfNeeded() {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
              let fileSize = attributes[.size] as? Int,
              fileSize > maxFileSize else {
            return
        }
        
        guard let content = try? String(contentsOf: fileURL, encoding: .utf8) else {
            return
        }
        
        let lines = content.components(separatedBy: .newlines).filter { !$0.isEmpty }
        
        let keepCount = lines.count / 2
        let keptLines = lines.suffix(keepCount)
        let newContent = keptLines.joined(separator: "\n") + "\n"
        
        try? newContent.write(to: fileURL, atomically: true, encoding: .utf8)
    }
}
