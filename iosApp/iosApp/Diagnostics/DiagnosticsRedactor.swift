import Foundation

struct DiagnosticsRedactor {
    private static let tokenPattern = #"[a-zA-Z0-9_-]{20,}"#
    private static let apiKeyPattern = #"(?i)(api[-_]?key|token|bearer|authorization)[:=\s]+[a-zA-Z0-9_\-+=\/]+"#
    private static let emailPattern = #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b"#
    
    static func redact(_ message: String) -> String {
        var redacted = message
        
        redacted = redactURLsToHost(redacted)
        
        redacted = redacted.replacingOccurrences(
            of: tokenPattern,
            with: "[REDACTED_TOKEN]",
            options: .regularExpression
        )
        
        redacted = redacted.replacingOccurrences(
            of: apiKeyPattern,
            with: "[REDACTED_KEY]",
            options: .regularExpression
        )
        
        redacted = redacted.replacingOccurrences(
            of: emailPattern,
            with: "[REDACTED_EMAIL]",
            options: .regularExpression
        )
        
        redacted = redactDebridLinks(redacted)
        
        return redacted
    }
    
    private static func redactURLsToHost(_ message: String) -> String {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else {
            return message
        }
        
        let nsMessage = message as NSString
        let matches = detector.matches(in: message, range: NSRange(location: 0, length: nsMessage.length))
        
        var result = message
        for match in matches.reversed() {
            guard let url = match.url,
                  let host = url.host else { continue }
            
            let replacement = url.scheme != nil ? "\(url.scheme!)://\(host)" : host
            let range = Range(match.range, in: result)!
            result.replaceSubrange(range, with: replacement)
        }
        
        return result
    }
    
    private static func redactDebridLinks(_ message: String) -> String {
        let debridPatterns = [
            #"https?://[^/]*debrid[^/]*/[^\s]+"#,
            #"https?://[^/]*alldebrid[^/]*/[^\s]+"#,
            #"https?://[^/]*premiumize[^/]*/[^\s]+"#,
            #"https?://[^/]*realdebrid[^/]*/[^\s]+"#
        ]
        
        var redacted = message
        for pattern in debridPatterns {
            redacted = redacted.replacingOccurrences(
                of: pattern,
                with: "[DEBRID_LINK]",
                options: .regularExpression
            )
        }
        
        return redacted
    }
}
