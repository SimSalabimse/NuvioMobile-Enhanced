import Foundation

final class DiagnosticsManager {
    static let shared = DiagnosticsManager()
    
    private let fileURL: URL
    private let queue = DispatchQueue(label: "tv.nuvio.diagnostics", qos: .utility)
    private let maxFileSize: Int = 4 * 1024 * 1024
    
    private init() {
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        fileURL = documentsPath.appendingPathComponent("diagnostics.jsonl")
        
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
            self.rotateIfNeeded()
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
        let crashMarkerKey = "NuvioDiagnosticsCrashMarker"
        
        if UserDefaults.standard.bool(forKey: crashMarkerKey) {
            log(category: .crash, message: "App did not terminate cleanly on previous launch (possible crash)")
        }
        
        UserDefaults.standard.set(true, forKey: crashMarkerKey)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
            UserDefaults.standard.set(false, forKey: crashMarkerKey)
        }
    }
    
    private func appendLine(_ line: String) {
        let data = (line + "\n").data(using: .utf8)!
        
        if FileManager.default.fileExists(atPath: fileURL.path) {
            if let fileHandle = try? FileHandle(forWritingTo: fileURL) {
                fileHandle.seekToEndOfFile()
                fileHandle.write(data)
                try? fileHandle.close()
            }
        } else {
            try? data.write(to: fileURL, options: .atomic)
        }
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
