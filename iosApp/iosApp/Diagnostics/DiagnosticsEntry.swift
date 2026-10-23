import Foundation

struct DiagnosticsEntry: Codable {
    let timestamp: String
    let appVersion: String
    let appBuild: String
    let iosVersion: String
    let category: String
    let message: String
    
    enum Category: String {
        case crash
        case error
        case networkError
        case performance
        case playback
        case download
        case launch
    }
    
    static func create(
        category: Category,
        message: String,
        appVersion: String = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown",
        appBuild: String = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "unknown"
    ) -> DiagnosticsEntry {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        
        return DiagnosticsEntry(
            timestamp: formatter.string(from: Date()),
            appVersion: appVersion,
            appBuild: appBuild,
            iosVersion: UIDevice.current.systemVersion,
            category: category.rawValue,
            message: message
        )
    }
    
    func toJSONLine() -> String? {
        guard let data = try? JSONEncoder().encode(self),
              let jsonString = String(data: data, encoding: .utf8) else {
            return nil
        }
        return jsonString
    }
}
