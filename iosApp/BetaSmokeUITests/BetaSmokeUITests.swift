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

        let home = hittableButton(app, ["Home"], timeout: 45)
        XCTAssertTrue(home.waitForExistence(timeout: 5), "Home did not render")

        let tab = hittableButton(app, ["Settings", "Profile"], timeout: 15)
        XCTAssertTrue(tab.waitForExistence(timeout: 5), "Settings tab was not on screen")
        tab.tap()

        let opened = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isSelected == true"),
            object: tab
        )
        XCTAssertEqual(XCTWaiter().wait(for: [opened], timeout: 15), .completed, "Settings did not open")
    }

    private func hittableButton(_ app: XCUIApplication, _ labels: [String], timeout: TimeInterval) -> XCUIElement {
        let query = app.buttons.matching(NSPredicate(format: "label IN %@ AND isHittable == true", labels))
        let button = query.firstMatch
        _ = button.waitForExistence(timeout: timeout)
        return button
    }
}
