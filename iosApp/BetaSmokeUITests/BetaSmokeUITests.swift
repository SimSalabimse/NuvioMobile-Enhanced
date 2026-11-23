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

        let home = waitForTab(app, ["Home"], timeout: 45)
        XCTAssertTrue(
            home.waitForExistence(timeout: 1) && tabIsOnScreen(home),
            "Home did not render. \(describe(home)) Visible buttons: \(visibleButtonLabels(app))"
        )

        let tab = waitForTab(app, ["Settings", "Profile"], timeout: 15)
        XCTAssertTrue(
            tab.waitForExistence(timeout: 1) && tabIsOnScreen(tab),
            "Settings tab was not on screen. \(describe(tab)) Visible buttons: \(visibleButtonLabels(app))"
        )
        tap(tab)

        let opened = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isSelected == true"),
            object: tab
        )
        XCTAssertEqual(XCTWaiter().wait(for: [opened], timeout: 15), .completed, "Settings did not open")
    }

    /// Hidden native tabs stay in the tree with an empty frame until the glass bar is ready.
    /// `isHittable` is not a legal query key, so it is checked on each match.
    private func waitForTab(_ app: XCUIApplication, _ labels: [String], timeout: TimeInterval) -> XCUIElement {
        let start = Date()
        let deadline = start.addingTimeInterval(timeout)
        var last: XCUIElement?
        while Date() < deadline {
            let matches = tabs(app, labels)
            if let hittable = matches.first(where: { $0.isHittable }) {
                return hittable
            }
            // The hidden system bar can report a frame before the glass bar is up.
            // Give the debug launch a moment to release that bar, then accept a real frame.
            if Date() > start.addingTimeInterval(8),
               let framed = matches.first(where: tabIsOnScreen) {
                return framed
            }
            last = matches.last
            RunLoop.current.run(until: Date().addingTimeInterval(0.3))
        }
        return last ?? app.buttons[labels[0]]
    }

    private func tabs(_ app: XCUIApplication, _ labels: [String]) -> [XCUIElement] {
        let queries = [app.buttons, app.tabBars.buttons, app.segmentedControls.buttons]
        var found: [XCUIElement] = []
        for label in labels {
            let predicate = NSPredicate(format: "label == %@", label)
            for query in queries {
                let matches = query.matching(predicate)
                for index in 0..<matches.count {
                    found.append(matches.element(boundBy: index))
                }
            }
        }
        return found
    }

    private func tabIsOnScreen(_ element: XCUIElement) -> Bool {
        let frame = element.frame
        return frame.width > 1 && frame.height > 1 && frame.minX >= 0 && frame.minY >= 0
    }

    private func tap(_ element: XCUIElement) {
        if element.isHittable {
            element.tap()
        } else {
            element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
    }

    private func describe(_ element: XCUIElement) -> String {
        let frame = element.frame
        return "hittable=\(element.isHittable) frame=(\(frame.minX),\(frame.minY) \(frame.width)x\(frame.height))"
    }

    private func visibleButtonLabels(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.prefix(12).map { button in
            button.label.isEmpty ? "(unlabeled)" : button.label
        }.joined(separator: ", ")
    }
}
