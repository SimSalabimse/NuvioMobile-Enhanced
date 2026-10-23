import XCTest
@testable import iosApp

final class DiagnosticsRedactorTests: XCTestCase {
    
    func testRedactsTokens() {
        let input = "Bearer abc123def456ghi789jklmno"
        let result = DiagnosticsRedactor.redact(input)
        XCTAssertFalse(result.contains("abc123def456ghi789jklmno"))
        XCTAssertTrue(result.contains("[REDACTED_TOKEN]") || result.contains("[REDACTED_KEY]"))
    }
    
    func testRedactsAPIKeys() {
        let input = "api_key=sk_test_abc123def456"
        let result = DiagnosticsRedactor.redact(input)
        XCTAssertFalse(result.contains("sk_test_abc123def456"))
        XCTAssertTrue(result.contains("[REDACTED_KEY]"))
    }
    
    func testRedactsEmailAddresses() {
        let input = "User email is user@example.com"
        let result = DiagnosticsRedactor.redact(input)
        XCTAssertFalse(result.contains("user@example.com"))
        XCTAssertTrue(result.contains("[REDACTED_EMAIL]"))
    }
    
    func testRedactsDebridLinks() {
        let inputs = [
            "https://realdebrid.com/d/abc123",
            "https://alldebrid.com/link/xyz789",
            "https://premiumize.me/file/qwerty"
        ]
        
        for input in inputs {
            let result = DiagnosticsRedactor.redact(input)
            XCTAssertTrue(result.contains("[DEBRID_LINK]"))
            XCTAssertFalse(result.contains("/d/abc123"))
            XCTAssertFalse(result.contains("/link/xyz789"))
            XCTAssertFalse(result.contains("/file/qwerty"))
        }
    }
    
    func testRedactsURLsToHostOnly() {
        let input = "Failed to fetch https://api.example.com/v1/users/123/profile?token=secret"
        let result = DiagnosticsRedactor.redact(input)
        XCTAssertTrue(result.contains("https://api.example.com"))
        XCTAssertFalse(result.contains("/v1/users"))
        XCTAssertFalse(result.contains("token=secret"))
    }
    
    func testPreservesNonSensitiveText() {
        let input = "Playback failed with HTTP 404"
        let result = DiagnosticsRedactor.redact(input)
        XCTAssertEqual(input, result)
    }
    
    func testRedactsMultipleSensitiveItems() {
        let input = "Error from https://api.service.com/data with token xyz123abc456def789 and email user@test.com"
        let result = DiagnosticsRedactor.redact(input)
        XCTAssertTrue(result.contains("https://api.service.com"))
        XCTAssertFalse(result.contains("/data"))
        XCTAssertFalse(result.contains("xyz123abc456def789"))
        XCTAssertFalse(result.contains("user@test.com"))
        XCTAssertTrue(result.contains("[REDACTED_TOKEN]") || result.contains("[REDACTED_KEY]"))
        XCTAssertTrue(result.contains("[REDACTED_EMAIL]"))
    }
}
