import XCTest

final class DiagnosticsIntroUITest: XCTestCase {
    private var app: XCUIApplication?

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    override func tearDownWithError() throws {
        if let app {
            captureText(app.debugDescription, name: "diagnostics-intro-hierarchy.txt")
        }
        app = nil
    }

    func testIntroSheetToggleDefaultsOnAndDismisses() throws {
        let app = XCUIApplication()
        self.app = app
        // Preferences were seeded on the Mac before this test. The runner executes
        // on the simulator, so it cannot reseed or write the evidence directory.
        // Screenshots go out as attachments and the host exports them.
        app.launch()

        let title = try wait(app.staticTexts["diagnostics.intro.title"], else: app.staticTexts["Diagnostics log"], timeout: 45)
        let toggle = try wait(app.switches["diagnostics.intro.saveToggle"], else: app.switches["Save diagnostics log"], timeout: 10)
        XCTAssertTrue(title.exists)
        let knob = switchControl(toggle)
        capture(app, name: "diagnostics-intro-default-on.png")
        XCTAssertEqual(try toggleState(knob), "1", "Save diagnostics log should default on\n\(app.debugDescription)")

        // The identifier sits on a container whose center is the label. The switch
        // that changes is the nested control on the trailing edge.
        knob.tap()
        capture(app, name: "diagnostics-intro-toggled-off.png")
        XCTAssertEqual(try waitState(knob, expected: "0"), "0", app.debugDescription)

        knob.tap()
        XCTAssertEqual(try waitState(knob, expected: "1"), "1", app.debugDescription)

        let done = try wait(app.buttons["diagnostics.intro.done"], else: app.buttons["Done"], timeout: 10)
        done.tap()
        XCTAssertTrue(done.waitForNonExistence(timeout: 15), "Diagnostics sheet stayed up\n\(app.debugDescription)")
        capture(app, name: "diagnostics-intro-dismissed.png")

        let shell = shellVisible(app, timeout: 25)
        XCTAssertTrue(shell, "Tab bar was not up after dismissing Diagnostics\n\(app.debugDescription)")
        captureText(app.debugDescription, name: "diagnostics-intro-hierarchy.txt")
    }

    private func switchControl(_ toggle: XCUIElement) -> XCUIElement {
        let nested = toggle.switches.element(boundBy: 0)
        if nested.waitForExistence(timeout: 2) {
            return nested
        }
        return toggle
    }

    private func capture(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func captureText(_ text: String, name: String) {
        let attachment = XCTAttachment(string: text)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
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
