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
        // it, and the empty "Who's watching?" picker never appears. Debug binaries honour
        // this argument and open Home. Release launches ignore it.
        app.launchArguments = ["-uiTestSkipOnboarding"]
        app.launch()

        let home = waitForButton(app, ["Home"], timeout: 45)
        XCTAssertTrue(
            home.waitForExistence(timeout: 1) && home.isHittable,
            "Home did not render. Visible buttons: \(visibleButtonLabels(app))"
        )

        let tab = waitForButton(app, ["Settings", "Profile"], timeout: 15)
        XCTAssertTrue(
            tab.waitForExistence(timeout: 1) && tab.isHittable,
            "Settings tab was not on screen. Visible buttons: \(visibleButtonLabels(app))"
        )
        tab.tap()

        let opened = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isSelected == true"),
            object: tab
        )
        XCTAssertEqual(XCTWaiter().wait(for: [opened], timeout: 15), .completed, "Settings did not open")
    }

    /// `isHittable` is not a legal query key path, so the wait checks it on each match.
    private func waitForButton(_ app: XCUIApplication, _ labels: [String], timeout: TimeInterval) -> XCUIElement {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            for label in labels {
                let matches = app.buttons.matching(NSPredicate(format: "label == %@", label))
                for index in 0..<matches.count {
                    let candidate = matches.element(boundBy: index)
                    if candidate.exists, candidate.isHittable {
                        return candidate
                    }
                }
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.3))
        }
        for label in labels {
            let button = app.buttons[label]
            if button.exists { return button }
        }
        return app.buttons[labels[0]]
    }

    private func visibleButtonLabels(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.prefix(12).map { button in
            button.label.isEmpty ? "(unlabeled)" : button.label
        }.joined(separator: ", ")
    }
}
