import XCTest

final class BetaSmokeUITests: XCTestCase {
    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    func testAppLaunchesHomeRendersAndSettingsOpens() {
        let app = XCUIApplication()
        // A fresh install opens the account screen. "Continue Without Account" sits in a
        // Compose scroll view whose accessibility frame is off-screen, so a tap never leaves
        // it. Debug binaries honour this argument, open Home, and release the tab bar.
        // Release launches ignore it.
        app.launchArguments = ["-uiTestSkipOnboarding"]
        app.launch()

        let home = app.buttons["nuvio-tab-Home"]
        XCTAssertTrue(
            home.waitForExistence(timeout: 45),
            "Home did not render. Visible buttons: \(visibleButtons(app))"
        )

        let settings = app.buttons["nuvio-tab-Settings"]
        XCTAssertTrue(
            settings.waitForExistence(timeout: 15),
            "Settings tab was not on screen. Visible buttons: \(visibleButtons(app))"
        )
        let press = settings.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        press.press(forDuration: 0.2)

        let opened = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isSelected == true"),
            object: settings
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [opened], timeout: 15),
            .completed,
            "Settings did not open. \(describe(settings)) \(describeGate(app))"
        )
    }

    private func describe(_ element: XCUIElement) -> String {
        let frame = element.frame
        let value = element.value as? String ?? ""
        return "hittable=\(element.isHittable) selected=\(element.isSelected) value=\(value) frame=(\(frame.minX),\(frame.minY) \(frame.width)x\(frame.height))"
    }

    private func describeGate(_ app: XCUIApplication) -> String {
        let gate = app.otherElements["nuvio-app-gate"]
        let frame = gate.frame
        return "gateExists=\(gate.exists) gateHittable=\(gate.isHittable) gateFrame=(\(frame.minX),\(frame.minY) \(frame.width)x\(frame.height))"
    }

    private func visibleButtons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.prefix(16).map { button in
            let label = button.label.isEmpty ? "(unlabeled)" : button.label
            return button.identifier.isEmpty ? label : "\(label)#\(button.identifier)"
        }.joined(separator: ", ")
    }
}
