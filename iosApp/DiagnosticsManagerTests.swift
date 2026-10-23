import XCTest
@testable import iosApp

final class DiagnosticsManagerTests: XCTestCase {
    
    override func setUp() {
        super.setUp()
        DiagnosticsManager.shared.clearLog()
        DiagnosticsManager.shared.isEnabled = true
    }
    
    override func tearDown() {
        DiagnosticsManager.shared.clearLog()
        DiagnosticsManager.shared.isEnabled = false
        super.tearDown()
    }
    
    func testToggleOffDeletesLog() {
        DiagnosticsManager.shared.log(category: .error, message: "Test error")
        
        let expectation = self.expectation(description: "Log written")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 1.0)
        
        XCTAssertNotNil(DiagnosticsManager.shared.exportLog())
        
        DiagnosticsManager.shared.isEnabled = false
        
        let deleteExpectation = self.expectation(description: "Log deleted")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            deleteExpectation.fulfill()
        }
        wait(for: [deleteExpectation], timeout: 1.0)
        
        XCTAssertNil(DiagnosticsManager.shared.exportLog())
    }
    
    func testLogsAreRedacted() {
        let sensitiveMessage = "Error with token abc123def456ghi789jklmno from https://api.test.com/secret/path"
        DiagnosticsManager.shared.log(category: .error, message: sensitiveMessage)
        
        let expectation = self.expectation(description: "Log written")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 1.0)
        
        guard let logURL = DiagnosticsManager.shared.exportLog(),
              let content = try? String(contentsOf: logURL, encoding: .utf8) else {
            XCTFail("Could not read log file")
            return
        }
        
        XCTAssertFalse(content.contains("abc123def456ghi789jklmno"))
        XCTAssertFalse(content.contains("/secret/path"))
        XCTAssertTrue(content.contains("[REDACTED_TOKEN]") || content.contains("[REDACTED_KEY]"))
    }
    
    func testLogRotation() {
        for i in 0..<1000 {
            DiagnosticsManager.shared.log(category: .error, message: "Test message \(i) with some padding to make it larger and trigger rotation when we have enough entries")
        }
        
        let expectation = self.expectation(description: "Logs written")
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 3.0)
        
        guard let logURL = DiagnosticsManager.shared.exportLog() else {
            XCTFail("Log file not found")
            return
        }
        
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: logURL.path),
              let fileSize = attributes[.size] as? Int else {
            XCTFail("Could not get file size")
            return
        }
        
        let maxSize = 5 * 1024 * 1024
        XCTAssertLessThan(fileSize, maxSize, "Log file should be rotated before exceeding max size")
    }
    
    func testClearLog() {
        DiagnosticsManager.shared.log(category: .error, message: "Test")
        
        let writeExpectation = self.expectation(description: "Log written")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            writeExpectation.fulfill()
        }
        wait(for: [writeExpectation], timeout: 1.0)
        
        XCTAssertNotNil(DiagnosticsManager.shared.exportLog())
        
        DiagnosticsManager.shared.clearLog()
        
        let clearExpectation = self.expectation(description: "Log cleared")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            clearExpectation.fulfill()
        }
        wait(for: [clearExpectation], timeout: 1.0)
        
        XCTAssertNil(DiagnosticsManager.shared.exportLog())
    }
    
    func testJSONLinesFormat() {
        DiagnosticsManager.shared.log(category: .launch, message: "App launched")
        
        let expectation = self.expectation(description: "Log written")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 1.0)
        
        guard let logURL = DiagnosticsManager.shared.exportLog(),
              let content = try? String(contentsOf: logURL, encoding: .utf8) else {
            XCTFail("Could not read log file")
            return
        }
        
        let lines = content.components(separatedBy: .newlines).filter { !$0.isEmpty }
        XCTAssertGreaterThan(lines.count, 0)
        
        for line in lines {
            guard let data = line.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                XCTFail("Invalid JSON line")
                continue
            }
            
            XCTAssertNotNil(json["timestamp"])
            XCTAssertNotNil(json["appVersion"])
            XCTAssertNotNil(json["iosVersion"])
            XCTAssertNotNil(json["category"])
            XCTAssertNotNil(json["message"])
        }
    }
}
