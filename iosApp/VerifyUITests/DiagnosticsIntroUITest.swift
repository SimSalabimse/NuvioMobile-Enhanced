import XCTest

final class DiagnosticsIntroUITest: XCTestCase {
    private var app: XCUIApplication?

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    override func tearDownWithError() throws {
        if let app {
            try? Evidence.write(app.debugDescription, to: "diagnostics-intro-hierarchy.txt")
        }
        app = nil
    }

    func testIntroSheetToggleDefaultsOnAndDismisses() throws {
        let app = XCUIApplication()
        self.app = app
        // Install creates the container. Reseed while the process is stopped so the
        // one-time sheet is allowed to show over a launched home shell.
        app.launch()
        Thread.sleep(forTimeInterval: 3)
        app.terminate()
        try seedContainer()
        app.launch()

        let title = try wait(app.staticTexts["diagnostics.intro.title"], else: app.staticTexts["Diagnostics log"], timeout: 45)
        let toggle = try wait(app.switches["diagnostics.intro.saveToggle"], else: app.switches["Save diagnostics log"], timeout: 10)
        XCTAssertTrue(title.exists)
        try Evidence.screenshot(app, name: "diagnostics-intro-default-on.png")
        XCTAssertEqual(try toggleState(toggle), "1", "Save diagnostics log should default on\n\(app.debugDescription)")

        toggle.tap()
        XCTAssertEqual(try waitState(toggle, expected: "0"), "0", app.debugDescription)
        try Evidence.screenshot(app, name: "diagnostics-intro-toggled-off.png")

        toggle.tap()
        XCTAssertEqual(try waitState(toggle, expected: "1"), "1", app.debugDescription)

        let done = try wait(app.buttons["diagnostics.intro.done"], else: app.buttons["Done"], timeout: 10)
        done.tap()
        XCTAssertTrue(done.waitForNonExistence(timeout: 15), "Diagnostics sheet stayed up\n\(app.debugDescription)")
        try Evidence.screenshot(app, name: "diagnostics-intro-dismissed.png")

        let shell = shellVisible(app, timeout: 25)
        XCTAssertTrue(shell, "Tab bar was not up after dismissing Diagnostics\n\(app.debugDescription)")
        try Evidence.write(app.debugDescription, to: "diagnostics-intro-hierarchy.txt")
    }

    private func seedContainer() throws {
        let udid = try Evidence.pointer("udid")
        let seed = try Evidence.pointer("seed-script")
        let appPath = try Evidence.pointer("app-path")
        let process = Process()
        process.executableURL = URL(fileURLWithPath: seed)
        process.arguments = [udid, appPath]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        try process.run()
        process.waitUntilExit()
        let output = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        XCTAssertEqual(process.terminationStatus, 0, "seed failed\n\(output)")
    }

    private func wait(_ primary: XCUIElement, else fallback: XCUIElement, timeout: TimeInterval) throws -> XCUIElement {
        if primary.waitForExistence(timeout: timeout) {
            return primary
        }
        if fallback.waitForExistence(timeout: 5) {
            return fallback
        }
        XCTFail("Missing \(primary) and \(fallback)\n\(app?.debugDescription ?? "")")
        return primary
    }

    private func shellVisible(_ app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let labels = ["Home", "Profile", "Settings", "Search", "Library"]
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            for label in labels {
                if app.buttons[label].exists || app.tabBars.buttons[label].exists || app.tabs[label].exists {
                    return true
                }
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.4))
        }
        return false
    }

    private func toggleState(_ toggle: XCUIElement) throws -> String {
        let raw: String
        if let text = toggle.value as? String {
            raw = text
        } else if let number = toggle.value as? NSNumber {
            raw = number.intValue == 1 ? "1" : "0"
        } else {
            XCTFail("Toggle value \(String(describing: toggle.value))")
            return ""
        }
        switch raw.lowercased() {
        case "1", "true", "on":
            return "1"
        default:
            return "0"
        }
    }

    private func waitState(_ toggle: XCUIElement, expected: String) throws -> String {
        let deadline = Date().addingTimeInterval(5)
        var latest = ""
        while Date() < deadline {
            latest = try toggleState(toggle)
            if latest == expected {
                return latest
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        return latest
    }
}

private enum Evidence {
    static func directory() -> URL {
        let path = (try? pointer("evidence-path")) ?? "/tmp/verify-nuvio-ios-evidence"
        let url = URL(fileURLWithPath: path, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func pointer(_ name: String) throws -> String {
        let url = URL(fileURLWithPath: "/tmp/verify-nuvio-ios-\(name)")
        let text = try String(contentsOf: url, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            throw EvidenceError.emptyPointer(name)
        }
        return text
    }

    static func screenshot(_ app: XCUIApplication, name: String) throws {
        let data = app.screenshot().pngRepresentation
        if data.isEmpty {
            throw EvidenceError.emptyPointer(name)
        }
        try data.write(to: directory().appendingPathComponent(name))
    }

    static func write(_ text: String, to name: String) throws {
        try text.write(to: directory().appendingPathComponent(name), atomically: true, encoding: .utf8)
    }
}

private enum EvidenceError: Error {
    case emptyPointer(String)
}
